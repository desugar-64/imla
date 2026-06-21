#!/usr/bin/env python3
"""Query GaussianBlurEffect#apply durations from a Perfetto trace."""

from __future__ import annotations

import argparse
import glob
import os
from pathlib import Path

from perfetto.trace_processor import TraceProcessor


def find_latest_trace() -> str | None:
    candidates = glob.glob(
        "benchmark/build/outputs/connected_android_test_additional_output/**/*.perfetto-trace",
        recursive=True,
    )
    if not candidates:
        return None
    return max(candidates, key=os.path.getmtime)


def format_ms(value: float) -> str:
    return f"{value:.3f}ms"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "trace",
        nargs="?",
        help="Path to perfetto trace (defaults to latest benchmark trace)",
    )
    parser.add_argument(
        "--threshold-ms",
        type=float,
        default=7.0,
        help="Highlight durations >= this threshold (default: 7.0)",
    )
    parser.add_argument(
        "--top",
        type=int,
        default=20,
        help="Show N slowest slices (default: 20)",
    )
    args = parser.parse_args()

    trace_path = args.trace or find_latest_trace()
    if not trace_path:
        print("No trace file found. Provide a trace path or run benchmarks first.")
        return 1

    trace_path = str(Path(trace_path))
    print(f"Trace: {trace_path}")

    with TraceProcessor(trace=trace_path) as tp:
        base_query = (
            "SELECT s.ts, s.dur, t.name AS thread_name "
            "FROM slice s "
            "LEFT JOIN thread_track tt ON s.track_id = tt.id "
            "LEFT JOIN thread t ON tt.utid = t.utid "
            "WHERE s.name = 'GaussianBlurEffect#apply'"
        )
        result = tp.query(base_query)
        df = result.as_pandas_dataframe()

        if df.empty:
            print("No GaussianBlurEffect#apply slices found.")
            return 0

        df["dur_ms"] = df["dur"] / 1_000_000.0
        df_sorted = df.sort_values("dur_ms", ascending=False)

        p50 = df["dur_ms"].quantile(0.50)
        p90 = df["dur_ms"].quantile(0.90)
        p95 = df["dur_ms"].quantile(0.95)
        p99 = df["dur_ms"].quantile(0.99)

        print("\nSummary")
        print("-------")
        print(f"count: {len(df)}")
        print(f"avg:   {format_ms(df['dur_ms'].mean())}")
        print(f"min:   {format_ms(df['dur_ms'].min())}")
        print(f"p50:   {format_ms(p50)}")
        print(f"p90:   {format_ms(p90)}")
        print(f"p95:   {format_ms(p95)}")
        print(f"p99:   {format_ms(p99)}")
        print(f"max:   {format_ms(df['dur_ms'].max())}")

        threshold = float(args.threshold_ms)
        over = df[df["dur_ms"] >= threshold]
        print("\nThreshold")
        print("---------")
        print(
            f">= {threshold:.1f}ms: {len(over)} / {len(df)} "
            f"({len(over) / len(df) * 100:.1f}%)"
        )

        top_n = max(1, args.top)
        print(f"\nTop {top_n} slowest")
        print("----------------")
        head = df_sorted.head(top_n)
        for _, row in head.iterrows():
            thread = row.get("thread_name") or "(unknown)"
            print(f"{format_ms(row['dur_ms'])}  thread={thread}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
