"""POSIX PTY transport for the bundled Android Python runtime; no downloaded code.

The host registers the child process group before sending START. Until then the
child cannot exec, so an interrupted handshake cannot orphan a user command.
stdin is framed control/data; stdout is exclusively the terminal byte stream
after the single ready line. Child output never becomes transport commands.
"""
import errno
import fcntl
import json
import os
import pty
import selectors
import signal
import struct
import sys
import termios


def kill_session(session):
    # Interactive shells put foreground/background jobs in distinct groups.
    # Kill every group in our PTY session, not merely the shell's initial group.
    groups = {session}
    try:
        namespace = os.readlink('/proc/self/ns/pid')
    except OSError:
        namespace = None
    for entry in os.listdir("/proc"):
        if not entry.isdecimal():
            continue
        try:
            if namespace is not None and os.readlink('/proc/' + entry + '/ns/pid') != namespace:
                continue
            with open("/proc/" + entry + "/stat") as source:
                fields = source.read().rsplit(")", 1)[1].split()
            with open("/proc/" + entry + "/status") as source:
                status = dict(line.split(":", 1) for line in source if ":" in line)
            sid = int((status.get("NSsid", fields[3]) if namespace else fields[3]).split()[-1])
            group = int((status.get("NSpgid", fields[2]) if namespace else fields[2]).split()[-1])
            if sid == session:
                groups.add(group)
        except (OSError, ValueError, IndexError):
            continue
    for group in groups:
        try:
            os.killpg(group, signal.SIGKILL)
        except ProcessLookupError:
            pass


def write_all(fd, data):
    view = memoryview(data)
    while view:
        try:
            count = os.write(fd, view)
        except InterruptedError:
            continue
        if count <= 0:
            raise BrokenPipeError("closed PTY transport")
        view = view[count:]


def run():
    command = json.loads(sys.argv[1])
    rows, columns = int(sys.argv[2]), int(sys.argv[3])
    gate_read, gate_write = os.pipe()
    pid, master = pty.fork()
    if pid == 0:
        os.close(gate_write)
        permitted = os.read(gate_read, 1) == b"S"
        os.close(gate_read)
        if not permitted:
            os._exit(125)
        os.environ.setdefault("TERM", "xterm-256color")
        try:
            os.execvpe(command[0], command, os.environ)
        except OSError as error:
            write_all(2, ("PTY exec failed: " + str(error) + "\n").encode())
            os._exit(127)

    os.close(gate_read)
    selector = selectors.DefaultSelector()
    input_buffer = bytearray()
    pending_input = bytearray()
    pending_output = bytearray()
    output_open = True
    child_status = None
    started = False
    def watch(fd, mask, label):
        try:
            selector.get_key(fd)
        except KeyError:
            if mask:
                selector.register(fd, mask, label)
        else:
            if mask:
                selector.modify(fd, mask, label)
            else:
                selector.unregister(fd)

    try:
        fcntl.ioctl(master, termios.TIOCSWINSZ, struct.pack("HHHH", rows, columns, 0, 0))
        # The managed process startup already separated its PID from this stream.
        write_all(1, ("DSH_PTY_V1:" + str(pid) + "\n").encode())
        for fd in (0, 1, master):
            os.set_blocking(fd, False)
        selector.register(0, selectors.EVENT_READ, "input")
        selector.register(master, selectors.EVENT_READ, "terminal")
        while output_open or pending_output:
            if child_status is None:
                observed, status = os.waitpid(pid, os.WNOHANG)
                if observed:
                    child_status = status
            # Both directions are nonblocking: a child producing output while it
            # reads large stdin must not deadlock the bridge or the application.
            watch(0, selectors.EVENT_READ if len(pending_input) < 65536 else 0, "input")
            if output_open:
                watch(master, (selectors.EVENT_READ if len(pending_output) < 65536 else 0) |
                      (selectors.EVENT_WRITE if pending_input else 0), "terminal")
            watch(1, selectors.EVENT_WRITE if pending_output else 0, "output")
            for key, events in selector.select(0.1):
                if key.data == "input":
                    data = os.read(0, 65536)
                    if not data:
                        return 125
                    input_buffer.extend(data)
                    while len(input_buffer) >= 5:
                        operation = input_buffer[0]
                        size = struct.unpack("!I", input_buffer[1:5])[0]
                        if size > 65536:
                            raise ValueError("PTY frame exceeds 64 KiB")
                        if len(input_buffer) < 5 + size:
                            break
                        payload = bytes(input_buffer[5:5 + size])
                        del input_buffer[:5 + size]
                        if operation == ord("S") and not started and not payload:
                            write_all(gate_write, b"S")
                            os.close(gate_write)
                            gate_write = -1
                            started = True
                        elif operation == ord("D") and started:
                            pending_input.extend(payload)
                        elif operation == ord("R") and started and len(payload) == 8:
                            height, width = struct.unpack("!II", payload)
                            if not (1 <= height <= 65535 and 1 <= width <= 65535):
                                raise ValueError("invalid PTY dimensions")
                            fcntl.ioctl(master, termios.TIOCSWINSZ,
                                        struct.pack("HHHH", height, width, 0, 0))
                        elif operation == ord("C") and not payload:
                            return 125
                        else:
                            raise ValueError("invalid PTY control frame")
                elif key.data == "output":
                    try:
                        count = os.write(1, pending_output)
                        del pending_output[:count]
                    except BlockingIOError:
                        pass
                else:
                    if events & selectors.EVENT_READ:
                        try:
                            data = os.read(master, 65536)
                        except OSError as error:
                            if error.errno != errno.EIO:
                                raise
                            data = b""
                        if data:
                            pending_output.extend(data)
                        else:
                            output_open = False
                            watch(master, 0, "terminal")
                    if output_open and events & selectors.EVENT_WRITE:
                        try:
                            count = os.write(master, pending_input)
                            del pending_input[:count]
                        except BlockingIOError:
                            pass
        if child_status is None:
            _, child_status = os.waitpid(pid, 0)
        exit_code = os.waitstatus_to_exitcode(child_status)
        return exit_code if exit_code >= 0 else 128 - exit_code
    finally:
        # Also reap background jobs that outlived the session leader.
        kill_session(pid)
        if gate_write >= 0:
            os.close(gate_write)
        selector.close()
        os.close(master)
        if child_status is None:
            os.waitpid(pid, 0)


if __name__ == "__main__":
    sys.exit(run())
