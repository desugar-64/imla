#!/usr/bin/env python3
import argparse
from datetime import datetime
from pathlib import Path
import re
import subprocess
import sys
import time


APP_ID = "dev.serhiiyaremych.imla"
APA_APP = "Android Performance Analyzer"
ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "diagnostics" / "apa" / "imla-smoke.perfetto.cfg"
SQL_DIR = ROOT / "diagnostics" / "apa" / "sql"
TRACE_PROCESSOR = ROOT / "trace_processor"
TRACE_DIR = ROOT / "diagnostics" / "apa" / "traces"
SCREENSHOT_DIR = ROOT / "diagnostics" / "apa" / "screenshots"


def run(command, *, check=True, capture_output=False):
    result = subprocess.run(
        command,
        cwd=ROOT,
        text=True,
        check=False,
        capture_output=capture_output,
    )
    if check and result.returncode != 0:
        stderr = result.stderr.strip() if result.stderr else ""
        stdout = result.stdout.strip() if result.stdout else ""
        detail = stderr or stdout or f"exit code {result.returncode}"
        raise SystemExit(f"Command failed: {' '.join(command)}\n{detail}")
    return result


def adb(device, *args, check=True, capture_output=False):
    command = ["adb"]
    if device:
        command.extend(["-s", device])
    command.extend(args)
    return run(command, check=check, capture_output=capture_output)


def adb_bytes(device, *args):
    command = ["adb"]
    if device:
        command.extend(["-s", device])
    command.extend(args)
    result = subprocess.run(command, cwd=ROOT, check=False, capture_output=True)
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        raise SystemExit(f"Command failed: {' '.join(command)}\n{detail}")
    return result.stdout


def default_display_id(device):
    result = adb(device, "shell", "dumpsys", "SurfaceFlinger", "--display-id", capture_output=True)
    match = re.search(r"Display\s+(\d+)\s+\(HWC display 0\)", result.stdout)
    if match:
        return match.group(1)

    match = re.search(r"Display\s+(\d+)", result.stdout)
    return match.group(1) if match else None


def capture_screenshot_png(device):
    display_id = default_display_id(device)
    if display_id:
        bytes_output = adb_bytes(device, "exec-out", "screencap", "-p", "-d", display_id)
    else:
        bytes_output = adb_bytes(device, "exec-out", "screencap", "-p")

    png_signature = b"\x89PNG\r\n\x1a\n"
    png_start = bytes_output.find(png_signature)
    if png_start < 0:
        raise SystemExit("screencap did not return PNG data")
    return bytes_output[png_start:]


def timestamp():
    return datetime.now().strftime("%Y-%m-%d-%H%M%S")


def require_trace_processor():
    if TRACE_PROCESSOR.exists():
        return
    raise SystemExit(
        "trace_processor is missing. Restore it with:\n"
        "curl -L -o trace_processor https://get.perfetto.dev/trace_processor && chmod +x trace_processor"
    )


def analyze_trace(trace_path):
    require_trace_processor()
    trace = Path(trace_path)
    if not trace.is_absolute():
        trace = ROOT / trace
    if not trace.exists():
        raise SystemExit(f"Trace not found: {trace}")

    analysis_dir = trace.with_suffix(trace.suffix + "_analysis")
    analysis_dir.mkdir(parents=True, exist_ok=True)
    sql_files = sorted(SQL_DIR.glob("*.sql"))
    if not sql_files:
        raise SystemExit(f"No SQL files found in {SQL_DIR}")

    outputs = []
    for sql_file in sql_files:
        output_file = analysis_dir / f"{sql_file.stem}.txt"
        result = run(
            [
                str(TRACE_PROCESSOR),
                "query",
                "-f",
                str(sql_file),
                str(trace),
            ],
            capture_output=True,
        )
        output_file.write_text(result.stdout, encoding="utf-8")
        outputs.append(output_file)

    summary = analysis_dir / "README.md"
    lines = [
        f"# Imla Perfetto Analysis: {trace.name}",
        "",
        f"- Trace: `{trace.relative_to(ROOT)}`",
        f"- SQL directory: `{SQL_DIR.relative_to(ROOT)}`",
        f"- Generated: {datetime.now().isoformat(timespec='seconds')}",
        "",
        "## Query Outputs",
        "",
    ]
    for output in outputs:
        lines.append(f"- `{output.relative_to(ROOT)}`")
    summary.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(summary.relative_to(ROOT))


def launch_app(device):
    adb(
        device,
        "shell",
        "am",
        "start",
        "-n",
        f"{APP_ID}/{APP_ID}.MainActivity",
        "-a",
        "android.intent.action.MAIN",
        "-c",
        "android.intent.category.LAUNCHER",
    )


def device_size(device):
    result = adb(device, "shell", "wm", "size", capture_output=True)
    match = re.search(r"Physical size:\s*(\d+)x(\d+)", result.stdout)
    if not match:
        raise SystemExit(f"Unable to read device size from wm output: {result.stdout.strip()}")
    return int(match.group(1)), int(match.group(2))


def input_tap_relative(device, width, height, x, y):
    adb(device, "shell", "input", "tap", str(round(width * x)), str(round(height * y)))


def input_swipe_relative(device, width, height, start_x, start_y, end_x, end_y, duration_ms):
    adb(
        device,
        "shell",
        "input",
        "swipe",
        str(round(width * start_x)),
        str(round(height * start_y)),
        str(round(width * end_x)),
        str(round(height * end_y)),
        str(duration_ms),
    )


def exercise_app(device):
    commands = [
        ("input", "swipe", "540", "1900", "540", "700", "700"),
        ("input", "swipe", "540", "1900", "540", "700", "700"),
        ("input", "swipe", "540", "700", "540", "1900", "700"),
        ("input", "tap", "900", "2230"),
    ]
    for command in commands:
        adb(device, "shell", *command, check=False)
        time.sleep(1)


