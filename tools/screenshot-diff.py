#!/usr/bin/env python3
from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
from datetime import datetime
import json
import math
from pathlib import Path
import sys

try:
    from PIL import Image, ImageChops, ImageDraw, ImageFilter
except ImportError as exc:
    raise SystemExit(
        "Pillow is required for screenshot comparison.\n"
        "Install it in a local ignored environment, for example:\n"
        "  tools/screenshot-diff before.png after.png\n"
        "The shell wrapper creates .codex/screenshot-diff-venv and installs Pillow."
    ) from exc


ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class DiffMetrics:
    width: int
    height: int
    pixels: int
    pixel_threshold: int
    changed_pixels: int
    changed_ratio: float
    mae: float
    rmse: float
    max_delta: int
    p50_delta: int
    p95_delta: int
    p99_delta: int
    ssim_luma_global: float


def timestamp() -> str:
    return datetime.now().strftime("%Y-%m-%d-%H%M%S")


def parse_crop(value: str) -> tuple[int, int, int, int]:
    parts = [part.strip() for part in value.split(",")]
    if len(parts) != 4:
        raise argparse.ArgumentTypeError("crop must be left,top,right,bottom")
    try:
        left, top, right, bottom = (int(part) for part in parts)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("crop coordinates must be integers") from exc
    if right <= left or bottom <= top:
        raise argparse.ArgumentTypeError("crop right/bottom must be greater than left/top")
    return left, top, right, bottom


def resolve_path(path: str) -> Path:
    resolved = Path(path)
    if not resolved.is_absolute():
        resolved = ROOT / resolved
    return resolved


def load_image(path: Path, crop: tuple[int, int, int, int] | None) -> Image.Image:
    if not path.exists():
        raise SystemExit(f"Image not found: {path}")
    image = Image.open(path).convert("RGB")
    if crop is not None:
        width, height = image.size
        left, top, right, bottom = crop
        if left < 0 or top < 0 or right > width or bottom > height:
            raise SystemExit(f"Crop {crop} is outside image bounds {width}x{height}: {path}")
        image = image.crop(crop)
    return image


def compared_image(image: Image.Image, smooth_radius: float) -> Image.Image:
    if smooth_radius <= 0:
        return image
    return image.filter(ImageFilter.GaussianBlur(radius=smooth_radius))


def percentile_from_histogram(histogram: list[int], percentile: float, total: int) -> int:
    target = math.ceil(total * percentile)
    seen = 0
    for value, count in enumerate(histogram):
        seen += count
        if seen >= target:
            return value
    return 255


def global_luma_ssim(reference: Image.Image, candidate: Image.Image) -> float:
    reference_data = reference.convert("L").tobytes()
    candidate_data = candidate.convert("L").tobytes()
    count = len(reference_data)
    if count == 0:
        return 1.0

    mean_reference = sum(reference_data) / count
    mean_candidate = sum(candidate_data) / count
    variance_reference = 0.0
    variance_candidate = 0.0
    covariance = 0.0

    for reference_value, candidate_value in zip(reference_data, candidate_data):
        reference_delta = reference_value - mean_reference
        candidate_delta = candidate_value - mean_candidate
        variance_reference += reference_delta * reference_delta
        variance_candidate += candidate_delta * candidate_delta
        covariance += reference_delta * candidate_delta

    variance_reference /= count
    variance_candidate /= count
    covariance /= count

    c1 = (0.01 * 255) ** 2
    c2 = (0.03 * 255) ** 2
    numerator = (2 * mean_reference * mean_candidate + c1) * (2 * covariance + c2)
    denominator = (
        (mean_reference**2 + mean_candidate**2 + c1)
        * (variance_reference + variance_candidate + c2)
    )
    if denominator == 0:
        return 1.0
    return numerator / denominator


def compute_metrics(
    reference: Image.Image,
    candidate: Image.Image,
    pixel_threshold: int,
) -> tuple[DiffMetrics, Image.Image, Image.Image]:
    if reference.size != candidate.size:
        raise SystemExit(
            "Image sizes differ: "
            f"reference={reference.size[0]}x{reference.size[1]}, "
            f"candidate={candidate.size[0]}x{candidate.size[1]}"
        )

    diff = ImageChops.difference(reference, candidate)
    diff_data = diff.tobytes()
    histogram = [0] * 256
    changed_pixels = 0
    total_abs = 0
    total_squared = 0
    mask_values: list[int] = []

    for index in range(0, len(diff_data), 3):
        red = diff_data[index]
        green = diff_data[index + 1]
        blue = diff_data[index + 2]
        pixel_delta = max(red, green, blue)
        histogram[pixel_delta] += 1
        if pixel_delta > pixel_threshold:
            changed_pixels += 1
            mask_values.append(255)
        else:
            mask_values.append(0)
        total_abs += red + green + blue
        total_squared += red * red + green * green + blue * blue

    width, height = reference.size
    pixels = width * height
    channels = pixels * 3
    metrics = DiffMetrics(
        width=width,
        height=height,
        pixels=pixels,
        pixel_threshold=pixel_threshold,
        changed_pixels=changed_pixels,
        changed_ratio=changed_pixels / pixels if pixels else 0.0,
        mae=total_abs / channels if channels else 0.0,
        rmse=math.sqrt(total_squared / channels) if channels else 0.0,
        max_delta=max(index for index, count in enumerate(histogram) if count),
        p50_delta=percentile_from_histogram(histogram, 0.50, pixels),
        p95_delta=percentile_from_histogram(histogram, 0.95, pixels),
        p99_delta=percentile_from_histogram(histogram, 0.99, pixels),
        ssim_luma_global=global_luma_ssim(reference, candidate),
    )
    mask = Image.new("L", reference.size)
    mask.putdata(mask_values)
    return metrics, diff, mask


