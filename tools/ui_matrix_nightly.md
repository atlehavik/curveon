# Nightly screen matrix

You are the nightly run of Curveon's screen matrix (started by `ui_matrix_nightly.sh` at 03:30 while the
rider sleeps; nobody answers questions, so decide and note what you decided). During the day the matrix is not
run: sessions queue what should be checked, and you check it now, fix what is wrong and report.

Your checkout is the worktree `.claude/worktrees/nightly-ui` on the branch `nightly/<branch>`, which holds the
rider's branch as it is tonight (and last night's fixes if they are not merged yet). The rest of CLAUDE.md
applies, with these exceptions, which win:

- **Only the emulator.** Use only `$ANDROID_SERIAL` (a `Curveon_Phone` emulator). Never install on, tap on or
  talk to the X7 or the Pixel, even if `adb devices` lists them; ignore CLAUDE.md's install rules and
  device checks.
- **Only the nightly branch.** Commit on `nightly/<branch>` and push only that branch
  (`git push -u origin nightly/<branch>`). Never commit to, merge into or push the rider's branch, `main` or any
  other branch; no releases, no website, no tags. The rider merges in the morning.
- No questions and no plans to approve: work through the items, then write the report.

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
4. Fix what is wrong in the app (layouts, sizes, text that does not fit; follow CLAUDE.md: every language,
   `isNarrowScreen()`, `HideSystemBars()`, place names on top). A problem that is really the scenario's (a
   label that changed, a tap that misses) is fixed in the scenario. Run the build and the matrix again
   (`run2`, `run3`) until it passes or you have tried three times. `./gradlew test` must still pass.
5. Commit each fix with a message from the rider's point of view (as in `git log`), and a changelog point if the
   rider would notice it (CLAUDE.md's rules for the unreleased entry). Push `nightly/<branch>`.
6. Write `$NIGHT/<branch>.json` (one branch of `night.json`, the format is at the top of `$BIN/ui_matrix_report.py`):
   - `branch`, `base` (the commit you tested), `nightly_branch`, `build` ("ok" or what failed),
     `commits` (yours tonight and any unmerged ones from before: `git log --format='%h %s' <branch>..HEAD`),
     `sheets` (the newest contact sheet per scenario, absolute paths).
   - one entry in `items` per queue item: its `id`, `note`, `scenarios`; `status` `ok` (passed at once),
     `fixed` (failed, now passes), `open` (still wrong: say why), `skipped` (not run: say why);
     `summary`: one or two plain Norwegian sentences for the rider on what was wrong and what you did;
     `problems`: the matrix's messages from the first run; `shots`: for a failed one the screenshot before
     (label like "small360 liggende, før") and after the fix, absolute paths; nothing for one that passed.
7. Take every item you reported off the queue: `python3 -I $QUEUE_TOOL done <id> ...`.

Then stop; the script builds the page and publishes it.
