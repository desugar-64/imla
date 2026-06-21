#!/usr/bin/env python3
"""
Analyze RenderingPipeline#renderAll and its children from Perfetto trace.
Usage: source perfetto-env/bin/activate && python scripts/analyze_render_pipeline.py [trace_file]
"""

from perfetto.trace_processor import TraceProcessor
import sys
from pathlib import Path
from typing import Dict, List, Any, Optional
from dataclasses import dataclass
from collections import defaultdict


@dataclass
class SliceInfo:
    name: str
    ts: int  # nanoseconds
    dur: int  # nanoseconds
    depth: int
    track_id: int
    id: int
    parent_id: Optional[int]

    @property
    def dur_ms(self) -> float:
        return self.dur / 1_000_000

    @property
    def end_ts(self) -> int:
        return self.ts + self.dur


def find_latest_trace() -> Optional[str]:
    """Find most recent benchmark trace file"""
    results_dir = Path("benchmark/build/outputs/connected_android_test_additional_output")
    if not results_dir.exists():
        return None

    trace_files = list(results_dir.glob("**/*.perfetto-trace"))
    if not trace_files:
        return None

    return str(max(trace_files, key=lambda x: x.stat().st_mtime))


def analyze_render_pipeline(trace_path: str):
    """Analyze RenderingPipeline#renderAll and all nested children"""

    print(f"🔍 Analyzing trace: {trace_path}\n")

    with TraceProcessor(trace=trace_path) as tp:
        # First, find RenderingPipeline#renderAll events
        query = """
        SELECT
            s.id,
            s.name,
            s.ts,
            s.dur,
            s.depth,
            s.track_id,
            s.parent_id
        FROM slice s
        WHERE s.name = 'RenderingPipeline#renderAll'
        ORDER BY s.ts
        LIMIT 100
        """

        result = tp.query(query)
        df = result.as_pandas_dataframe()

        if len(df) == 0:
            print("❌ No RenderingPipeline#renderAll events found")
            return

        print(f"✅ Found {len(df)} RenderingPipeline#renderAll events\n")

        # Analyze timing distribution
        df['dur_ms'] = df['dur'] / 1_000_000
        print("📊 RenderingPipeline#renderAll Timing Statistics:")
        print(f"   Count:   {len(df)}")
        print(f"   Average: {df['dur_ms'].mean():.3f} ms")
        print(f"   Median:  {df['dur_ms'].median():.3f} ms")
        print(f"   Min:     {df['dur_ms'].min():.3f} ms")
        print(f"   Max:     {df['dur_ms'].max():.3f} ms")
        print(f"   P90:     {df['dur_ms'].quantile(0.90):.3f} ms")
        print(f"   P99:     {df['dur_ms'].quantile(0.99):.3f} ms")
        print()

        # Get one representative sample (pick median-duration one)
        median_dur = df['dur_ms'].median()
        sample_row = df.iloc[(df['dur_ms'] - median_dur).abs().argsort().iloc[0]]

        sample_id = int(sample_row['id'])
        sample_ts = int(sample_row['ts'])
        sample_dur = int(sample_row['dur'])
        sample_track = int(sample_row['track_id'])
        sample_depth = int(sample_row['depth'])

        print(f"🔬 Analyzing representative sample (closest to median):")
        print(f"   Duration: {sample_dur / 1_000_000:.3f} ms")
        print(f"   Track ID: {sample_track}")
        print(f"   Depth: {sample_depth}")
        print()

        # Find all children of this event (slices nested within it)
        # Children have: same track, ts >= parent.ts, ts + dur <= parent.ts + parent.dur, depth > parent.depth
        children_query = f"""
        SELECT
            s.id,
            s.name,
            s.ts,
            s.dur,
            s.depth,
            s.track_id,
            s.parent_id
        FROM slice s
        WHERE s.track_id = {sample_track}
          AND s.ts >= {sample_ts}
          AND (s.ts + s.dur) <= {sample_ts + sample_dur}
          AND s.depth > {sample_depth}
        ORDER BY s.ts, s.depth
        """

        children_result = tp.query(children_query)
        children_df = children_result.as_pandas_dataframe()

        print(f"📋 Found {len(children_df)} child events\n")

        if len(children_df) == 0:
            print("No children found for this event")
            return

        # Aggregate by name for summary
        children_df['dur_ms'] = children_df['dur'] / 1_000_000

        # Group by name and calculate stats
        agg_stats = children_df.groupby('name').agg({
            'dur_ms': ['count', 'sum', 'mean', 'max'],
            'depth': 'first'
        }).reset_index()

        agg_stats.columns = ['name', 'count', 'total_ms', 'avg_ms', 'max_ms', 'depth']
        agg_stats = agg_stats.sort_values('total_ms', ascending=False)

        # Calculate percentage of parent duration
        parent_dur_ms = sample_dur / 1_000_000
        agg_stats['pct_of_parent'] = (agg_stats['total_ms'] / parent_dur_ms * 100)

        print("=" * 100)
        print("🏆 TOP TIME CONSUMERS (sorted by total time):")
        print("=" * 100)
        print(f"{'Name':<55} {'Count':>6} {'Total(ms)':>10} {'Avg(ms)':>9} {'Max(ms)':>9} {'%Parent':>8}")
        print("-" * 100)

        for _, row in agg_stats.head(30).iterrows():
            depth_indent = "  " * (int(row['depth']) - sample_depth - 1)
            name = depth_indent + row['name'][:50-len(depth_indent)]
            print(f"{name:<55} {int(row['count']):>6} {row['total_ms']:>10.3f} {row['avg_ms']:>9.3f} {row['max_ms']:>9.3f} {row['pct_of_parent']:>7.1f}%")

        print()

        # Show hierarchical breakdown for direct children only
        direct_children_query = f"""
        SELECT
            s.name,
            s.dur,
            s.depth
        FROM slice s
        WHERE s.track_id = {sample_track}
          AND s.ts >= {sample_ts}
          AND (s.ts + s.dur) <= {sample_ts + sample_dur}
          AND s.depth = {sample_depth + 1}
        ORDER BY s.ts
        """

        direct_result = tp.query(direct_children_query)
        direct_df = direct_result.as_pandas_dataframe()

        if len(direct_df) > 0:
            direct_df['dur_ms'] = direct_df['dur'] / 1_000_000

            direct_agg = direct_df.groupby('name').agg({
                'dur_ms': ['count', 'sum', 'mean']
            }).reset_index()
            direct_agg.columns = ['name', 'count', 'total_ms', 'avg_ms']
            direct_agg = direct_agg.sort_values('total_ms', ascending=False)
            direct_agg['pct'] = direct_agg['total_ms'] / parent_dur_ms * 100

            print("=" * 80)
            print("📊 DIRECT CHILDREN BREAKDOWN (depth+1 only):")
            print("=" * 80)
            print(f"{'Name':<45} {'Count':>6} {'Total(ms)':>10} {'Avg(ms)':>9} {'%Parent':>8}")
            print("-" * 80)

            for _, row in direct_agg.iterrows():
                print(f"{row['name'][:45]:<45} {int(row['count']):>6} {row['total_ms']:>10.3f} {row['avg_ms']:>9.3f} {row['pct']:>7.1f}%")

            accounted = direct_agg['total_ms'].sum()
            unaccounted = parent_dur_ms - accounted
            print("-" * 80)
            print(f"{'TOTAL ACCOUNTED':<45} {'':<6} {accounted:>10.3f} {'':<9} {accounted/parent_dur_ms*100:>7.1f}%")
            print(f"{'UNACCOUNTED (self-time)':<45} {'':<6} {unaccounted:>10.3f} {'':<9} {unaccounted/parent_dur_ms*100:>7.1f}%")
            print(f"{'PARENT TOTAL':<45} {'':<6} {parent_dur_ms:>10.3f} {'':<9} {'100.0%':>8}")

        print()

        # Analyze across ALL renderAll events for consistency
        print("=" * 80)
        print("📈 AGGREGATE ANALYSIS ACROSS ALL renderAll EVENTS:")
        print("=" * 80)

        # Get all children across all renderAll events
        all_render_ids = df['id'].tolist()
        all_render_tracks = df['track_id'].tolist()
        all_render_ts = df['ts'].tolist()
        all_render_dur = df['dur'].tolist()
        all_render_depth = df['depth'].tolist()

        # Build a query that gets children for all renderAll events
        # This is complex, so we'll do it per-event and aggregate
        all_children_data = []

        for i in range(min(len(df), 50)):  # Limit to 50 events for performance
            q = f"""
            SELECT s.name, s.dur, s.depth
            FROM slice s
            WHERE s.track_id = {int(all_render_tracks[i])}
              AND s.ts >= {int(all_render_ts[i])}
              AND (s.ts + s.dur) <= {int(all_render_ts[i]) + int(all_render_dur[i])}
              AND s.depth = {int(all_render_depth[i]) + 1}
            """
            res = tp.query(q)
            child_df = res.as_pandas_dataframe()
            if len(child_df) > 0:
                child_df['parent_dur'] = all_render_dur[i]
                all_children_data.append(child_df)

        if all_children_data:
            import pandas as pd
            combined = pd.concat(all_children_data, ignore_index=True)
            combined['dur_ms'] = combined['dur'] / 1_000_000

            final_agg = combined.groupby('name').agg({
                'dur_ms': ['count', 'sum', 'mean', 'std', 'max'],
            }).reset_index()
            final_agg.columns = ['name', 'count', 'total_ms', 'avg_ms', 'std_ms', 'max_ms']
            final_agg = final_agg.sort_values('avg_ms', ascending=False)

            print(f"\nAggregated over {len(all_children_data)} renderAll events:")
            print(f"{'Name':<45} {'Count':>6} {'Avg(ms)':>9} {'Std':>8} {'Max(ms)':>9}")
            print("-" * 80)

            for _, row in final_agg.head(20).iterrows():
                std_val = row['std_ms'] if not pd.isna(row['std_ms']) else 0
                print(f"{row['name'][:45]:<45} {int(row['count']):>6} {row['avg_ms']:>9.3f} {std_val:>8.3f} {row['max_ms']:>9.3f}")


def main():
    if len(sys.argv) > 1:
        trace_path = sys.argv[1]
    else:
        trace_path = find_latest_trace()
        if not trace_path:
            print("❌ No trace file found. Provide path as argument or run benchmarks first.")
            sys.exit(1)

    if not Path(trace_path).exists():
        print(f"❌ Trace file not found: {trace_path}")
        sys.exit(1)

    analyze_render_pipeline(trace_path)


if __name__ == "__main__":
    main()