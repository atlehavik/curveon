#!/usr/bin/env bash
# The nightly screen matrix: at 03:30 (systemd user timer) it takes what sessions queued during the day
# (tools/ui_matrix_queue.py), runs tools/ui_matrix.py on the Curveon_Phone emulator for each queued branch,
# lets a headless Claude session fix what it finds on the branch nightly/<branch>, and publishes the report
# page (a private claude.ai artifact, its address in ~/.curveon-nightly/page-url).
#
#   tools/ui_matrix_nightly.sh install   copy this and its helpers to ~/.curveon-nightly/bin and turn the timer on
#                                        (run it again after changing any of them)
#   ui_matrix_nightly.sh check           can the night run: emulator, claude login, the page
#   ui_matrix_nightly.sh run             what the timer starts; nothing happens when the queue is empty
#   ui_matrix_nightly.sh publish         build and publish the report page again from what is in ~/.curveon-nightly
#
# Everything lives in ~/.curveon-nightly: queue.json, nights/<date>/ (results, screenshots), page/, logs/.
# Only the emulator is touched: never the X7 or the Pixel, and only nightly/* branches are committed to.
set -uo pipefail

N=${CURVEON_NIGHTLY:-$HOME/.curveon-nightly}
REPO=$HOME/develop/Curveon
SDK=$HOME/Android/Sdk
WT=$REPO/.claude/worktrees/nightly-ui
AVD=Curveon_Phone
HERE=$(cd "$(dirname "$0")" && pwd)
export PATH=$SDK/platform-tools:$SDK/emulator:$HOME/.local/bin:$PATH
export CURVEON_NIGHTLY_RUN=1
mkdir -p "$N/logs" "$N/nights"
LOG=$N/logs/$(date +%F).log

log() { echo "$(date '+%F %T') $*" | tee -a "$LOG"; }

find_emu() {
    for s in $(adb devices | awk '/^emulator-[0-9]+\tdevice/{print $1}'); do
        if [ "$(adb -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r')" = "$AVD" ]; then echo "$s"; return; fi
    done
}

reset_display() {
    adb -s "$SERIAL" shell 'wm size reset; wm density reset; settings put system font_scale 1.0;
        settings put system user_rotation 0; for c in hole corner tall double waterfall; do
        cmd overlay disable com.android.internal.display.cutout.emulation.$c; done;
        cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural' >/dev/null 2>&1
}

