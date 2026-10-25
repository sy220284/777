"""Fail-closed Linux child sandbox. Uses shipped Python/ctypes; never downloads a helper.

Landlock controls path resolution (including symlinks, rename and truncate). Seccomp
blocks networking, foreign-process access and alternate syscall ABIs. Both survive exec
and descendants. The host retains the original process/PTY and tool receipt ownership.
"""
import ctypes
import errno
import json
import os
import platform
import sys

libc = ctypes.CDLL(None, use_errno=True)
libc.syscall.restype = ctypes.c_long


def checked(result, operation):
    if result < 0:
        raise OSError(ctypes.get_errno(), operation)
    return result


class Ruleset(ctypes.Structure):
    _fields_ = [("handled_access_fs", ctypes.c_uint64)]


class Beneath(ctypes.Structure):
    _pack_ = 1
    _fields_ = [("allowed_access", ctypes.c_uint64), ("parent_fd", ctypes.c_int32)]


class Filter(ctypes.Structure):
    _fields_ = [("code", ctypes.c_ushort), ("jt", ctypes.c_ubyte),
                ("jf", ctypes.c_ubyte), ("k", ctypes.c_uint32)]


class Program(ctypes.Structure):
    _fields_ = [("len", ctypes.c_ushort), ("filter", ctypes.POINTER(Filter))]


def filesystem(policy):
    abi = checked(libc.syscall(444, 0, 0, 1), "Landlock ABI unavailable")
    if abi < 3:
        raise RuntimeError("Landlock ABI >= 3 required (truncate/rename enforcement)")
    # EXECUTE..TRUNCATE. Device IOCTL is denied by default when the ABI supports it.
    handled = (1 << (16 if abi >= 5 else 15)) - 1
    ruleset = Ruleset(handled)
    fd = checked(libc.syscall(444, ctypes.byref(ruleset), ctypes.sizeof(ruleset), 0), "Landlock ruleset")
    read = (1 << 0) | (1 << 2) | (1 << 3)
    regular = (1 << 0) | (1 << 1) | (1 << 2) | (1 << 14) | (1 << 15)
    try:
        for path, write in [(p, False) for p in policy["read"]] + [(p, True) for p in policy["write"]]:
            if not os.path.exists(path):
                continue
            parent = os.open(path, os.O_PATH | os.O_CLOEXEC)
            try:
                access = handled if write else read
                if not os.path.isdir(path):
                    access &= regular
                # Filesystem sandbox never grants device ioctl rights, even to a writable tree.
                access &= ~((1 << 15) | (1 << 6) | (1 << 9) | (1 << 11))
                attr = Beneath(access, parent)
                checked(libc.syscall(445, fd, 1, ctypes.byref(attr), 0), "Landlock path rule")
            finally:
                os.close(parent)
        checked(libc.prctl(38, 1, 0, 0, 0), "no_new_privs")
        checked(libc.syscall(446, fd, 0), "Landlock restrict_self")
    finally:
        os.close(fd)


def syscalls():
    machine = platform.machine().lower()
    if machine in ("x86_64", "amd64"):
        arch = 0xC000003E
        denied = [41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 53, 54, 55, 62, 101,
                  155, 161, 165, 166, 167, 168, 169, 170, 171, 172, 173, 174, 175,
                  176, 200, 234, 246, 248, 249, 250, 272, 288, 298, 299, 303, 304, 307, 308,
                  310, 311, 313, 321, 323, 424, 425, 426, 427, 428, 429, 430, 431,
                  432, 433, 434, 438, 440, 442]
        ioctl_nr = 16
    elif machine in ("aarch64", "arm64"):
        arch = 0xC00000B7
        denied = [39, 40, 41, 51, 97, 104, 105, 106, 117, 129, 130, 131, 142,
                  198, 199, 200, 201, 202, 203, 206, 207, 208, 209, 210, 211, 212,
                  217, 218, 219, 241, 242, 243, 264, 265, 268, 270, 271, 272,
                  273, 280, 282, 424, 425, 426, 427, 428, 429, 430, 431, 432, 433,
                  434, 438, 440, 442]
        ioctl_nr = 29
    else:
        raise RuntimeError("unsupported sandbox syscall ABI")
    deny = 0x00050000 | errno.EPERM
    # BPF_LD|W|ABS, JMP|JEQ|K, RET|K. Kill mismatched arch; reject x32 nr space.
    code = [Filter(0x20, 0, 0, 4), Filter(0x15, 1, 0, arch), Filter(0x06, 0, 0, 0x80000000),
            Filter(0x20, 0, 0, 0), Filter(0x35, 0, 1, 0x40000000), Filter(0x06, 0, 0, deny)]
    for number in sorted(set(denied)):
        code += [Filter(0x15, 0, 1, number), Filter(0x06, 0, 0, deny)]
    # Deny all non-terminal ioctl, including Binder/device escape paths.
    tty = [0x5401, 0x5402, 0x5403, 0x5404, 0x540F, 0x5410, 0x5413, 0x5414, 0x541B]
    code += [Filter(0x15, 0, 2 * len(tty) + 2, ioctl_nr), Filter(0x20, 0, 0, 24)]
    for operation in tty:
        code += [Filter(0x15, 0, 1, operation), Filter(0x06, 0, 0, 0x7FFF0000)]
    code += [Filter(0x06, 0, 0, deny), Filter(0x06, 0, 0, 0x7FFF0000)]
    instructions = (Filter * len(code))(*code)
    program = Program(len(code), instructions)
    checked(libc.prctl(22, 2, ctypes.byref(program), 0, 0), "seccomp filter")


def main():
    policy = json.loads(sys.argv[1])
    command = json.loads(sys.argv[2])
    if not command or not policy["write"]:
        raise ValueError("sandbox command/workspace required")
    # The trusted launcher supplies absolute fixed roots, never tool-provided environment overrides.
    workspace = os.path.realpath(policy["write"][0])
    os.chdir(workspace)
    # No inherited app socket, file or Binder descriptor may bypass path mediation.
    for descriptor in os.listdir("/proc/self/fd"):
        number = int(descriptor)
        if number >= 3:
            try:
                os.close(number)
            except OSError as failure:
                if failure.errno != errno.EBADF:
                    raise
    filesystem(policy)
    syscalls()
    if len(sys.argv) > 3:
        child_environment = json.loads(sys.argv[3])
        os.environ.clear()
        os.environ.update(child_environment)
    os.environ["HOME"] = workspace
    os.environ["TMPDIR"] = workspace
    os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
    os.execvpe(command[0], command, os.environ)


if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        print("PROCESS_ISOLATION_UNAVAILABLE: " + str(failure), file=sys.stderr)
        sys.exit(125)