def open_settings_sheet(device, width, height):
    input_tap_relative(device, width, height, 0.948, 0.099)
    time.sleep(3)


def exercise_pure_slider(device):
    width, height = device_size(device)
    input_swipe_relative(device, width, height, 0.371, 0.678, 0.771, 0.678, 900)
    time.sleep(0.5)
    input_swipe_relative(device, width, height, 0.771, 0.678, 0.491, 0.678, 700)
    time.sleep(0.5)
    input_swipe_relative(device, width, height, 0.520, 0.732, 0.771, 0.732, 900)
    time.sleep(0.5)
    input_swipe_relative(device, width, height, 0.771, 0.732, 0.366, 0.732, 900)


def exercise_sheet_reopen(device):
    width, height = device_size(device)
    open_settings_sheet(device, width, height)
    input_swipe_relative(device, width, height, 0.5, 0.46, 0.5, 0.93, 700)
    time.sleep(1)
    open_settings_sheet(device, width, height)


def exercise_sheet_motion(device):
    width, height = device_size(device)
    input_swipe_relative(device, width, height, 0.5, 0.50, 0.5, 0.58, 700)
    time.sleep(0.5)
    input_swipe_relative(device, width, height, 0.5, 0.58, 0.5, 0.49, 700)


def exercise_nav_content(device):
    width, height = device_size(device)
    for x in (0.52, 0.66, 0.18):
        input_tap_relative(device, width, height, x, 0.94)
        time.sleep(0.8)


def prepare_exercise(device, mode):
    if mode in ("pure-slider", "sheet-motion"):
        width, height = device_size(device)
        open_settings_sheet(device, width, height)


def exercise_mode(device, mode):
    if mode == "smoke":
        exercise_app(device)
    elif mode == "pure-slider":
        exercise_pure_slider(device)
    elif mode == "sheet-reopen":
        exercise_sheet_reopen(device)
    elif mode == "sheet-motion":
        exercise_sheet_motion(device)
    elif mode == "nav-content":
        exercise_nav_content(device)
    else:
        raise SystemExit(f"Unknown exercise mode: {mode}")


def capture_trace(args):
    TRACE_DIR.mkdir(parents=True, exist_ok=True)
    SCREENSHOT_DIR.mkdir(parents=True, exist_ok=True)
    remote_config = "/data/misc/perfetto-configs/imla-smoke.perfetto.cfg"
    remote_trace = "/data/misc/perfetto-traces/imla-smoke.perfetto-trace"
    trace_name = f"imla-smoke-{timestamp()}.perfetto-trace"
    local_trace = TRACE_DIR / trace_name
    local_screenshot = SCREENSHOT_DIR / f"{local_trace.stem}.png"

    adb(args.device, "shell", "mkdir", "-p", "/data/misc/perfetto-configs", "/data/misc/perfetto-traces")
    adb(args.device, "push", str(CONFIG), remote_config)
    launch_app(args.device)
    time.sleep(args.warmup_seconds)
    if args.exercise:
        prepare_exercise(args.device, args.exercise_mode)

    perfetto = subprocess.Popen(
        [
            "adb",
            "-s",
            args.device,
            "shell",
            "perfetto",
            "--txt",
            "-c",
            remote_config,
            "-o",
            remote_trace,
        ],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    time.sleep(1)
    if args.exercise:
        exercise_mode(args.device, args.exercise_mode)
    stdout, stderr = perfetto.communicate()
    if perfetto.returncode != 0:
        raise SystemExit(stderr.strip() or stdout.strip() or "perfetto capture failed")

    adb(args.device, "pull", remote_trace, str(local_trace))
    local_screenshot.write_bytes(capture_screenshot_png(args.device))
    print(local_trace.relative_to(ROOT))
    print(local_screenshot.relative_to(ROOT))
    analyze_trace(local_trace)
    if args.open_apa:
        open_apa(local_trace)


def open_apa(trace_path):
    trace = Path(trace_path)
    if not trace.is_absolute():
        trace = ROOT / trace
    run(["open", "-a", APA_APP, str(trace)])


def parse_args(argv):
    parser = argparse.ArgumentParser(description="Capture and analyze Imla Perfetto feedback traces.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    analyze = subparsers.add_parser("analyze", help="Run checked-in SQL analysis for a trace.")
    analyze.add_argument("trace")

    open_command = subparsers.add_parser("open", help="Open a trace in Android Performance Analyzer.")
    open_command.add_argument("trace")

    for name in ("capture", "smoke"):
        capture = subparsers.add_parser(name, help="Capture a new smoke trace from a connected device.")
        capture.add_argument("--device", required=True, help="ADB serial, for example emulator-5580.")
        capture.add_argument("--warmup-seconds", type=float, default=2.0)
        capture.add_argument("--open-apa", action="store_true")
        capture.add_argument(
            "--exercise-mode",
            choices=["smoke", "pure-slider", "sheet-reopen", "sheet-motion", "nav-content"],
            default="smoke",
        )
        exercise = capture.add_mutually_exclusive_group()
        exercise.add_argument("--exercise", dest="exercise", action="store_true", default=name == "smoke")
        exercise.add_argument("--no-exercise", dest="exercise", action="store_false")

    return parser.parse_args(argv)


def main(argv):
    args = parse_args(argv)
    if args.command == "analyze":
        analyze_trace(args.trace)
    elif args.command == "open":
        open_apa(args.trace)
    elif args.command in ("capture", "smoke"):
        capture_trace(args)
    else:
        raise SystemExit(f"Unknown command: {args.command}")


if __name__ == "__main__":
    main(sys.argv[1:])
