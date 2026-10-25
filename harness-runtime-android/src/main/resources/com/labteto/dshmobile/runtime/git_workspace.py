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
        save(manifest, dict(workspace_id=workspace_id, owner=owner, source=str(source),
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
        git(source, "worktree", "remove", "--force", str(directory))
        admission["state"] = "discarded"
        save(manifest, admission)
        return dict(workspace_id=workspace_id, state="discarded")
    if action != "merge":
        raise ValueError("unknown workspace operation")
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
        git(source, "apply", "--binary", "--whitespace=nowarn", "-", data=patch)
    actual = snapshot(source)
    if actual != merged_tree:
        raise RuntimeError("merge requires inspection: source changed while applying; do not blindly replay")
    return dict(workspace_id=workspace_id, state="merged" if patch else "already_present",
                tree=child_tree, source_tree=actual, base_tree=base_tree)


if __name__ == "__main__":
    try:
        print(json.dumps(operate(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4],
                                 json.loads(sys.stdin.read())), ensure_ascii=False))
    except Exception as error:
        print(json.dumps(dict(error=str(error)), ensure_ascii=False))
        sys.exit(1)
