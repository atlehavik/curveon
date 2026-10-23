#!/usr/bin/env bash
# Laster opp website/ til curveon.no over FTPS (TLS kreves, sertifikatet verifiseres).
# Bruk: tools/deploy-website.sh [--apply]   (uten --apply: bare tørrkjøring)
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
set -a; . "$HOME/.curveon-signing/website.env"; set +a
flag="--dry-run"; [ "${1:-}" = "--apply" ] && flag=""
lftp -c "
set ftp:ssl-force true
set ftp:ssl-protect-data true
set cmd:fail-exit true
open -u '$FTP_USER','$FTP_PASS' '$FTP_HOST'
mirror -R --only-newer $flag '$here/website' '$FTP_DIR'
" 2>&1 | sed "s|$FTP_PASS|***|g; s|%40curveon.no:[^@]*@|%40curveon.no:***@|g"
