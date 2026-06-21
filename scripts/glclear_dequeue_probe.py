#!/usr/bin/env python3
"""
Inspect glClear slices and the dequeueBuffer work nested inside them.

Usage:
  source perfetto-env/bin/activate
  python scripts/glclear_dequeue_probe.py --trace path/to/trace.perfetto-trace

If --trace is omitted, the latest trace under
benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected
is used automatically.
"""

import argparse
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional

import pandas as pd
from perfetto.trace_processor import TraceProcessor


TRACE_ROOT = Path(
    "benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected"
)


@dataclass
class GLClearEvent:
    slice_id: int
    ts_ms: float
    dur_ms: float
    track_id: int
    track_name: str
    thread_name: str
    process_name: str
    dq_count: int
    dq_total_ms: float
    dq_max_ms: float


def resolve_trace_path(explicit: Optional[Path]) -> Path:
    if explicit:
        if not explicit.exists():
            raise FileNotFoundError(f"Trace not found: {explicit}")
        return explicit

    traces = sorted(
        TRACE_ROOT.rglob("*.perfetto-trace"),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    if not traces:
        raise FileNotFoundError(f"No .perfetto-trace files under {TRACE_ROOT}")
    return traces[0]


def sanitized_process_clause(process_name: str) -> str:
    if not process_name:
        return ""
    safe_name = process_name.replace("'", "''")
    return f"AND p.name = '{safe_name}'"


def load_glclear_and_dequeue(
    tp: TraceProcessor, process_name: str
) -> tuple[pd.DataFrame, pd.DataFrame]:
    process_clause = sanitized_process_clause(process_name)

    gl_df = tp.query(
        f"""
        SELECT s.id, s.ts, s.dur, s.ts + s.dur AS ts_end, s.track_id,
               t.name AS track_name, th.name AS thread_name, p.name AS process_name
        FROM slice s
        JOIN track t ON s.track_id = t.id
        JOIN thread_track tt ON t.id = tt.id
        JOIN thread th ON tt.utid = th.utid
        JOIN process p ON th.upid = p.upid
        WHERE s.name = 'glClear' {process_clause}
        ORDER BY s.ts
        """
    ).as_pandas_dataframe()

    dq_df = tp.query(
        f"""
        SELECT s.id, s.parent_id, s.ts, s.dur, s.ts + s.dur AS ts_end, s.track_id,
               t.name AS track_name, th.name AS thread_name, p.name AS process_name
        FROM slice s
        JOIN track t ON s.track_id = t.id
        JOIN thread_track tt ON t.id = tt.id
        JOIN thread th ON tt.utid = th.utid
        JOIN process p ON th.upid = p.upid
        WHERE s.name LIKE 'dequeueBuffer%' {process_clause}
        ORDER BY s.ts
        """
    ).as_pandas_dataframe()

    return gl_df, dq_df


def percentile_stats(values: List[float]) -> Optional[Dict[str, float]]:
    if not values:
        return None
    series = pd.Series(values)
    return {
        "count": len(values),
        "avg": series.mean(),
        "p50": series.quantile(0.50),
        "p90": series.quantile(0.90),
        "p95": series.quantile(0.95),
        "p99": series.quantile(0.99),
        "min": series.min(),
        "max": series.max(),
        "total": series.sum(),
    }


def format_stats(label: str, stats: Optional[Dict[str, float]]) -> str:
    if not stats:
        return f"{label}: no samples"
    return (
        f"{label}: count={stats['count']}, "
        f"avg={stats['avg']:.3f}ms, p50={stats['p50']:.3f}ms, "
        f"p90={stats['p90']:.3f}ms, p95={stats['p95']:.3f}ms, p99={stats['p99']:.3f}ms, "
        f"min={stats['min']:.3f}ms, max={stats['max']:.3f}ms"
    )


def build_glclear_events(gl_df: pd.DataFrame, dq_df: pd.DataFrame) -> List[GLClearEvent]:
    dq_by_track: Dict[int, pd.DataFrame] = {
        track_id: group.sort_values("ts") for track_id, group in dq_df.groupby("track_id")
    }
    events: List[GLClearEvent] = []

    for _, row in gl_df.iterrows():
        dq_candidates = dq_by_track.get(int(row["track_id"]))
        dq_matches = (
            dq_candidates[
                (dq_candidates["ts"] >= row["ts"]) & (dq_candidates["ts"] <= row["ts_end"])
            ]
            if dq_candidates is not None
            else pd.DataFrame()
        )

        dq_count = len(dq_matches)
        dq_total_ms = float(dq_matches["dur"].sum()) / 1_000_000 if dq_count else 0.0
        dq_max_ms = float(dq_matches["dur"].max()) / 1_000_000 if dq_count else 0.0

        events.append(
            GLClearEvent(
                slice_id=int(row["id"]),
                ts_ms=float(row["ts"]) / 1_000_000,
                dur_ms=float(row["dur"]) / 1_000_000,
                track_id=int(row["track_id"]),
                track_name=str(row["track_name"]),
                thread_name=str(row["thread_name"]),
                process_name=str(row["process_name"]),
                dq_count=int(dq_count),
                dq_total_ms=dq_total_ms,
                dq_max_ms=dq_max_ms,
            )
        )

    return events


def print_top_events(events: List[GLClearEvent], limit: int) -> None:
    print(f"\nTop {limit} glClear slices by duration (ms):")
    for event in sorted(events, key=lambda e: e.dur_ms, reverse=True)[:limit]:
        dq_note = (
            f"dq_count={event.dq_count}, dq_total={event.dq_total_ms:.3f}ms, "
            f"dq_max={event.dq_max_ms:.3f}ms"
        )
        print(
            f"  ts={event.ts_ms:,.3f}ms, dur={event.dur_ms:.3f}ms, "
            f"{dq_note}, slice_id={event.slice_id}"
        )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Inspect glClear slices and nested dequeueBuffer work inside them."
    )
    parser.add_argument(
        "--trace",
        type=Path,
        help="Path to a .perfetto-trace file. Defaults to the latest connected benchmark trace.",
    )
    parser.add_argument(
        "--process",
        default="dev.serhiiyaremych.imla",
        help="Process name filter (empty string disables filtering).",
    )
    parser.add_argument(
        "--top",
        type=int,
        default=10,
        help="Number of longest glClear slices to print.",
    )
    args = parser.parse_args()

    trace_path = resolve_trace_path(args.trace)
    print(f"Using trace: {trace_path}")

    with TraceProcessor(trace=str(trace_path)) as tp:
        gl_df, dq_df = load_glclear_and_dequeue(tp, args.process)

    if gl_df.empty:
        print("No glClear slices found with the current filter.")
        return

    events = build_glclear_events(gl_df, dq_df)

    all_gl_stats = percentile_stats([e.dur_ms for e in events])
    dq_gl_events = [e for e in events if e.dq_count > 0]
    dq_gl_stats = percentile_stats([e.dur_ms for e in dq_gl_events])
    dq_nested_stats = percentile_stats([e.dq_total_ms for e in dq_gl_events])
    dq_slice_stats = percentile_stats(
        list((dq_df[dq_df["track_id"].isin({e.track_id for e in events})]["dur"]) / 1_000_000)
        if not dq_df.empty
        else []
    )

    print(f"Process filter: {args.process or 'none'}")
    print(f"glClear slices: {len(events)}")
    print(f"glClear with nested dequeueBuffer: {len(dq_gl_events)}")
    print(format_stats("glClear duration", all_gl_stats))
    print(format_stats("glClear (with dequeueBuffer)", dq_gl_stats))
    print(format_stats("Nested dequeueBuffer total per glClear", dq_nested_stats))
    print(format_stats("dequeueBuffer slice duration (same track)", dq_slice_stats))

    if dq_gl_events:
        print_top_events(events, args.top)
    else:
        print("\nNo nested dequeueBuffer slices found inside glClear ranges.")


if __name__ == "__main__":
    main()
