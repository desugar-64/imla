#!/usr/bin/env python3
import argparse
import subprocess
import sys


TIMEOUT_EXIT_CODE = 124


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Run adb with an explicit timeout and forward adb output unchanged."
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=20.0,
        help="Maximum seconds to wait for adb before returning 124. Default: 20.",
    )
    parser.add_argument(
        "--device",
        help="ADB serial passed as `adb -s <serial>`.",
    )
    parser.add_argument(
        "adb_args",
        nargs=argparse.REMAINDER,
        help="Arguments passed to adb. A leading `--` separator is ignored.",
    )
    args = parser.parse_args(argv)
    if args.adb_args[:1] == ["--"]:
        args.adb_args = args.adb_args[1:]
    if not args.adb_args:
        parser.error("missing adb arguments")
    if args.timeout <= 0:
        parser.error("--timeout must be greater than zero")
    return args


def main(argv):
    args = parse_args(argv)
    command = ["adb"]
    if args.device:
        command.extend(["-s", args.device])
    command.extend(args.adb_args)

    try:
        result = subprocess.run(command, timeout=args.timeout)
    except subprocess.TimeoutExpired:
        sys.stderr.write(
            f"adb command timed out after {args.timeout:g}s: {' '.join(command)}\n"
        )
        return TIMEOUT_EXIT_CODE
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
