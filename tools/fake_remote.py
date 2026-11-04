#!/usr/bin/env python3
"""
A pretend Bluetooth handlebar remote on an emulator, for trying Curveon's remote support with no remote:
Android sees it as an external keyboard, so unlike `adb shell input` it goes the way a real one does
(connecting, key repeats while held, keys only a remote may use).

  tools/fake_remote.py [-s serial] [--small] [--name NAME] STEP...
    STEP   UP DOWN LEFT RIGHT ENTER ESC F6 F7 F9 NEXT PREV PLAY VOLUP Q N C EQUAL MINUS BACK: a press
           hold:KEY:ms   held that long
           wait:ms       the remote stays connected
    --small  no Q key: Android doesn't count it as a whole keyboard

It connects when this starts (give Android three seconds) and disconnects when the steps are done.
"""
import argparse
import json
import os
import subprocess
import tempfile

KEYS = dict(UP=103, DOWN=108, LEFT=105, RIGHT=106, ENTER=28, ESC=1, F6=64, F7=65, F9=67, NEXT=163, PREV=165, PLAY=164,
            VOLUP=115, Q=16, N=49, C=46, EQUAL=13, MINUS=12, BACK=158)
ON_DEVICE = "/data/local/tmp/fake_remote.evemu"

parser = argparse.ArgumentParser()
parser.add_argument("-s", dest="serial", default="emulator-5554")
parser.add_argument("--small", action="store_true")
parser.add_argument("--name", default="Fake Remote")
parser.add_argument("steps", nargs="*")
args = parser.parse_args()

keys = [code for name, code in KEYS.items() if name != "Q" or not args.small]
script = [
    {"id": 1, "command": "register", "name": args.name, "vid": 0x1234, "pid": 0x5678, "bus": "bluetooth",
     "configuration": [{"type": 100, "data": [1]}, {"type": 101, "data": keys}]},
    {"id": 1, "command": "delay", "duration": 3000},
]


def key(code, value):
    script.append({"id": 1, "command": "inject", "events": [1, code, value, 0, 0, 0]})


def wait(ms):
    script.append({"id": 1, "command": "delay", "duration": ms})


for step in args.steps:
    parts = step.split(":")
    if parts[0] == "wait":
        wait(int(parts[1]))
    elif parts[0] == "hold":
        key(KEYS[parts[1]], 1); wait(int(parts[2])); key(KEYS[parts[1]], 0); wait(1500)
    else:
        key(KEYS[step], 1); wait(70); key(KEYS[step], 0); wait(1200)

with tempfile.TemporaryDirectory() as folder:
    local = os.path.join(folder, "fake_remote.evemu")
    with open(local, "w") as f:
        f.write("\n".join(json.dumps(line) for line in script) + "\n")
    subprocess.run(["adb", "-s", args.serial, "push", local, ON_DEVICE], check=True, stdout=subprocess.DEVNULL)
subprocess.run(["adb", "-s", args.serial, "shell", "uinput", ON_DEVICE], check=True)
