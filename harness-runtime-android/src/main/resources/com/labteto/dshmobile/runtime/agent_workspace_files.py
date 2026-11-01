"""Descriptor-relative file effects; the runtime also applies its existing OS sandbox."""
import fcntl
import hashlib
import io
import itertools
import json
import os
import stat
import sys
import uuid


def fingerprint(descriptor):
    digest = hashlib.sha256()
    offset = 0
    while True:
        chunk = os.pread(descriptor, 65536, offset)
        if not chunk:
            return digest.hexdigest()
        digest.update(chunk)
        offset += len(chunk)


def operate(request):
    parts = request['path'].split('/')
    if not parts or any(part in ('', '.', '..') for part in parts):
        raise ValueError('Invalid workspace-relative path')
    operation = request['operation']
    parent = os.open('.', os.O_RDONLY | os.O_DIRECTORY)
    staged = None
    lock_descriptor = None
    try:
        # Shared-root mutations across independent agent activations/processes
        # must serialize their read/verify/commit sequences.
        if operation in ('write', 'edit'):
            # Lock the root directory inode directly: a persistent lock-file
            # would dirty a Git workspace and could be overwritten by an agent.
            # dup keeps the root inode lock alive while traversing nested dirs.
            lock_descriptor = os.dup(parent)
            fcntl.flock(lock_descriptor, fcntl.LOCK_EX)
        for part in parts[:-1]:
            if operation == 'write':
                try:
                    os.mkdir(part, dir_fd=parent)
                except FileExistsError:
                    pass
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
            os.close(parent)
            parent = child
        name = parts[-1]
        mode = None
        if operation in ('read', 'edit'):
            descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
            with os.fdopen(descriptor, 'rb') as stream:
                info = os.fstat(stream.fileno())
                if not stat.S_ISREG(info.st_mode):
                    raise ValueError('Target must be a regular file')
                before = fingerprint(stream.fileno())
                if operation == 'edit' and request.get('observed') != before:
                    raise ValueError('Read the current file before editing')
                if operation == 'read':
                    start = max(1, request.get('start_line', 1))
                    end = max(start, request.get('end_line', start + 399))
                    reader = io.TextIOWrapper(stream, encoding='utf-8', errors='replace')
                    content = '\n'.join(f'{number}: {line.rstrip(chr(10))}' for number, line in
                        enumerate(itertools.islice(reader, start - 1, end), start))
                    after = fingerprint(reader.buffer.fileno())
                    if before != after:
                        raise ValueError('File changed while reading')
                    return {'content': content, 'fileVersions': {request['original_path']: after}}
                source = stream.read().decode('utf-8')
                if fingerprint(stream.fileno()) != before:
                    raise ValueError('File changed while preparing edit')
                old = request['old_text']
                if not old or source.count(old) != 1:
                    raise ValueError('Replacement text must identify exactly one occurrence')
                content = source.replace(old, request['new_text'], 1)
                mode = stat.S_IMODE(info.st_mode) & 0o777
        elif operation == 'write':
            content = request['content']
            try:
                info = os.stat(name, dir_fd=parent, follow_symlinks=False)
                if not stat.S_ISREG(info.st_mode):
                    raise ValueError('Target must be a regular file')
                mode = stat.S_IMODE(info.st_mode) & 0o777
            except FileNotFoundError:
                pass
        else:
            raise ValueError('Unknown file operation')
        staged = '.agent-write-' + uuid.uuid4().hex
        descriptor = os.open(staged, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW,
                             0o600, dir_fd=parent)
        with os.fdopen(descriptor, 'wb') as output:
            output.write(content.encode('utf-8'))
            output.flush()
            if mode is not None:
                os.fchmod(output.fileno(), mode)
            os.fsync(output.fileno())
        if operation == 'edit':
            # Reopen the current path: the earlier descriptor can point to an
            # unlinked inode after another writer replaces the target.
            current = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
            try:
                current_info = os.fstat(current)
                if (not stat.S_ISREG(current_info.st_mode)
                        or (current_info.st_dev, current_info.st_ino) != (info.st_dev, info.st_ino)
                        or fingerprint(current) != before):
                    raise ValueError('Remote file changed after observation; read it again')
            finally:
                os.close(current)
        os.replace(staged, name, src_dir_fd=parent, dst_dir_fd=parent)
        staged = None
        os.fsync(parent)
        version = hashlib.sha256(content.encode('utf-8')).hexdigest()
        return {'content': '已写入远端文件 ' + request['original_path'],
                'fileVersions': {request['original_path']: version}}
    finally:
        if staged is not None:
            os.unlink(staged, dir_fd=parent)
        os.close(parent)
        if lock_descriptor is not None:
            os.close(lock_descriptor)


if __name__ == '__main__':
    try:
        print(json.dumps(operate(json.load(sys.stdin)), ensure_ascii=False))
    except Exception as failure:
        print(json.dumps({'content': str(failure), 'isError': True,
                          'errorCode': 'REMOTE_FILE_OPERATION_FAILED'}, ensure_ascii=False))