start_emu() {
    SERIAL=$(find_emu)
    STARTED=0
    if [ -z "$SERIAL" ]; then
        log "starting $AVD without a window"
        setsid emulator -avd "$AVD" -no-window -no-audio -no-boot-anim >>"$N/logs/emulator.log" 2>&1 < /dev/null &
        STARTED=1
        for _ in $(seq 120); do
            sleep 5
            SERIAL=$(find_emu)
            [ -n "$SERIAL" ] && [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
        done
    fi
    [ -n "$SERIAL" ] || { log "the emulator did not start"; return 1; }
    sleep 10
    reset_display
    adb -s "$SERIAL" shell settings put global sys_storage_threshold_percentage 1
    log "emulator $SERIAL"
}

# The worktree on nightly/<branch>: the rider's branch as it is now, plus fixes from earlier nights not yet
# merged. If those no longer merge, they are kept as nightly/<branch>-<date> and the night starts afresh.
prepare() {
    local b=$1 nb=nightly/$1
    git -C "$REPO" rev-parse --verify -q "refs/heads/$b" >/dev/null || { log "no branch $b"; return 1; }
    [ -d "$WT" ] || git -C "$REPO" worktree add -q --detach "$WT" "$b" || return 1
    git -C "$WT" reset -q --hard && git -C "$WT" clean -qfd
    if git -C "$REPO" rev-parse --verify -q "refs/heads/$nb" >/dev/null && ! git -C "$REPO" merge-base --is-ancestor "$nb" "$b"; then
        git -C "$WT" checkout -q "$nb" || return 1
        if ! git -C "$WT" merge -q --no-edit "$b"; then
            git -C "$WT" merge --abort
            git -C "$REPO" branch -m "$nb" "$nb-$(date +%F)"
            log "earlier fixes on $nb no longer merge: kept as $nb-$(date +%F)"
            git -C "$WT" checkout -q -B "$nb" "$b"
        fi
    else
        git -C "$WT" checkout -q -B "$nb" "$b" || return 1
    fi
    [ -f "$WT/local.properties" ] || cp "$REPO/local.properties" "$WT/" 2>/dev/null
    return 0
}

# A branch's part of the report when the session could not do it: every item skipped, with the reason.
skipped() {
    python3 -I - "$1" "$2" "$3" "$N/queue.json" <<'EOF2'
import json, sys
branch, reason, out, queue = sys.argv[1:]
items = [i for i in json.load(open(queue)) if i["branch"] == branch]
json.dump({"branch": branch, "base": "", "nightly_branch": f"nightly/{branch}", "build": "", "commits": [], "sheets": [],
           "items": [{"id": i["id"], "note": i["note"], "scenarios": i["scenarios"], "status": "skipped",
                      "summary": reason} for i in items]}, open(out, "w"), indent=1, ensure_ascii=False)
EOF2
}

# A Claude background session (the only kind that has the Artifact tool), waited for until it is done.
#   session NAME DIR TIMEOUT_MIN ALLOWED_TOOLS PROMPT
session() {
    local name=$1 dir=$2 mins=$3 tools=$4 prompt=$5 id state
    id=$(cd "$dir" && env -u CLAUDECODE -u CLAUDE_CODE_CHILD_SESSION -u CLAUDE_CODE_SESSION_ID -u CLAUDE_JOB_DIR \
        -u CLAUDE_CODE_MESSAGING_SOCKET -u CLAUDE_PID \
        claude --bg "$prompt" -n "$name" --permission-mode auto --add-dir "$N" --allowedTools "$tools" < /dev/null 2>>"$LOG" \
        | sed -n 's/^backgrounded · \([0-9a-f]*\) .*/\1/p' | head -1)
    [ -n "$id" ] || { log "$name: the session did not start"; return 1; }
    log "$name: session $id in $dir"
    for _ in $(seq $((mins * 4))); do
        sleep 15
        state=$(claude agents --json --all 2>/dev/null | python3 -I -c "import json,sys
print(next((a.get('state', '') for a in json.load(sys.stdin) if a.get('id') == '$id'), 'gone'))")
        [ "$state" = done ] || [ "$state" = gone ] && break
    done
    [ "$state" = done ] || log "$name: stopped after $mins min (state $state)"
    claude stop "$id" >/dev/null 2>&1
    log "$name: ended"
}

publish() {
    local url
    url=$(cat "$N/page-url" 2>/dev/null) || { log "no page-url: the page is not published"; return 1; }
    python3 -I "$HERE/ui_matrix_report.py" "$N/page" | tee -a "$LOG"
    # The session must start in a folder Claude trusts (under ~/develop/Curveon), and publishes only files under it.
    mkdir -p "$WT/.nightly-page"
    grep -qx '/.nightly-page/' "$(git -C "$REPO" rev-parse --path-format=absolute --git-common-dir)/info/exclude" 2>/dev/null \
        || echo '/.nightly-page/' >> "$(git -C "$REPO" rev-parse --path-format=absolute --git-common-dir)/info/exclude"
    rsync -a --delete "$N/page/" "$WT/.nightly-page/"
    session nightly-publish "$WT/.nightly-page" 20 "Artifact Read" "Publish the screen-matrix report page with the Artifact tool, and do nothing else.
1. Artifact action list, scope files, url $url (so every published file is known).
2. Artifact action read, url $url.
3. Artifact publish: url $url, file_path index.html, files = exactly the JSON object in files.json in this
   directory (published path -> source path relative to this directory; null removes that file).
Then stop."
}

cmd_run() {
    exec 9>"$N/nightly.lock"
    flock -n 9 || { log "already running"; exit 0; }
    local branches night started
    branches=$(python3 -I "$HERE/ui_matrix_queue.py" branches)
    [ -n "$branches" ] || { log "the queue is empty"; exit 0; }
    night=$N/nights/$(date +%F)
    mkdir -p "$night"
    started=$(date '+%F %H:%M')
    git -C "$REPO" fetch -q origin 2>>"$LOG"
    start_emu || exit 1
    for b in $branches; do
        rm -f "$night/$b.json"
        if ! prepare "$b"; then
            skipped "$b" "Grenen $b finnes ikke lenger eller kunne ikke sjekkes ut, så den ble ikke testet." "$night/$b.json"
            python3 -I "$HERE/ui_matrix_queue.py" done --branch "$b"
            continue
        fi
        log "branch $b at $(git -C "$WT" rev-parse --short HEAD)"
        local prompt
        prompt="$(sed -e "s|\\\$ANDROID_SERIAL|$SERIAL|g" -e "s|\\\$NIGHT|$night|g" -e "s|\\\$QUEUE_TOOL|$HERE/ui_matrix_queue.py|g" -e "s|\\\$BIN|$HERE|g" "$HERE/ui_matrix_nightly.md")

## Tonight

The emulator is $SERIAL, tonight's folder $night. Branch \`$b\` (tested at $(git -C "$WT" rev-parse --short HEAD)), nightly branch \`nightly/$b\`.
Queue items for this branch:

$(python3 -I "$HERE/ui_matrix_queue.py" list --branch "$b" --json)"
        session "nightly-$b" "$WT" 180 \
            "Read Edit Write Glob Grep Bash(./gradlew *) Bash(adb -s $SERIAL *) Bash(python3 -I tools/ui_matrix.py *) Bash(python3 -I $HERE/ui_matrix_queue.py *)" \
            "$prompt"
        reset_display
        # Items stay queued for the next night when the session did not get to report them.
        [ -s "$night/$b.json" ] || skipped "$b" "Nattøkta ble ikke ferdig (se loggen $LOG); punktene prøves igjen neste natt." "$night/$b.json"
    done
    python3 -I - "$night" "$started" <<'EOF'
import glob, json, os, sys
from datetime import datetime
night, started = sys.argv[1:]
branches = []
for p in sorted(glob.glob(os.path.join(night, "*.json"))):
    if not p.endswith("night.json"):
        try:
            branches.append(json.load(open(p)))
        except ValueError:
            pass
json.dump({"started": started, "finished": datetime.now().strftime("%Y-%m-%d %H:%M"), "branches": branches},
          open(os.path.join(night, "night.json"), "w"), indent=1, ensure_ascii=False)
EOF
    ln -sfn "$night" "$N/nights/latest"
    if [ "$STARTED" = 1 ]; then adb -s "$SERIAL" emu kill >/dev/null 2>&1; fi
    publish
    log "done"
}

cmd_check() {
    local ok=1
    emulator -list-avds | grep -qx "$AVD" && echo "ok: AVD $AVD" || { echo "missing: AVD $AVD"; ok=0; }
    [ -f "$N/page-url" ] && echo "ok: page $(cat "$N/page-url")" || { echo "missing: $N/page-url"; ok=0; }
    systemctl --user is-enabled curveon-ui-nightly.timer >/dev/null 2>&1 && echo "ok: timer on ($(systemctl --user list-timers curveon-ui-nightly.timer --no-legend | awk '{print $1, $2, $3}'))" || { echo "missing: timer (run install)"; ok=0; }
    [ "$(loginctl show-user "$USER" -p Linger --value)" = yes ] && echo "ok: runs while logged out" || echo "note: runs only while you are logged in (loginctl enable-linger $USER)"
    if [ -f "$N/page-url" ]; then
        LOG=$N/logs/check.log
        : > "$LOG"
        session nightly-check "$REPO" 5 "Artifact" "Use the Artifact tool, action read, url $(cat "$N/page-url"). Then stop. Do not change anything."
        grep -q "ended" "$LOG" && ! grep -q "did not start\|stopped after" "$LOG" \
            && echo "ok: a background Claude session can run (see claude agents)" || { echo "problem: see $LOG"; ok=0; }
    fi
    [ $ok = 1 ]
}

cmd_install() {
    mkdir -p "$N/bin" "$HOME/.config/systemd/user"
    cp "$HERE"/ui_matrix_nightly.sh "$HERE"/ui_matrix_nightly.md "$HERE"/ui_matrix_queue.py "$HERE"/ui_matrix_report.py "$N/bin/"
    chmod +x "$N/bin/ui_matrix_nightly.sh"
    cp "$HERE"/systemd/curveon-ui-nightly.service "$HERE"/systemd/curveon-ui-nightly.timer "$HOME/.config/systemd/user/"
    systemctl --user daemon-reload
    systemctl --user enable --now curveon-ui-nightly.timer
    systemctl --user list-timers curveon-ui-nightly.timer --no-legend
}

case "${1:-}" in
    run) cmd_run ;;
    publish) [ -d "$WT" ] || git -C "$REPO" worktree add -q --detach "$WT" main; publish ;;
    check) cmd_check ;;
    install) cmd_install ;;
    *) sed -n '2,15p' "$0"; exit 2 ;;
esac
