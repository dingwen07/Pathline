#!/usr/bin/env python3
"""Query an installed Pathline debug build through ADB run-as; prints JSON lines.

Example: query-debug-db.py -s SERIAL 'SELECT timestampMs,speed FROM location_samples WHERE timestampMs >= ? LIMIT 20' 1791175200000
Use -t TRANSPORT_ID instead to select an ADB transport.
"""

import argparse
import shlex
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb", help="adb executable path")
    device = parser.add_mutually_exclusive_group()
    device.add_argument("-s", "--serial", help="device serial from adb devices -l")
    device.add_argument("-t", "--transport-id", help="device transport ID from adb devices -l")
    parser.add_argument("sql", help="SELECT query; use ? for bound values")
    parser.add_argument("bind", nargs="*", help="positional bound values")
    args = parser.parse_args()
    adb = [args.adb]
    if args.serial:
        adb += ["-s", args.serial]
    elif args.transport_id:
        adb += ["-t", args.transport_id]
    package = "net.extrawdw.apps.locationhistory"
    paths = subprocess.check_output(adb + ["shell", "pm", "path", package], text=True).splitlines()
    apk = next((line.removeprefix("package:").strip() for line in paths if line.strip().endswith("/base.apk")), None)
    if apk is None:
        parser.error("Pathline is not installed on this device")
    command = [
        "run-as", package, "env", f"CLASSPATH={apk}", "app_process", "/system/bin",
        f"{package}.debug.DatabaseQuery", args.sql, *args.bind,
    ]
    # adb shell parses one remote shell command. Quote each argument there, including SQL/binds.
    return subprocess.call(adb + ["shell", shlex.join(command)])


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, subprocess.CalledProcessError) as error:
        print(f"ADB query failed: {error}", file=sys.stderr)
        sys.exit(1)
