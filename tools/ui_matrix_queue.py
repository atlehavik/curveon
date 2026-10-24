#!/usr/bin/env python3
"""
The screen-matrix queue: what the nightly run (tools/ui_matrix_nightly.sh, 03:30) should check with
tools/ui_matrix.py. During the day the matrix is never run; a change that could look wrong on some kind of
screen gets a line here instead, and the night runs it, fixes what it finds and reports on the claude.ai page.

    python3 -I tools/ui_matrix_queue.py add --scenarios settings,crop "Settings tabs scroll; long Norwegian labels"
    python3 -I tools/ui_matrix_queue.py add --new "Ride summary dialog: GPX & rides -> a ride -> Summary; Close and Share must fit" "New ride summary"
    python3 -I tools/ui_matrix_queue.py list [--branch B] [--json]
    python3 -I tools/ui_matrix_queue.py branches          branches with something queued, one per line
    python3 -I tools/ui_matrix_queue.py done ID [ID ...] | --branch B   (the nightly run, once reported)
    python3 -I tools/ui_matrix_queue.py morning           what last night found, for the SessionStart hook

`add` takes the branch and commit from the git checkout it is run in. The queue and the nights' results live
outside git in ~/.curveon-nightly (CURVEON_NIGHTLY to move it), so every worktree adds to the same queue.
"""
import argparse
import fcntl
import json
import os
import subprocess
import sys
import time
from contextlib import contextmanager
from datetime import datetime

HOME = os.environ.get("CURVEON_NIGHTLY", os.path.expanduser("~/.curveon-nightly"))
QUEUE = os.path.join(HOME, "queue.json")


@contextmanager
def locked():
    """The queue, read and written under a lock: several sessions and the night may touch it at once."""
    os.makedirs(HOME, exist_ok=True)
    with open(QUEUE + ".lock", "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            items = json.load(open(QUEUE))
        except (FileNotFoundError, ValueError):
            items = []
        box = {"items": items}
        yield box
        tmp = QUEUE + ".tmp"
        with open(tmp, "w") as f:
            json.dump(box["items"], f, indent=1, ensure_ascii=False)
        os.replace(tmp, QUEUE)


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True).stdout.strip()


def add(a):
    branch = git("rev-parse", "--abbrev-ref", "HEAD")
    if not branch or branch == "HEAD":
        sys.exit("not on a branch: run add from the checkout the change is in")
    if branch.startswith("nightly/"):
        branch = branch[len("nightly/"):]
    item = {
        "id": f"{int(time.time() * 1000) % 10**10:x}",
        "added": datetime.now().isoformat(timespec="minutes"),
        "branch": branch,
        "commit": git("rev-parse", "--short", "HEAD"),
        "scenarios": [s for s in (a.scenarios or "").split(",") if s],
        "new_scenario": a.new or "",
        "note": a.note,
    }
    if not item["scenarios"] and not item["new_scenario"]:
        sys.exit("say which scenarios (--scenarios) or describe a new one (--new)")
    with locked() as q:
        q["items"].append(item)
    print(f"queued {item['id']} for tonight on {branch}: {a.note}")


def load():
    try:
        return json.load(open(QUEUE))
    except (FileNotFoundError, ValueError):
        return []


def list_(a):
    items = [i for i in load() if not a.branch or i["branch"] == a.branch]
    if a.json:
        print(json.dumps(items, indent=1, ensure_ascii=False))
        return
    for i in items:
        what = ",".join(i["scenarios"]) + (f" + new: {i.get('new_scenario')}" if i.get("new_scenario") else "")
        print(f"{i['id']}  {i['added']}  {i['branch']}@{i['commit']}  [{what}]  {i['note']}")
    if not items:
        print("the queue is empty")


def branches(_):
    seen = []
    for i in load():
        if i["branch"] not in seen:
            seen.append(i["branch"])
    print("\n".join(seen))


def done(a):
    with locked() as q:
        q["items"] = [i for i in q["items"] if i["id"] not in a.ids and i["branch"] != a.branch]


def morning(_):
    """A few lines for the start of a session: last night's open items and fixes waiting to be merged."""
    if os.environ.get("CURVEON_NIGHTLY_RUN") or "/worktrees/nightly-ui" in os.getcwd():
        return  # the night's own sessions
    lines = []
    latest = os.path.join(HOME, "nights", "latest", "night.json")
    try:
        night = json.load(open(latest))
        age_h = (time.time() - os.path.getmtime(latest)) / 3600
    except (FileNotFoundError, ValueError):
        night, age_h = None, 1e9
    if night and age_h < 20:
        items = [i for b in night.get("branches", []) for i in b.get("items", [])]
        count = {s: sum(1 for i in items if i.get("status") == s) for s in ("ok", "fixed", "open", "skipped")}
        lines.append(f"Screen matrix last night ({night.get('finished', '?')}): {count['ok']} passed, "
                     f"{count['fixed']} fixed, {count['open']} open, {count['skipped']} not run.")
        for i in items:
            if i.get("status") in ("open", "skipped"):
                lines.append(f"  open: {i.get('note', '')}: {i.get('summary', '')}")
    for b in git("for-each-ref", "--format=%(refname:short)", "refs/heads/nightly/").split():
        base = b[len("nightly/"):]
        ahead = git("log", "--oneline", f"{base}..{b}") if git("rev-parse", "--verify", "-q", base) else ""
        if ahead:
            lines.append(f"Nightly fixes waiting to be merged into {base} ({len(ahead.splitlines())} commit(s), "
                         f"branch {b}); ask the rider before merging:")
            lines += [f"  {line}" for line in ahead.splitlines()]
    page = open(os.path.join(HOME, "page-url")).read().strip() if os.path.exists(os.path.join(HOME, "page-url")) else ""
    if lines:
        if page:
            lines.append(f"Report with pictures: {page}")
        print("\n".join(lines))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("add")
    p.add_argument("--scenarios", help="comma-separated names from SCENARIOS in ui_matrix.py")
    p.add_argument("--new", help="a screen the matrix has no scenario for yet: how to get there and what must fit")
    p.add_argument("note", help="what changed and what to look for")
    p.set_defaults(fn=add)
    p = sub.add_parser("list")
    p.add_argument("--branch")
    p.add_argument("--json", action="store_true")
    p.set_defaults(fn=list_)
    sub.add_parser("branches").set_defaults(fn=branches)
    p = sub.add_parser("done")
    p.add_argument("ids", nargs="*")
    p.add_argument("--branch", help="every item queued for this branch")
    p.set_defaults(fn=done)
    sub.add_parser("morning").set_defaults(fn=morning)
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
