#!/usr/bin/env python3
"""
Builds the screen-matrix report page (a private claude.ai artifact the rider reads on the phone) from the
nights' results in ~/.curveon-nightly/nights/<date>/night.json:

    python3 -I tools/ui_matrix_report.py [OUT_DIR]     default ~/.curveon-nightly/page

Writes OUT_DIR/index.html, the pictures under OUT_DIR/img/ (as JPEG, small enough for a phone) and
OUT_DIR/files.json, the map of published path -> file for the Artifact publish (paths relative to OUT_DIR).
The newest night is shown in full with its pictures, the 14 before it as one line each.

night.json, written by the nightly session (see ui_matrix_nightly.md):
  {"started", "finished", "notes",
   "branches": [{"branch", "base", "nightly_branch", "build", "commits": [{"sha", "subject"}],
                 "sheets": [{"scenario", "path"}],
                 "items": [{"id", "note", "scenarios": [], "status": "ok|fixed|open|skipped", "summary",
                            "problems": [], "shots": [{"label", "path"}]}]}]}
"""
import hashlib
import html
import json
import os
import sys

HOME = os.environ.get("CURVEON_NIGHTLY", os.path.expanduser("~/.curveon-nightly"))
STATUS = {"ok": ("Besto", "ok"), "fixed": ("Rettet", "fixed"), "open": ("Åpen", "open"),
          "skipped": ("Ikke kjørt", "skipped")}

CSS = """
<title>Skjermmatrise</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Barlow+Condensed:wght@500;700&family=Barlow:wght@400;600&family=JetBrains+Mono:wght@400&display=swap">
<style>
/* Instrument panel: one column of nights, each item a row with a status lamp; pictures in a strip that wraps. */
:root {
  --bg: #eef1f3; --panel: #ffffff; --fg: #17222b; --muted: #5b6b77; --line: #d3dbe1;
  --accent: #0a8fa6; --ok: #2f8a4c; --fixed: #0a7f95; --open: #c0392b; --skipped: #8a6d1f;
  --display: "Barlow Condensed", "Arial Narrow", sans-serif;
  --body: "Barlow", system-ui, sans-serif;
  --mono: "JetBrains Mono", ui-monospace, monospace;
}
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) {
  --bg: #0f1519; --panel: #172026; --fg: #e3eaee; --muted: #8fa0ab; --line: #2a3740;
  --accent: #3fd0e6; --ok: #5cc27c; --fixed: #3fd0e6; --open: #ff6f61; --skipped: #e0b850; color-scheme: dark } }
:root[data-theme="dark"] {
  --bg: #0f1519; --panel: #172026; --fg: #e3eaee; --muted: #8fa0ab; --line: #2a3740;
  --accent: #3fd0e6; --ok: #5cc27c; --fixed: #3fd0e6; --open: #ff6f61; --skipped: #e0b850; color-scheme: dark }
body { background: var(--bg); color: var(--fg); font: 16px/1.5 var(--body);
  padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px));
  padding-block: 20px 48px; }
main { max-width: 860px; margin: 0 auto; display: grid; gap: 28px; }
h1, h2, h3 { font-family: var(--display); font-weight: 700; text-wrap: balance; margin: 0; letter-spacing: .01em; }
h1 { font-size: 2.1rem; }
h2 { font-size: 1.45rem; }
h3 { font-size: 1.1rem; font-weight: 500; }
.eyebrow { font: 500 .8rem var(--display); text-transform: uppercase; letter-spacing: .12em; color: var(--muted); }
header { display: grid; gap: 6px; }
.tally { display: flex; flex-wrap: wrap; gap: 8px 18px; font-variant-numeric: tabular-nums; }
.tally span b { font-family: var(--display); font-size: 1.3rem; margin-right: 4px; }
section.branch { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 18px; display: grid; gap: 16px; min-width: 0; }
.mono { font-family: var(--mono); font-size: .82rem; overflow-wrap: anywhere; }
.muted { color: var(--muted); }
.merge { border-left: 3px solid var(--accent); padding: 8px 12px; background: color-mix(in srgb, var(--accent) 8%, transparent); }
.merge ul { margin: 6px 0 0; padding-left: 18px; }
.item { display: grid; grid-template-columns: auto 1fr; gap: 4px 12px; border-top: 1px solid var(--line); padding-top: 14px; min-width: 0; }
.item > div { min-width: 0; display: grid; gap: 6px; }
.lamp { width: 12px; height: 12px; border-radius: 50%; margin-top: 7px; background: var(--c); box-shadow: 0 0 0 3px color-mix(in srgb, var(--c) 22%, transparent); }
.pill { display: inline-block; font: 600 .75rem var(--display); text-transform: uppercase; letter-spacing: .1em; color: var(--c); margin-right: 8px; }
.ok { --c: var(--ok); } .fixed { --c: var(--fixed); } .open { --c: var(--open); } .skipped { --c: var(--skipped); }
.problems { margin: 0; padding-left: 18px; font-size: .9rem; }
.shots { display: flex; flex-wrap: wrap; gap: 10px; }
.shots figure { margin: 0; width: 150px; max-width: 45%; }
.shots img { display: block; border: 1px solid var(--line); border-radius: 6px; max-height: 300px; object-fit: contain; background: #000; }
.shots figcaption { font: .75rem var(--mono); color: var(--muted); overflow-wrap: anywhere; }
details summary { cursor: pointer; font-family: var(--display); font-weight: 500; }
details img { margin-top: 8px; border-radius: 6px; }
a { color: var(--accent); }
a:focus-visible, summary:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.history { display: grid; gap: 4px; font-variant-numeric: tabular-nums; }
.queue li { margin-bottom: 4px; }
</style>
"""


