#!/usr/bin/env python3
import argparse
from datetime import datetime
from io import BytesIO
from pathlib import Path
import subprocess
import sys

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SCREENSHOT_DIR = ROOT / "diagnostics" / "apa" / "screenshots"
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Capture an Android screenshot and write a PNG resized to half dimensions."
    )
    parser.add_argument(
        "--device",
        help="ADB serial forwarded to tools/adb-timeout.",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=20.0,
        help="Maximum seconds for screencap. Default: 20.",
    )
    parser.add_argument(
        "--out",
        type=Path,
        help="Output PNG path. Default: diagnostics/apa/screenshots/screenshot-half-<timestamp>.png.",
    )
    args = parser.parse_args(argv)
    if args.timeout <= 0:
        parser.error("--timeout must be greater than zero")
    return args


def timestamp():
    return datetime.now().strftime("%Y-%m-%d-%H%M%S")


def output_path(path):
    if path is None:
        return DEFAULT_SCREENSHOT_DIR / f"screenshot-half-{timestamp()}.png"
    return path if path.is_absolute() else ROOT / path


def capture_screenshot_png(device, timeout):
    command = [
        str(ROOT / "tools" / "adb-timeout"),
        "--timeout",
        f"{timeout:g}",
    ]
    if device:
        command.extend(["--device", device])
    command.extend(["exec-out", "screencap", "-p"])

    result = subprocess.run(command, cwd=ROOT, check=False, capture_output=True)
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        raise SystemExit(f"Command failed: {' '.join(command)}\n{detail}")

    png_start = result.stdout.find(PNG_SIGNATURE)
    if png_start < 0:
        raise SystemExit("screencap did not return PNG data")
    return result.stdout[png_start:]


def resize_half(png_bytes):
    source = Image.open(BytesIO(png_bytes))
    source.load()
    target_size = (
        max(1, source.width // 2),
        max(1, source.height // 2),
    )
    resized = source.resize(target_size, Image.Resampling.LANCZOS)
    output = BytesIO()
    resized.save(output, format="PNG", optimize=True)
    return resized.size, output.getvalue()


def main(argv):
    args = parse_args(argv)
    destination = output_path(args.out)
    destination.parent.mkdir(parents=True, exist_ok=True)

    png_bytes = capture_screenshot_png(args.device, args.timeout)
    size, resized_png = resize_half(png_bytes)
    destination.write_bytes(resized_png)

    relative = destination.relative_to(ROOT) if destination.is_relative_to(ROOT) else destination
    print(f"{relative} ({size[0]}x{size[1]})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
