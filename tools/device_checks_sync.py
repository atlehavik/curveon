#!/usr/bin/env python3
"""
device-checks.md <-> the "Device checks" page (a private claude.ai artifact the rider ticks on the phone).

  export <md> <out.json>              the open checks as the page's list document
  apply  <md> <results dir>           what the rider marked, back into the file: a check that
                                      worked is removed (as CLAUDE.md asks once the rider reports
                                      back), one that failed stays with the rider's note under it

A check is known by a hash of its section heading and its text, so editing a check makes it a new
one on the page (and an old mark for it is then left alone).
"""
import hashlib
import json
import os
import re
import sys
from datetime import datetime

ITEM = re.compile(r"^- \[( |x)\] ")


def check_id(section, text):
    return hashlib.sha1((section + "\n" + text).encode()).hexdigest()[:12]


def parse(md):
    """Sections in file order, each with its prose and its checks (open and done) and the lines they span."""
    lines = md.split("\n")
    sections, cur, item = [], None, None
    for i, line in enumerate(lines):
        if line.startswith("## "):
            cur = {"title": line[3:].strip(), "prose": [], "items": []}
            sections.append(cur)
            item = None
            continue
        if cur is None:
            continue
        m = ITEM.match(line)
        if m:
            item = {"done": m.group(1) == "x", "lines": [line[len(m.group(0)):]], "start": i, "end": i}
            cur["items"].append(item)
        elif item is not None and line.startswith("  ") and line.strip():
            item["lines"].append(line[2:])
            item["end"] = i
        else:
            item = None
            if line.strip() and not cur["items"]:
                cur["prose"].append(line)
    for s in sections:
        for it in s["items"]:
            it["text"] = "\n".join(it["lines"])
            it["id"] = check_id(s["title"], it["text"])
    return lines, sections


def export(md_path, out_path):
    _, sections = parse(open(md_path, encoding="utf-8").read())
    doc = {"sections": [], "updated": datetime.now().isoformat(timespec="minutes")}
    for s in sections:
        items = [{"id": it["id"], "text": it["text"]} for it in s["items"] if not it["done"]]
        if items:
            doc["sections"].append({"title": s["title"], "prose": "\n".join(s["prose"]).strip(), "items": items})
    json.dump(doc, open(out_path, "w", encoding="utf-8"), ensure_ascii=False)
    n = sum(len(s["items"]) for s in doc["sections"])
    print(f"{n} open checks in {len(doc['sections'])} sections, {os.path.getsize(out_path) // 1024} KB")


def apply(md_path, results_dir):
    md = open(md_path, encoding="utf-8").read()
    lines, sections = parse(md)
    results = {}
    for name in os.listdir(results_dir):
        if name.endswith(".json"):
            r = json.load(open(os.path.join(results_dir, name), encoding="utf-8"))
            results[name[:-5]] = r.get("data", r)
    drop, notes, done, failed = set(), {}, [], []
    for s in sections:
        for it in s["items"]:
            r = results.get(it["id"])
            if not r or it["done"]:
                continue
            first = it["lines"][0][:90]
            if r.get("status") == "ok":
                drop.update(range(it["start"], it["end"] + 1))
                done.append(f"{s['title'][:40]}: {first}")
            elif r.get("status") == "fail":
                day = (r.get("at") or "")[:10]
                note = (r.get("note") or "").strip().replace("\n", " ")
                notes[it["end"]] = f"  **Feilet {day}:** {note or '(ingen merknad)'}"
                failed.append(f"{s['title'][:40]}: {first} -> {note}")
    out = []
    for i, line in enumerate(lines):
        if i in drop:
            continue
        out.append(line)
        if i in notes and notes[i] not in lines[i + 1:i + 2]:
            out.append(notes[i])
    # A section left with no checks and no prose goes too.
    text = "\n".join(out)
    text = re.sub(r"\n## [^\n]*\n(?=\n*(## |\Z))", "\n", text)
    open(md_path, "w", encoding="utf-8").write(text)
    print(f"{len(done)} worked (removed), {len(failed)} failed (kept with the note)")
    for f in failed:
        print("FAILED", f)


if __name__ == "__main__":
    {"export": export, "apply": apply}[sys.argv[1]](*sys.argv[2:])