def esc(s):
    return html.escape(str(s or ""))


class Images:
    """Pictures copied into the page as small JPEGs, named by a hash of where they came from."""

    def __init__(self, out):
        self.out, self.files = out, {}

    def add(self, path, max_side):
        if not path or not os.path.exists(path):
            return None
        from PIL import Image
        name = "img/" + hashlib.sha1(os.path.abspath(path).encode()).hexdigest()[:12] + ".jpg"
        dest = os.path.join(self.out, name)
        if not os.path.exists(dest):
            im = Image.open(path).convert("RGB")
            im.thumbnail((max_side, max_side))
            im.save(dest, "JPEG", quality=80)
        self.files[name] = name
        return name


def tally(night):
    items = [i for b in night.get("branches", []) for i in b.get("items", [])]
    return {k: sum(1 for i in items if i.get("status") == k) for k in STATUS}


def tally_html(t):
    return "<div class=\"tally\">" + "".join(
        f"<span class=\"{cls}\"><b style=\"color:var(--c)\">{t[k]}</b>{label.lower()}</span>"
        for k, (label, cls) in STATUS.items()) + "</div>"


def branch_html(b, imgs):
    out = [f"<section class=\"branch\"><div><div class=\"eyebrow\">Gren</div><h2>{esc(b.get('branch'))}</h2>"
           f"<div class=\"mono muted\">testet på {esc(b.get('base'))}"
           + (f" · bygg: {esc(b.get('build'))}" if b.get("build") and b.get("build") != "ok" else "") + "</div></div>"]
    commits = b.get("commits") or []
    if commits:
        out.append(f"<div class=\"merge\"><b>Rettinger på <span class=\"mono\">{esc(b.get('nightly_branch'))}</span></b>"
                   f" venter på deg. Si «flett inn nattrettingene» i neste økt, så tar jeg dem inn i "
                   f"<span class=\"mono\">{esc(b.get('branch'))}</span>.<ul>"
                   + "".join(f"<li><span class=\"mono\">{esc(c.get('sha'))}</span> {esc(c.get('subject'))}</li>" for c in commits)
                   + "</ul></div>")
    for i in b.get("items", []):
        label, cls = STATUS.get(i.get("status"), ("?", "skipped"))
        shots = []
        for s in i.get("shots", []):
            name = imgs.add(s.get("path"), 1200)
            if name:
                shots.append(f"<figure><a href=\"{name}\" target=\"_blank\"><img src=\"{name}\" alt=\"{esc(s.get('label'))}\" loading=\"lazy\"></a>"
                             f"<figcaption>{esc(s.get('label'))}</figcaption></figure>")
        problems = "".join(f"<li class=\"mono\">{esc(p)}</li>" for p in i.get("problems", []))
        out.append(f"<div class=\"item {cls}\"><span class=\"lamp\"></span><div>"
                   f"<h3><span class=\"pill\">{label}</span>{esc(i.get('note'))}</h3>"
                   f"<div class=\"mono muted\">{esc(', '.join(i.get('scenarios', [])))}</div>"
                   + (f"<div>{esc(i.get('summary'))}</div>" if i.get("summary") else "")
                   + (f"<ul class=\"problems\">{problems}</ul>" if problems else "")
                   + (f"<div class=\"shots\">{''.join(shots)}</div>" if shots else "")
                   + "</div></div>")
    for sh in b.get("sheets", []):
        name = imgs.add(sh.get("path"), 1440)
        if name:
            out.append(f"<details><summary>Kontaktark: {esc(sh.get('scenario'))}</summary>"
                       f"<a href=\"{name}\" target=\"_blank\"><img src=\"{name}\" alt=\"Kontaktark {esc(sh.get('scenario'))}\" loading=\"lazy\"></a></details>")
    out.append("</section>")
    return "".join(out)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HOME, "page")
    os.makedirs(os.path.join(out, "img"), exist_ok=True)
    nights_dir = os.path.join(HOME, "nights")
    nights = []
    for d in sorted(os.listdir(nights_dir) if os.path.isdir(nights_dir) else [], reverse=True):
        p = os.path.join(nights_dir, d, "night.json")
        if d != "latest" and os.path.exists(p):
            try:
                nights.append((d, json.load(open(p))))
            except ValueError:
                pass
    try:
        queue = json.load(open(os.path.join(HOME, "queue.json")))
    except (FileNotFoundError, ValueError):
        queue = []
    imgs = Images(out)
    body = ["<main><header><div class=\"eyebrow\">Curveon · nattkjøring kl. 03:30</div><h1>Skjermmatrise</h1>"]
    if nights:
        day, night = nights[0]
        body.append(f"<div class=\"muted\">Siste natt: {esc(night.get('started', day))} til {esc(night.get('finished', '?'))}</div>"
                    + tally_html(tally(night)) + "</header>")
        if night.get("notes"):
            body.append(f"<p>{esc(night['notes'])}</p>")
        body += [branch_html(b, imgs) for b in night.get("branches", [])]
    else:
        body.append("<div class=\"muted\">Ingen natt er kjørt ennå. Første kjøring blir kl. 03:30 natten etter at noe er lagt i køen.</div></header>")
    if queue:
        body.append("<section><h2>Venter til neste natt</h2><ul class=\"queue\">"
                    + "".join(f"<li>{esc(q.get('note'))} <span class=\"mono muted\">{esc(q.get('branch'))} · "
                              f"{esc(', '.join(q.get('scenarios', [])) or 'nytt scenario')}</span></li>" for q in queue)
                    + "</ul></section>")
    if len(nights) > 1:
        rows = []
        for day, n in nights[1:15]:
            t = tally(n)
            rows.append(f"<div><span class=\"mono\">{esc(day)}</span> · {t['ok']} besto, {t['fixed']} rettet, "
                        f"{t['open']} åpne, {t['skipped']} ikke kjørt</div>")
        body.append("<section><h2>Tidligere netter</h2><div class=\"history\">" + "".join(rows) + "</div></section>")
    body.append("</main>")
    with open(os.path.join(out, "index.html"), "w") as f:
        f.write(CSS + "".join(body))
    # Pictures from older builds of the page that this one no longer shows go.
    for name in os.listdir(os.path.join(out, "img")):
        if f"img/{name}" not in imgs.files:
            os.remove(os.path.join(out, "img", name))
    # The artifact keeps a file the publish leaves out, so last night's pictures are named with null to go.
    files = dict(imgs.files)
    try:
        files.update({k: None for k, v in json.load(open(os.path.join(out, "files.json"))).items()
                      if v is not None and k not in files})
    except (FileNotFoundError, ValueError):
        pass
    with open(os.path.join(out, "files.json"), "w") as f:
        json.dump(files, f, indent=1)
    print(f"{out}/index.html with {len(imgs.files)} picture(s)")


if __name__ == "__main__":
    main()