def default_output_dir(reference: Path, candidate: Path) -> Path:
    name = f"{reference.stem}__vs__{candidate.stem}-{timestamp()}"
    return ROOT / "diagnostics" / "screenshot-diff" / name


def display_path(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def fit_tile(image: Image.Image, max_width: int, max_height: int) -> Image.Image:
    tile = image.convert("RGB")
    tile.thumbnail((max_width, max_height), Image.Resampling.LANCZOS)
    return tile


def make_contact_sheet(
    reference: Image.Image,
    candidate: Image.Image,
    amplified_diff: Image.Image,
    mask: Image.Image,
) -> Image.Image:
    tiles = [
        ("reference", reference),
        ("candidate", candidate),
        ("diff amplified", amplified_diff),
        ("threshold mask", mask.convert("RGB")),
    ]
    fitted_tiles = [(label, fit_tile(image, 640, 640)) for label, image in tiles]
    label_height = 28
    padding = 16
    tile_width = max(tile.width for _, tile in fitted_tiles)
    tile_height = max(tile.height for _, tile in fitted_tiles)
    sheet = Image.new(
        "RGB",
        (padding * 3 + tile_width * 2, padding * 3 + (tile_height + label_height) * 2),
        "white",
    )
    draw = ImageDraw.Draw(sheet)

    for index, (label, tile) in enumerate(fitted_tiles):
        column = index % 2
        row = index // 2
        x = padding + column * (tile_width + padding)
        y = padding + row * (tile_height + label_height + padding)
        draw.text((x, y), label, fill="black")
        sheet.paste(tile, (x, y + label_height))
    return sheet


def evaluate_gates(args: argparse.Namespace, metrics: DiffMetrics) -> list[str]:
    failures = []
    if args.fail_changed_ratio is not None and metrics.changed_ratio > args.fail_changed_ratio:
        failures.append(
            f"changed_ratio {metrics.changed_ratio:.6f} > {args.fail_changed_ratio:.6f}"
        )
    if args.fail_mae is not None and metrics.mae > args.fail_mae:
        failures.append(f"mae {metrics.mae:.4f} > {args.fail_mae:.4f}")
    if args.fail_rmse is not None and metrics.rmse > args.fail_rmse:
        failures.append(f"rmse {metrics.rmse:.4f} > {args.fail_rmse:.4f}")
    if args.fail_max_delta is not None and metrics.max_delta > args.fail_max_delta:
        failures.append(f"max_delta {metrics.max_delta} > {args.fail_max_delta}")
    if args.fail_ssim_below is not None and metrics.ssim_luma_global < args.fail_ssim_below:
        failures.append(
            f"ssim_luma_global {metrics.ssim_luma_global:.6f} < {args.fail_ssim_below:.6f}"
        )
    return failures


def write_report(
    output_dir: Path,
    reference_path: Path,
    candidate_path: Path,
    args: argparse.Namespace,
    metrics: DiffMetrics,
    failures: list[str],
) -> None:
    report = {
        "reference": str(reference_path),
        "candidate": str(candidate_path),
        "crop": args.crop,
        "smooth_radius": args.smooth_radius,
        "amplify": args.amplify,
        "passed": not failures,
        "failures": failures,
        "metrics": asdict(metrics),
    }
    (output_dir / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    lines = [
        "# Screenshot Diff",
        "",
        f"- Reference: `{reference_path}`",
        f"- Candidate: `{candidate_path}`",
        f"- Size: {metrics.width}x{metrics.height}",
        f"- Pixel threshold: {metrics.pixel_threshold}",
        f"- Smooth radius: {args.smooth_radius}",
        f"- Result: {'FAIL' if failures else 'PASS'}",
        "",
        "## Metrics",
        "",
        f"- MAE: {metrics.mae:.4f}",
        f"- RMSE: {metrics.rmse:.4f}",
        f"- Max delta: {metrics.max_delta}",
        f"- Changed pixels: {metrics.changed_pixels} / {metrics.pixels} ({metrics.changed_ratio:.6%})",
        f"- Delta percentiles: p50={metrics.p50_delta}, p95={metrics.p95_delta}, p99={metrics.p99_delta}",
        f"- Global luma SSIM: {metrics.ssim_luma_global:.6f}",
        "",
        "## Artifacts",
        "",
        "- `reference.png`",
        "- `candidate.png`",
        "- `diff.png`",
        "- `diff-amplified.png`",
        "- `threshold-mask.png`",
        "- `contact-sheet.png`",
        "- `report.json`",
    ]
    if failures:
        lines.extend(["", "## Gate Failures", ""])
        lines.extend(f"- {failure}" for failure in failures)
    (output_dir / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def save_artifacts(
    output_dir: Path,
    reference: Image.Image,
    candidate: Image.Image,
    compared_reference: Image.Image,
    compared_candidate: Image.Image,
    diff: Image.Image,
    mask: Image.Image,
    amplify: int,
) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    reference.save(output_dir / "reference.png")
    candidate.save(output_dir / "candidate.png")
    if compared_reference is not reference:
        compared_reference.save(output_dir / "compared-reference.png")
        compared_candidate.save(output_dir / "compared-candidate.png")
    diff.save(output_dir / "diff.png")
    amplified_diff = diff.point(lambda value: min(255, value * amplify))
    amplified_diff.save(output_dir / "diff-amplified.png")
    mask.save(output_dir / "threshold-mask.png")
    make_contact_sheet(reference, candidate, amplified_diff, mask).save(output_dir / "contact-sheet.png")


def run_diff(args: argparse.Namespace) -> int:
    reference_path = resolve_path(args.reference)
    candidate_path = resolve_path(args.candidate)
    output_dir = resolve_path(args.out) if args.out else default_output_dir(reference_path, candidate_path)

    reference = load_image(reference_path, args.crop)
    candidate = load_image(candidate_path, args.crop)
    compared_reference = compared_image(reference, args.smooth_radius)
    compared_candidate = compared_image(candidate, args.smooth_radius)
    metrics, diff, mask = compute_metrics(
        reference=compared_reference,
        candidate=compared_candidate,
        pixel_threshold=args.pixel_threshold,
    )
    failures = evaluate_gates(args, metrics)

    save_artifacts(
        output_dir=output_dir,
        reference=reference,
        candidate=candidate,
        compared_reference=compared_reference,
        compared_candidate=compared_candidate,
        diff=diff,
        mask=mask,
        amplify=args.amplify,
    )
    write_report(
        output_dir=output_dir,
        reference_path=reference_path,
        candidate_path=candidate_path,
        args=args,
        metrics=metrics,
        failures=failures,
    )

    result = "FAIL" if failures else "PASS"
    print(f"{result} {display_path(output_dir)}")
    print(
        "MAE={:.4f} RMSE={:.4f} changed={:.6%} max_delta={} p95={} p99={} ssim_luma_global={:.6f}".format(
            metrics.mae,
            metrics.rmse,
            metrics.changed_ratio,
            metrics.max_delta,
            metrics.p95_delta,
            metrics.p99_delta,
            metrics.ssim_luma_global,
        )
    )
    for failure in failures:
        print(f"gate: {failure}", file=sys.stderr)
    return 1 if failures else 0


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Compare two screenshots and write diff artifacts plus numeric metrics."
    )
    parser.add_argument("reference", help="Baseline/reference screenshot.")
    parser.add_argument("candidate", help="Candidate/current screenshot.")
    parser.add_argument("--out", help="Output directory for report and visual artifacts.")
    parser.add_argument(
        "--pixel-threshold",
        type=int,
        default=4,
        help="Per-pixel max-channel delta ignored by the changed-pixel ratio. Default: 4.",
    )
    parser.add_argument(
        "--smooth-radius",
        type=float,
        default=0.0,
        help="Gaussian blur radius applied before metrics. Useful for noisy blur comparisons.",
    )
    parser.add_argument(
        "--amplify",
        type=int,
        default=8,
        help="Multiplier for diff-amplified.png. Default: 8.",
    )
    parser.add_argument(
        "--crop",
        type=parse_crop,
        help="Compare only left,top,right,bottom pixels.",
    )
    parser.add_argument("--fail-changed-ratio", type=float, help="Fail if changed ratio is above this.")
    parser.add_argument("--fail-mae", type=float, help="Fail if MAE is above this.")
    parser.add_argument("--fail-rmse", type=float, help="Fail if RMSE is above this.")
    parser.add_argument("--fail-max-delta", type=int, help="Fail if max channel delta is above this.")
    parser.add_argument(
        "--fail-ssim-below",
        type=float,
        help="Fail if global luma SSIM is below this.",
    )
    args = parser.parse_args(argv)
    if not 0 <= args.pixel_threshold <= 255:
        parser.error("--pixel-threshold must be between 0 and 255")
    if args.smooth_radius < 0:
        parser.error("--smooth-radius must be non-negative")
    if args.amplify < 1:
        parser.error("--amplify must be at least 1")
    return args


def main(argv: list[str]) -> None:
    raise SystemExit(run_diff(parse_args(argv)))


if __name__ == "__main__":
    main(sys.argv[1:])
