"""Local Git worktree lifecycle. Snapshot indexes never replace the user's index."""
import fcntl
import hashlib
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


MERGE_RESOLUTIONS = ("auto", "prefer_parent", "prefer_child", "select")
MAX_REVIEW_BYTES = 262144


def changed_paths(source, before, after):
    data = git(source, "diff", "--name-only", "--no-renames", "-z", before, after).stdout
    return {os.fsdecode(part) for part in data.split(b"\0") if part}


def tree_blobs(source, tree):
    entries = {}
    for record in git(source, "ls-tree", "-r", "-z", tree).stdout.split(b"\0"):
        if not record:
            continue
        header, path = record.split(b"\t", 1)
        mode, kind, oid = header.decode().split()
        entries[os.fsdecode(path)] = (mode, kind, oid)
    return entries


def merge_comparison(source, admission, parent_tree, child_tree):
    base = admission["base_tree"]
    parent = changed_paths(source, base, parent_tree)
    child = changed_paths(source, base, child_tree)
    return dict(parent_only_paths=sorted(parent - child),
                child_only_paths=sorted(child - parent),
                overlap_paths=sorted(parent & child))


def choose_file_versions(source, parent_tree, child_tree, comparison, resolution, choices):
    """Use the exact selected whole-file versions; unrelated parent edits remain intact."""
    overlap = set(comparison["overlap_paths"])
    child_only = set(comparison["child_only_paths"])
    parent_only = set(comparison["parent_only_paths"])
    if resolution == "select":
        if (not overlap.issubset(choices) or
                not set(choices).issubset(overlap | child_only | parent_only) or
                any(choice not in ("parent", "child") for choice in choices.values())):
            raise ValueError("select requires a parent/child choice for every overlapping path; "
                             "optional choices may drop independent changes")
    elif choices:
        raise ValueError("path_choices are only valid for select resolution")
    # The default keeps independent changes, but select can explicitly drop a
    # child-only addition or revert a parent-only edit when it depends on a worse
    # implementation. No line-level Frankenstein merge is silently manufactured.
    use_child = {path for path in child_only if choices.get(path, "child") == "child"}
    use_child.update(path for path in parent_only if choices.get(path, "parent") == "child")
    use_child.update(path for path in overlap if
                     (resolution == "prefer_child" or
                      (resolution == "select" and choices[path] == "child")))
    if not use_child:
        return parent_tree
    parent_files = tree_blobs(source, parent_tree)
    child_files = tree_blobs(source, child_tree)
    # File/directory path replacement must be handled by an explicit new candidate
    # worktree rather than guessing which unrelated descendant should be removed.
    retained = set(parent_files) - use_child
    for path in use_child:
        if any(other.startswith(path + "/") or path.startswith(other + "/")
               for other in retained):
            raise ValueError("file/directory overlap needs a reworked workspace: " + path)
    with tempfile.TemporaryDirectory(prefix="merge-choice-") as directory:
        env = dict(os.environ, GIT_INDEX_FILE=str(Path(directory) / "index"))
        git(source, "read-tree", parent_tree, env=env)
        instructions = bytearray()
        for path in sorted(use_child):
            entry = child_files.get(path)
            if entry is not None and entry[1] != "blob":
                raise ValueError("non-file merge needs a reworked workspace: " + path)
            mode, oid = (entry[0], entry[2]) if entry else ("0", "0" * 40)
            instructions.extend(f"{mode} {oid}\t".encode() + os.fsencode(path) + b"\0")
        git(source, "update-index", "-z", "--index-info", data=bytes(instructions), env=env)
        return git(source, "write-tree", env=env).stdout.decode().strip()


def merge_proposal(source, admission, child_tree, resolution="auto", choices=None):
    """Compute a precisely versioned choice; Git's text auto-merge is one option, not a verdict."""
    if resolution not in MERGE_RESOLUTIONS:
        raise ValueError("unknown merge resolution")
    choices = {} if choices is None else choices
    if not isinstance(choices, dict) or any(not isinstance(path, str) for path in choices):
        raise ValueError("invalid path_choices")
    parent_tree = snapshot(source)
    comparison = merge_comparison(source, admission, parent_tree, child_tree)
    if resolution == "auto":
        if choices:
            raise ValueError("auto resolution cannot accept path choices")
        parent = commit(source, parent_tree, admission["base_commit"])
        child = commit(source, child_tree, admission["base_commit"])
        attempt = git(source, "merge-tree", "--write-tree", "--merge-base=" + admission["base_commit"],
                      parent, child, accepted=(0, 1))
        if attempt.returncode == 1:
            return dict(state="conflict", source_tree=parent_tree, comparison=comparison,
                        resolution=resolution, detail=attempt.stdout.decode(errors="replace")[:MAX_REVIEW_BYTES])
        merged_tree = attempt.stdout.decode().splitlines()[0]
    else:
        merged_tree = choose_file_versions(source, parent_tree, child_tree, comparison, resolution, choices)
    patch = git(source, "diff", "--binary", "--full-index", parent_tree, merged_tree).stdout
    return dict(state="ready", source_tree=parent_tree, merged_tree=merged_tree, patch=patch,
                resolution=resolution, comparison=comparison)


def review_receipt(proposal, offset, page):
    digest = hashlib.sha256()
    digest.update(proposal["source_tree"].encode() + b"\0" +
                  proposal["merged_tree"].encode() + b"\0" +
                  str(offset).encode() + b"\0" + page)
    return digest.hexdigest()


