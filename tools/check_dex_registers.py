#!/usr/bin/env python3
"""
Checks a built APK for methods of the app's own code that use more than 255 registers.

The compiler (D8) has written wrong code for such a method: on 2026-11-05 DashboardScreen grew to 261 registers, an
instruction that can only name registers 0-255 was given v3 where it needed v259 (259 - 256), and Android 17 refused to
start the app ("VerifyError ... register v3 has type Reference ... but expected Integer"). Older Android may run the wrong
code instead. Split such a method (move a part of it into a function of its own) before a build goes out.

    python3 -I tools/check_dex_registers.py app/build/outputs/apk/release/app-release.apk

Exit code 1 and the methods listed when any goes over; 0 when all are within.
"""
import os, re, subprocess, sys, tempfile, zipfile

LIMIT = 255
PREFIX = 'Lcom/curveon/'
SDK = os.path.expanduser('~/Android/Sdk/build-tools')


def dexdump():
    versions = sorted(os.listdir(SDK))
    for v in reversed(versions):
        p = os.path.join(SDK, v, 'dexdump')
        if os.path.exists(p):
            return p
    sys.exit('no dexdump under ' + SDK)


def main(apk):
    tool = dexdump()
    over = []
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if not re.fullmatch(r'classes\d*\.dex', name):
                continue
            path = z.extract(name, tmp)
            out = subprocess.run([tool, '-d', path], capture_output=True).stdout.decode('utf-8', 'replace')
            cls = None
            method = None
            for line in out.split('\n'):
                m = re.match(r"\s*Class descriptor\s*: '(.*)'", line)
                if m:
                    cls = m.group(1)
                    continue
                m = re.match(r"\s*name\s*: '(.*)'", line)
                if m:
                    method = m.group(1)
                    continue
                m = re.match(r'\s*registers\s*: (\d+)', line)
                if m and cls and cls.startswith(PREFIX) and int(m.group(1)) > LIMIT:
                    over.append((int(m.group(1)), cls, method))
    for n, cls, method in sorted(over, reverse=True):
        print(f'{n} registers: {cls[1:-1].replace("/", ".")}.{method}')
    if over:
        print(f'{len(over)} method(s) over {LIMIT} registers: split them before this build goes out.')
        return 1
    print(f'ok: no method of the app uses more than {LIMIT} registers')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1]))
