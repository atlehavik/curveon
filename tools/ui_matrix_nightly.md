# Nightly screen matrix

You are the nightly run of Curveon's screen matrix (started by `ui_matrix_nightly.sh` at 03:30 while the
rider sleeps; nobody answers questions, so decide and note what you decided). During the day the matrix is not
run: sessions queue what should be checked, and you check it now and report. You never change the app.

Your checkout is the worktree `.claude/worktrees/nightly-ui` on the branch `nightly/<branch>`, which holds the
rider's branch as it is tonight (and last night's fixes if they are not merged yet). The rest of CLAUDE.md
applies, with these exceptions, which win:

- **Only the emulator.** Use only `$ANDROID_SERIAL` (a `Curveon_Phone` emulator). Never install on, tap on or
  talk to the X7 or the Pixel, even if `adb devices` lists them; ignore CLAUDE.md's install rules and
  device checks.
- **You never change the app.** Not a line, not a text, not a margin, however small or obvious the fix: what is wrong
  goes in the report, with pictures, and the rider decides. The only files you may change are `tools/ui_matrix*`. The
  script throws away anything else you change, and says so on the report page.
- **Only the nightly branch.** Commit only changes to `tools/ui_matrix*`, on `nightly/<branch>`. Never push, and never
  commit to or merge into the rider's branch, `main` or any other branch; no releases, no website, no tags.
- No questions and no plans to approve: work through the items, then write the report.
- **A check never asks for a look.** A scenario may check that things are on screen or can be reached. It may not ask
  for all of something to show at once, or anything else that only another layout could meet. (2026-11-04: a check
  added here said every Settings tab must show at once, and the fix for it turned the menu into a grid of icons. The
  rider had to find it, and it had gone out in a release. That is why this run no longer changes the app.)

## What to do

1. Build a debug APK (`./gradlew :app:assembleDebug`; read the whole output) and install it on the emulator
   (`adb install -r`). If the build fails on the branch as it is, do not fix the rider's unrelated code: mark
   every item `skipped` with the reason and go to step 6.
2. Run the matrix on the scenarios the items ask for, all variants:
   `python3 -I tools/ui_matrix.py -s $ANDROID_SERIAL --scenarios <a,b> --out $NIGHT/<branch>/run1 --json $NIGHT/<branch>/run1.json`
   (if this branch's `ui_matrix.py` has no `--json`, read its printed output instead). An item with
   `new_scenario` needs a scenario first: add it to `SCENARIOS` in `tools/ui_matrix.py` (labels in English and
   Norwegian, like the others), and commit it with the fixes.
3. Look over the contact sheets (`sheet_<scenario>.png`) yourself too, not only the automatic checks: overlap,
   text cut off or running out of a card, buttons off screen. The item's note says what to look for.
4. Do not fix the app. A problem that is really the scenario's (a label that changed, a tap that misses) is fixed in the
   scenario, and the matrix run again (`run2`, `run3`; three tries at most).
5. Commit a change to a scenario with a message saying what it checks now. No changelog point: nothing changes for riders.
6. Write `$NIGHT/<branch>.json` (one branch of `night.json`, the format is at the top of `$BIN/ui_matrix_report.py`):
   - `branch`, `base` (the commit you tested), `nightly_branch`, `build` ("ok" or what failed),
     `commits` (your changes to the checks tonight: `git log --format='%h %s' <branch>..HEAD`),
     `sheets` (the newest contact sheet per scenario, absolute paths).
   - one entry in `items` per queue item: its `id`, `note`, `scenarios`; `status` `ok` (passed at once),
     `open` (wrong in the app: say what, on which screens, and what could be done, without having done it),
     `skipped` (not run: say why);
     `summary`: one or two plain Norwegian sentences for the rider on what is wrong;
     `problems`: the matrix's messages; `shots`: for a failed one the screenshots that show it (label like
     "small360 liggende"), absolute paths; nothing for one that passed.
7. Take every item you reported off the queue: `python3 -I $QUEUE_TOOL done <id> ...`.

Then stop; the script builds the page and publishes it.