def validate_quality_decision(decision, resolution, comparison):
    """A review is an attributable judgment, not an assertion that tests executed."""
    if not isinstance(decision, dict) or decision.get("resolution") != resolution:
        raise ValueError("merge quality decision is missing or does not match resolution")
    reason = decision.get("reason")
    if not isinstance(reason, str) or len(reason.strip()) < 12 or len(reason) > 4000:
        raise ValueError("merge quality decision requires a concrete reason")
    criteria = decision.get("criteria")
    if not isinstance(criteria, dict) or not all(
            isinstance(criteria.get(key), str) and criteria[key].strip()
            for key in ("correctness", "experience", "maintainability")):
        raise ValueError("merge quality decision must compare correctness, experience and maintainability")
    if comparison["overlap_paths"] and not decision.get("reviewed_both"):
        raise ValueError("overlapping implementations require explicit comparison of both sides")


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
    if action in ("compare_merge", "preview_merge"):
        pending = admission.get("integration")
        if pending and pending["state"] in ("staging", "applying", "needs_inspection"):
            raise ValueError("pending integration must be recovered before another merge review")
        parent_tree = snapshot(source)
        comparison = merge_comparison(source, admission, parent_tree, child_tree)
        if action == "compare_merge":
            parent_diff = git(source, "diff", "--binary", "--full-index",
                              base_tree, parent_tree).stdout
            child_diff = git(source, "diff", "--binary", "--full-index",
                             base_tree, child_tree).stdout
            if len(parent_diff) > MAX_REVIEW_BYTES or len(child_diff) > MAX_REVIEW_BYTES:
                return dict(state="needs_full_review", workspace_id=workspace_id,
                            tree=child_tree, source_tree=parent_tree, comparison=comparison,
                            parent_diff_bytes=len(parent_diff), child_diff_bytes=len(child_diff))
            return dict(state="ready", workspace_id=workspace_id, tree=child_tree,
                        source_tree=parent_tree, comparison=comparison,
                        parent_diff=parent_diff.decode(errors="replace"),
                        child_diff=child_diff.decode(errors="replace"))
        resolution = arguments.get("resolution", "auto")
        choices = arguments.get("path_choices", {})
        proposal = merge_proposal(source, admission, child_tree, resolution, choices)
        if proposal["state"] == "conflict":
            return dict(workspace_id=workspace_id, tree=child_tree, **proposal)
        patch = proposal["patch"]
        truncated = len(patch) > MAX_REVIEW_BYTES
        offset = arguments.get("review_offset", 0)
        if (not isinstance(offset, int) or isinstance(offset, bool) or offset < 0 or
                offset % MAX_REVIEW_BYTES != 0 or (offset and offset >= len(patch))):
            raise ValueError("invalid review page offset")
        page = patch[offset:offset + MAX_REVIEW_BYTES]
        next_offset = offset + len(page)
        receipt = dict(workspace_id=workspace_id, tree=child_tree,
                       source_tree=proposal["source_tree"],
                       resolution=resolution, path_choices=choices,
                       comparison=proposal["comparison"],
                       state="needs_full_review" if truncated else "ready",
                       diff=page.decode(errors="replace"),
                       diff_bytes=len(patch), diff_truncated=truncated,
                       diff_offset=offset, last_page=next_offset == len(patch),
                       next_offset=next_offset if next_offset < len(patch) else None)
        if truncated:
            # Every page is bound to the same exact source/result pair. Collect them
            # all before the reviewed merge can be committed.
            receipt["review_receipt"] = review_receipt(proposal, offset, page)
        if not truncated or next_offset == len(patch):
            receipt["merged_tree"] = proposal["merged_tree"]
        return receipt
    if action == "recover_merge":
        pending = admission.get("integration")
        if not pending or pending["state"] not in ("staging", "applying", "needs_inspection"):
            raise ValueError("no pending merge integration to recover")
        return recover_integration(source, manifest, admission)
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
    if not all(arguments.get(key) for key in ("expected_source_tree", "expected_merged_tree")):
        raise ValueError("a complete merge preview and its two tree versions are required")
    resolution = arguments.get("resolution", "auto")
    choices = arguments.get("path_choices", {})
    proposal = merge_proposal(source, admission, child_tree, resolution, choices)
    parent_tree = proposal["source_tree"]
    if arguments["expected_source_tree"] != parent_tree:
        raise ValueError("source changed since merge preview; inspect again")
    if proposal["state"] == "conflict":
        return dict(workspace_id=workspace_id, state="conflict", tree=child_tree,
                    source_tree=parent_tree, detail=proposal["detail"])
    merged_tree = proposal["merged_tree"]
    if arguments["expected_merged_tree"] != merged_tree:
        raise ValueError("integration result changed since merge preview; inspect again")
    if len(proposal["patch"]) > MAX_REVIEW_BYTES:
        patch_bytes = proposal["patch"]
        receipts = arguments.get("review_receipts")
        expected_receipts = [review_receipt(proposal, offset, patch_bytes[offset:offset + MAX_REVIEW_BYTES])
                             for offset in range(0, len(patch_bytes), MAX_REVIEW_BYTES)]
        if not isinstance(receipts, list) or receipts != expected_receipts:
            raise ValueError("full merge review requires every exact diff page receipt")
    decision = arguments.get("quality_decision")
    validate_quality_decision(decision, resolution, proposal["comparison"])
    patch = proposal["patch"]
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
                tree=child_tree, source_tree=actual, base_tree=base_tree,
                merged_tree=merged_tree, resolution=resolution,
                quality_decision=decision, comparison=proposal["comparison"])


if __name__ == "__main__":
    try:
        print(json.dumps(operate(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4],
                                 json.loads(sys.stdin.read())), ensure_ascii=False))
    except Exception as error:
        print(json.dumps(dict(error=str(error)), ensure_ascii=False))
        sys.exit(1)
