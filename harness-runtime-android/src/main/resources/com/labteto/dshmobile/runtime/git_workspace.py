"""Local Git worktree lifecycle. Snapshot indexes never replace the user's index."""
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


def git(root, *args, data=None, env=None, accepted=(0,)):
    result = subprocess.run(["git", "-c", "core.fsmonitor=false", "-C", str(root), *args],
                            input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    if result.returncode not in accepted:
        raise RuntimeError(result.stderr.decode(errors="replace").strip() or "Git operation failed")
    return result


def text(root, *args):
    return git(root, *args).stdout.decode().strip()


def snapshot(root):
    # GIT_INDEX_FILE is private even for repositories with staged/unstaged overlap.
    with tempfile.TemporaryDirectory(prefix="agent-index-") as directory:
        env = dict(os.environ, GIT_INDEX_FILE=str(Path(directory) / "index"))
        git(root, "read-tree", "HEAD", env=env)
        git(root, "add", "-A", "--", ".", env=env)
        return git(root, "write-tree", env=env).stdout.decode().strip()


def commit(root, tree, parent):
    env = dict(os.environ, GIT_AUTHOR_NAME="777 workspace", GIT_AUTHOR_EMAIL="workspace@localhost",
               GIT_COMMITTER_NAME="777 workspace", GIT_COMMITTER_EMAIL="workspace@localhost")
    return git(root, "commit-tree", tree, "-p", parent, data=b"Agent workspace snapshot\n", env=env).stdout.decode().strip()


def save(path, value):
    temporary = path.with_suffix(".tmp")
    with temporary.open("w") as output:
        json.dump(value, output, ensure_ascii=False)
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)
    descriptor = os.open(str(path.parent), os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def validate_worktree(source, directory):
    if text(directory, "rev-parse", "--show-toplevel") != str(directory):
        raise ValueError("workspace directory identity changed")
    if text(directory, "rev-parse", "--path-format=absolute", "--git-common-dir") != text(
            source, "rev-parse", "--path-format=absolute", "--git-common-dir"):
        raise ValueError("workspace Git identity changed")


def remove_unadmitted(source, directory):
    registered = git(source, "worktree", "list", "--porcelain", "-z").stdout.split(b"\0")
    if b"worktree " + os.fsencode(directory) in registered:
        git(source, "worktree", "remove", "--force", str(directory))
    elif directory.is_symlink():
        directory.unlink()
    elif directory.exists():
        shutil.rmtree(directory)


def tree_entries(source, tree, paths):
    wanted = set(paths)
    entries = {}
    for record in git(source, "ls-tree", "-r", "-z", tree).stdout.split(b"\0"):
        if not record:
            continue
        header, path = record.split(b"\t", 1)
        name = os.fsdecode(path)
        if name in wanted:
            mode, kind, oid = header.decode().split()
            if kind != "blob":
                raise ValueError("integration requires file-level changes: " + name)
            entries[name] = dict(mode=mode, oid=oid)
    return entries


def local_entry(source, path):
    # Never traverse a changed parent symlink while inspecting/recovering a merge.
    target = source / path
    for parent in target.parents:
        if parent == source:
            break
        if parent.is_symlink():
            return dict(mode="parent_symlink")
    if target.is_symlink():
        data = os.fsencode(os.readlink(target))
        mode = "120000"
    elif target.is_file():
        data = target.read_bytes()
        mode = "100755" if target.stat().st_mode & 0o111 else "100644"
    elif target.is_dir():
        return dict(mode="directory")
    elif not target.exists():
        return None
    else:
        return dict(mode="special")
    oid = git(source, "hash-object", "--stdin", data=data).stdout.decode().strip()
    return dict(mode=mode, oid=oid)


def prepare_integration(source, manifest, admission, parent_tree, merged_tree, child_tree):
    paths = [os.fsdecode(p) for p in git(source, "diff", "--name-only", "--no-renames", "-z",
                                       parent_tree, merged_tree).stdout.split(b"\0") if p]
    after = tree_entries(source, merged_tree, paths)
    backup = manifest.parent / "integration-backup"
    backup.mkdir(exist_ok=True)
    records = {}
    for index, path in enumerate(paths):
        before = local_entry(source, path)
        if before is not None and before["mode"] not in ("100644", "100755", "120000", "directory"):
            raise ValueError("cannot snapshot integration path: " + path)
        if before is not None and before["mode"] != "directory":
            target = source / path
            data = os.fsencode(os.readlink(target)) if target.is_symlink() else target.read_bytes()
            if git(source, "hash-object", "--stdin", data=data).stdout.decode().strip() != before["oid"]:
                raise ValueError("source changed while preparing integration: " + path)
            with (backup / str(index)).open("wb") as output:
                output.write(data); output.flush(); os.fsync(output.fileno())
            before = dict(before, backup=str(index))
        records[path] = dict(before=before, after=after.get(path))
    descriptor = os.open(str(backup), os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    stage = manifest.parent / "integration-stage"
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir()
    for path in sorted(records, key=lambda p: (len(Path(p).parts), p)):
        before = records[path]["before"]
        if before is None:
            continue
        target = stage / path
        target.parent.mkdir(parents=True, exist_ok=True)
        if before["mode"] == "directory":
            target.mkdir(exist_ok=True)
        else:
            data = (backup / before["backup"]).read_bytes()
            if before["mode"] == "120000":
                target.symlink_to(os.fsdecode(data))
            else:
                target.write_bytes(data)
                target.chmod(0o755 if before["mode"] == "100755" else 0o644)
    integration = dict(state="staging", source_tree=parent_tree, target_tree=merged_tree,
                       child_tree=child_tree, paths=records, unresolved_paths=[])
    admission["integration"] = integration
    save(manifest, admission)
    # The backup belongs to the exact preflight version, not a transient concurrent edit.
    if snapshot(source) != parent_tree or any(local_entry(source, path) !=
            ({k: v for k, v in item["before"].items() if k != "backup"} if item["before"] else None)
            for path, item in records.items()):
        integration["state"] = "not_started"
        save(manifest, admission)
        raise ValueError("source changed during merge preparation; inspect and retry")
    return integration


def publish_integration(source, stage, manifest, admission):
    integration = admission["integration"]
    records = integration["paths"]
    for path, item in records.items():
        item["after"] = local_entry(stage, path)
    # Exact post-images are known before any source file is touched.
    integration["state"] = "applying"
    save(manifest, admission)
    deleted = sorted((p for p in records if records[p]["after"] is None),
                     key=lambda p: (len(Path(p).parts), p), reverse=True)
    written = sorted((p for p in records if records[p]["after"] is not None),
                     key=lambda p: (len(Path(p).parts), p))
    for path in deleted + written:
        item = records[path]
        before = item["before"]
        expected = {k: v for k, v in before.items() if k != "backup"} if before else None
        if local_entry(source, path) != expected:
            raise ValueError("source changed before publishing integration: " + path)
        target = source / path
        after = item["after"]
        if after is None:
            if target.is_dir() and not target.is_symlink():
                target.rmdir()
            elif target.exists() or target.is_symlink():
                target.unlink()
        elif after["mode"] == "directory":
            if target.is_file() or target.is_symlink():
                target.unlink()
            target.mkdir(parents=True, exist_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.is_dir() and not target.is_symlink():
                target.rmdir()  # Never remove an unexpected descendant recursively.
            descriptor, temporary = tempfile.mkstemp(prefix="publish-", dir=manifest.parent / "integration-backup")
            try:
                data = os.fsencode(os.readlink(stage / path)) if after["mode"] == "120000" else (stage / path).read_bytes()
                with os.fdopen(descriptor, "wb") as output:
                    output.write(data); output.flush(); os.fsync(output.fileno())
                if after["mode"] == "120000":
                    os.unlink(temporary); os.symlink(os.fsdecode(data), temporary)
                else:
                    os.chmod(temporary, 0o755 if after["mode"] == "100755" else 0o644)
                os.replace(temporary, target)
            finally:
                if os.path.lexists(temporary):
                    os.unlink(temporary)
        descriptor = os.open(str(target.parent), os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


def recover_integration(source, manifest, admission):
    integration = admission["integration"]
    if integration["state"] == "staging":
        integration["state"] = "not_started"
        save(manifest, admission)
        return dict(workspace_id=admission["workspace_id"], state="not_started", integration=integration)
    if snapshot(source) == integration["target_tree"]:
        integration["state"] = "merged"
        integration["unresolved_paths"] = []
        save(manifest, admission)
        return dict(workspace_id=admission["workspace_id"], state="merged", integration=integration,
                    source_tree=integration["target_tree"], tree=integration["child_tree"])
    unresolved = []
    records = integration["paths"]
    # Remove only exact newly-created outputs, deepest first, then restore prior files.
    ordered = sorted(records, key=lambda path: (len(Path(path).parts), path), reverse=True)
    for path in ordered:
        item = records[path]
        before = item["before"]
        expected_before = {k: v for k, v in before.items() if k != "backup"} if before else None
        current = local_entry(source, path)
        if current == expected_before:
            continue
        target = source / path
        if current == dict(mode="directory") and item["after"] is None:
            try:
                target.rmdir()
                current = None
            except OSError:
                unresolved.append(path)
                continue
        if current != item["after"]:
            unresolved.append(path)
            continue
        if before is None:
            if target.exists() or target.is_symlink():
                target.unlink()
        elif before["mode"] == "directory":
            if target.exists() or target.is_symlink():
                target.unlink()
            target.mkdir(parents=True, exist_ok=True)
        else:
            data = (manifest.parent / "integration-backup" / before["backup"]).read_bytes()
            if git(source, "hash-object", "--stdin", data=data).stdout.decode().strip() != before["oid"]:
                raise RuntimeError("integration backup is not intact: " + path)
            target.parent.mkdir(parents=True, exist_ok=True)
            # A private sibling and rename avoid truncating the destination during rollback.
            if current == dict(mode="directory"):
                try:
                    target.rmdir()
                except OSError:
                    unresolved.append(path)
                    continue
                current = None
            descriptor, temporary = tempfile.mkstemp(prefix="restore-", dir=manifest.parent / "integration-backup")
            try:
                with os.fdopen(descriptor, "wb") as output:
                    output.write(data); output.flush(); os.fsync(output.fileno())
                if before["mode"] == "120000":
                    os.unlink(temporary)
                    os.symlink(os.fsdecode(data), temporary)
                else:
                    os.chmod(temporary, 0o755 if before["mode"] == "100755" else 0o644)
                if local_entry(source, path) != current:
                    unresolved.append(path)
                    continue
                os.replace(temporary, target)
            finally:
                if os.path.lexists(temporary):
                    os.unlink(temporary)
        descriptor = os.open(str(target.parent), os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    integration["state"] = "needs_inspection" if unresolved else "rolled_back"
    integration["unresolved_paths"] = sorted(set(unresolved))
    save(manifest, admission)
    return dict(workspace_id=admission["workspace_id"], state=integration["state"], integration=integration)


def operate(root, store, owner, action, arguments):
    root, store = Path(root).resolve(), Path(store).resolve()
    if store == root or root in store.parents:
        raise ValueError("workspace store must be outside the source workspace")
    store.mkdir(parents=True, exist_ok=True)
    # Process death releases this lock. The Git metadata + immutable admission manifest
    # own workspace facts; no Agent registry or second execution queue exists here.
    with (store / "lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        return locked(root, store, owner, action, arguments)


def locked(root, store, owner, action, arguments):
    if action == "list":
        workspaces = []
        for path in sorted(store.glob("*/admission.json")):
            admission = json.loads(path.read_text())
            if admission["owner"] == owner:
                workspaces.append(admission)
        return dict(workspaces=workspaces)
    workspace_id = arguments["workspace_id"]
    if not re.fullmatch(r"[0-9a-f]{32}", workspace_id):
        raise ValueError("invalid workspace identity")
    container = store / workspace_id
    manifest = container / "admission.json"
    directory = container / "tree"
    if action == "fork" and not manifest.exists():
        source = (root / arguments.get("repository", ".")).resolve()
        if source != root and root not in source.parents:
            raise ValueError("repository outside workspace")
        if text(source, "rev-parse", "--show-toplevel") != str(source):
            raise ValueError("repository must identify its Git top-level directory")
        head = text(source, "rev-parse", "HEAD")
        tree = snapshot(source)
        base = commit(source, tree, head)
        container.mkdir()
        save(manifest, dict(workspace_id=workspace_id, owner=owner, invocation_id=arguments.get("invocation_id"), source=str(source),
                            root=str(directory), base_commit=base, base_tree=tree, state="creating"))
    if not manifest.is_file():
        raise ValueError("workspace not found")
    admission = json.loads(manifest.read_text())
    if admission["owner"] != owner:
        raise ValueError("workspace belongs to another session")
    source = Path(admission["source"]).resolve()
    if source != root and root not in source.parents:
        raise ValueError("recorded repository outside workspace")
    if admission["root"] != str(directory):
        raise ValueError("workspace path does not match admission")
    if action == "fork":
        requested = (root / arguments.get("repository", ".")).resolve()
        if requested != source:
            raise ValueError("workspace identity already admitted for a different repository")
        if admission["state"] == "discarded":
            raise ValueError("workspace was discarded; use a new invocation")
        if admission["state"] == "creating":
            # No child is exposed until READY is durable. Repair only this
            # unadmitted allocation after an interrupted worktree creation.
            remove_unadmitted(source, directory)
            git(source, "worktree", "add", "--detach", str(directory), admission["base_commit"])
            admission["state"] = "ready"
            save(manifest, admission)
        validate_worktree(source, directory)
        return admission
    if action == "discard" and admission["state"] == "discarded":
        return dict(workspace_id=workspace_id, state="discarded")
    if admission["state"] == "creating" and action == "discard":
        if arguments.get("expected_tree") != admission["base_tree"]:
            raise ValueError("unadmitted workspace baseline changed")
        remove_unadmitted(source, directory)
        admission["state"] = "discarded"
        save(manifest, admission)
        return dict(workspace_id=workspace_id, state="discarded")
    if admission["state"] != "ready":
        raise ValueError("workspace is not ready: " + admission["state"])
    validate_worktree(source, directory)
    if action == "locate":
        return admission
    child_tree = snapshot(directory)
    base_tree = admission["base_tree"]
    if action == "status":
        patch = git(source, "diff", "--binary", "--full-index", base_tree, child_tree).stdout
        return dict(admission, tree=child_tree, changed=child_tree != base_tree,
                    diff=patch[:262144].decode(errors="replace"), diff_truncated=len(patch) > 262144)
    if arguments.get("expected_tree") != child_tree:
        raise ValueError("workspace changed since review; inspect again")
    if action == "discard":
        if admission.get("integration", {}).get("state") in ("staging", "applying", "needs_inspection"):
            raise ValueError("recover the pending integration before discarding its workspace")
        git(source, "worktree", "remove", "--force", str(directory))
        admission["state"] = "discarded"
        save(manifest, admission)
        return dict(workspace_id=workspace_id, state="discarded")
    if action != "merge":
        raise ValueError("unknown workspace operation")
    pending = admission.get("integration")
    if pending and pending["state"] in ("staging", "applying", "needs_inspection"):
        return recover_integration(source, manifest, admission)
    if "allowed_paths" in arguments:
        allowed = arguments["allowed_paths"]
        if not isinstance(allowed, list) or not allowed or any(
                not isinstance(path, str) or not path or path.startswith("/") or
                any(part in ("", ".", "..") for part in path.split("/")) for path in allowed):
            raise ValueError("invalid integration paths")
        changed = git(source, "diff", "--name-only", "--no-renames", "-z", base_tree, child_tree).stdout
        paths = [os.fsdecode(path) for path in changed.split(b"\0") if path]
        if any(not any(path == scope or path.startswith(scope + "/") for scope in allowed) for path in paths):
            raise ValueError("workspace contains changes outside declared outputs; inspect before merging")
    parent_tree = snapshot(source)
    parent = commit(source, parent_tree, admission["base_commit"])
    child = commit(source, child_tree, admission["base_commit"])
    merge = git(source, "merge-tree", "--write-tree", "--merge-base=" + admission["base_commit"],
                parent, child, accepted=(0, 1))
    if merge.returncode == 1:
        return dict(workspace_id=workspace_id, state="conflict", tree=child_tree,
                    source_tree=parent_tree, detail=merge.stdout.decode(errors="replace")[:262144])
    merged_tree = merge.stdout.decode().splitlines()[0]
    patch = git(source, "diff", "--binary", "--full-index", parent_tree, merged_tree).stdout
    if patch:
        git(source, "apply", "--check", "--binary", "--whitespace=nowarn", "-", data=patch)
        if snapshot(source) != parent_tree:
            raise ValueError("source changed during merge; inspect and retry")
        integration = prepare_integration(source, manifest, admission, parent_tree, merged_tree, child_tree)
        try:
            stage = manifest.parent / "integration-stage"
            git(stage, "apply", "--binary", "--whitespace=nowarn", "-", data=patch)
            publish_integration(source, stage, manifest, admission)
            actual = snapshot(source)
            if actual != merged_tree:
                raise RuntimeError("merge source changed while applying; inspect integration outcome")
            integration["state"] = "merged"
            save(manifest, admission)
        except Exception as failure:
            try:
                recover_integration(source, manifest, admission)
            except Exception as recovery_error:
                raise RuntimeError("merge and recovery incomplete; inspect original integration record") from recovery_error
            raise
    else:
        actual = snapshot(source)
    return dict(workspace_id=workspace_id, state="merged" if patch else "already_present",
                tree=child_tree, source_tree=actual, base_tree=base_tree)


if __name__ == "__main__":
    try:
        print(json.dumps(operate(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4],
                                 json.loads(sys.stdin.read())), ensure_ascii=False))
    except Exception as error:
        print(json.dumps(dict(error=str(error)), ensure_ascii=False))
        sys.exit(1)
