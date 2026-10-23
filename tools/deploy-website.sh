#!/usr/bin/env bash
# Laster opp website/ til curveon.no over FTPS (TLS kreves, sertifikatet verifiseres).
# Bruk: tools/deploy-website.sh [--apply]   (uten --apply: bare tørrkjøring)
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
# Leses linje for linje, ikke som shell-kode, så passordet kan ha hvilke tegn som helst.
while IFS='=' read -r k v; do
  case "$k" in FTP_HOST|FTP_USER|FTP_PASS|FTP_DIR) printf -v "$k" '%s' "$v";; esac
done < "$HOME/.curveon-signing/website.env"
flag="--dry-run"; [ "${1:-}" = "--apply" ] && flag=""
lftp -c "
set ftp:ssl-force true
set ftp:ssl-protect-data true
set cmd:fail-exit true
open -u '$FTP_USER','$FTP_PASS' '$FTP_HOST'
mirror -R --only-newer $flag '$here/website' '$FTP_DIR'
" 2>&1 | sed -E 's|(://[^:/]*:)[^@]*@|\1***@|g'
