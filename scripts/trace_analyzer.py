#!/usr/bin/env python3
"""
General Perfetto Trace Analyzer
Usage: source perfetto-env/bin/activate && python trace_analyzer.py [trace_file] [metric_names...]

Examples:
  python trace_analyzer.py trace.perfetto-trace BlurEffect#applyEffect flush
  python trace_analyzer.py trace.perfetto-trace "BlurEffect*" "*"
"""

from perfetto.trace_processor import TraceProcessor
import pandas as pd
import sys
import os
import argparse
from typing import List, Dict, Any, Optional
import json
from datetime import datetime
from pathlib import Path

class TraceAnalyzer:
    def __init__(self, trace_path: str):
        self.trace_path = trace_path
        self.tp = None

    def __enter__(self):
        self.tp = TraceProcessor(trace=self.trace_path)
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        if self.tp:
            self.tp = None

    def extract_metric_stats(self, metric_pattern: str) -> Optional[Dict[str, Any]]:
        """Extract comprehensive statistics for a given metric pattern"""

        if not self.tp:
            return None

        try:
            # Support both exact match and SQL LIKE patterns
            if '*' in metric_pattern or '%' in metric_pattern:
                # Convert * to % for SQL LIKE
                sql_pattern = metric_pattern.replace('*', '%')
                query = f"SELECT name, ts, dur, depth FROM slice WHERE name LIKE '{sql_pattern}' ORDER BY ts"
            else:
                query = f"SELECT name, ts, dur, depth FROM slice WHERE name = '{metric_pattern}' ORDER BY ts"

            result = self.tp.query(query)
            df = result.as_pandas_dataframe()

            if len(df) == 0:
                return {
                    'metric': metric_pattern,
                    'count': 0,
                    'found': False,
                    'error': None
                }

            # Convert duration from nanoseconds to milliseconds
            df['dur_ms'] = df['dur'] / 1_000_000

            # Calculate comprehensive statistics
            stats = {
                'metric': metric_pattern,
                'found': True,
                'count': len(df),
                'duration_ms': {
                    'total': df['dur_ms'].sum(),
                    'average': df['dur_ms'].mean(),
                    'median': df['dur_ms'].median(),
                    'min': df['dur_ms'].min(),
                    'max': df['dur_ms'].max(),
                    'std': df['dur_ms'].std(),
                    'p50': df['dur_ms'].quantile(0.50),
                    'p90': df['dur_ms'].quantile(0.90),
                    'p95': df['dur_ms'].quantile(0.95),
                    'p99': df['dur_ms'].quantile(0.99)
                },
                'timestamp_ns': {
                    'first': df['ts'].min(),
                    'last': df['ts'].max(),
                    'span': df['ts'].max() - df['ts'].min()
                },
                'unique_names': df['name'].nunique(),
                'names': df['name'].unique().tolist() if df['name'].nunique() <= 10 else df['name'].unique()[:10].tolist() + ['...']
            }

            return stats

        except Exception as e:
            return {
                'metric': metric_pattern,
                'count': 0,
                'found': False,
                'error': str(e)
            }

    def extract_metrics(self, metric_patterns: List[str]) -> Dict[str, Any]:
        """Extract multiple metrics and return comprehensive results"""

        results = {
            'trace_file': self.trace_path,
            'metrics': {},
            'summary': {
                'total_metrics_requested': len(metric_patterns),
                'metrics_found': 0,
                'total_events': 0
            }
        }

        for pattern in metric_patterns:
            stats = self.extract_metric_stats(pattern)
            results['metrics'][pattern] = stats

            if stats['found']:
                results['summary']['metrics_found'] += 1
                results['summary']['total_events'] += stats['count']

        return results

    def list_available_metrics(self, limit: int = 50) -> List[str]:
        """List available slice names in the trace"""

        if not self.tp:
            return []

        try:
            query = f"SELECT DISTINCT name FROM slice ORDER BY name LIMIT {limit}"
            result = self.tp.query(query)
            df = result.as_pandas_dataframe()
            return df['name'].tolist()
        except Exception as e:
            print(f"Error listing metrics: {e}")
            return []

def format_results(results: Dict[str, Any], format_type: str = 'table') -> str:
    """Format results for display"""

    if format_type == 'json':
        return json.dumps(results, indent=2)

    output = []
    output.append(f"Trace Analysis Results for: {results['trace_file']}")
    output.append("=" * 80)
    output.append(f"Metrics Found: {results['summary']['metrics_found']}/{results['summary']['total_metrics_requested']}")
    output.append(f"Total Events: {results['summary']['total_events']}")
    output.append("")

    for pattern, stats in results['metrics'].items():
        output.append(f"Metric: {pattern}")

        if not stats['found']:
            output.append(f"  ❌ Not found")
            if stats.get('error'):
                output.append(f"  Error: {stats['error']}")
            output.append("")
            continue

        output.append(f"  ✅ Found {stats['count']} events")

        if stats['unique_names'] > 1:
            output.append(f"  Names: {', '.join(stats['names'])}")

        duration = stats['duration_ms']
        output.append(f"  Duration (ms):")
        output.append(f"    Average: {duration['average']:.3f}")
        output.append(f"    Median:  {duration['median']:.3f}")
        output.append(f"    Min:     {duration['min']:.3f}")
        output.append(f"    Max:     {duration['max']:.3f}")
        output.append(f"    Std:     {duration['std']:.3f}")
        output.append(f"    P50:     {duration['p50']:.3f}")
        output.append(f"    P90:     {duration['p90']:.3f}")
        output.append(f"    P95:     {duration['p95']:.3f}")
        output.append(f"    P99:     {duration['p99']:.3f}")
        output.append(f"    Total:   {duration['total']:.3f}")

        timestamp = stats['timestamp_ns']
        output.append(f"  Timestamp (ns):")
        output.append(f"    Span:    {timestamp['span']:,}")
        output.append("")

    return "\n".join(output)

def parse_benchmark_json(json_path: str) -> Dict[str, Any]:
    """Parse Android Benchmark JSON output and extract key metrics"""

    with open(json_path, 'r') as f:
        data = json.load(f)

    if not data.get('benchmarks'):
        raise ValueError("No benchmarks found in JSON file")

    benchmark = data['benchmarks'][0]

    # Extract key metrics with consistent naming
    json_metrics = {}

    # Frame timing metrics
    for metric_name in ['gfxFrameTime50thPercentileMs', 'gfxFrameTime90thPercentileMs',
                       'gfxFrameTime95thPercentileMs', 'gfxFrameTime99thPercentileMs']:
        if metric_name in benchmark.get('metrics', {}):
            metric_data = benchmark['metrics'][metric_name]
            json_metrics[metric_name] = {
                'median': metric_data.get('median', 0),
                'minimum': metric_data.get('minimum', 0),
                'maximum': metric_data.get('maximum', 0)
            }

    # Frame overrun metrics
    if 'frameOverrunMs' in benchmark.get('metrics', {}):
        overrun_data = benchmark['metrics']['frameOverrunMs']
        json_metrics['frameOverrunMs'] = overrun_data

    # Frame count and jank
    for metric_name in ['frameCount', 'gfxFrameJankPercent', 'gfxFrameTotalCount']:
        if metric_name in benchmark.get('metrics', {}):
            metric_data = benchmark['metrics'][metric_name]
            json_metrics[metric_name] = {
                'median': metric_data.get('median', 0)
            }

    # Custom trace section metrics (those ending with AverageMs)
    custom_metrics = {k: v for k, v in benchmark.get('metrics', {}).items()
                     if 'AverageMs' in k and k not in ['frameDurationCpuMs']}

    for metric_name, metric_data in custom_metrics.items():
        json_metrics[metric_name] = {
            'median': metric_data.get('median', 0),
            'minimum': metric_data.get('minimum', 0),
            'maximum': metric_data.get('maximum', 0)
        }

    return {
        'name': benchmark['name'],
        'className': benchmark.get('className', ''),
        'totalRunTimeNs': benchmark.get('totalRunTimeNs', 0),
        'device_info': data.get('context', {}),
        'metrics': json_metrics
    }

def find_latest_benchmark(results_dir: str = "benchmark/build/outputs/connected_android_test_additional_output") -> Tuple[Optional[str], Optional[str]]:
    """Find the most recent benchmark JSON and trace files"""

    results_path = Path(results_dir)
    if not results_path.exists():
        return None, None

    # Find the most recent benchmark directory
    benchmark_dirs = list(results_path.glob("benchmark/connected/*"))
    if not benchmark_dirs:
        return None, None

    latest_dir = max(benchmark_dirs, key=lambda x: x.stat().st_mtime)

    # Find JSON file
    json_files = list(latest_dir.glob("*-benchmarkData.json"))
    json_file = json_files[0] if json_files else None

    # Find trace file (most recent)
    trace_files = list(latest_dir.glob("*.perfetto-trace"))
    trace_file = max(trace_files, key=lambda x: x.stat().st_mtime) if trace_files else None

    return str(json_file) if json_file else None, str(trace_file) if trace_file else None

def store_results(results: Dict[str, Any], name: str, include_json: bool = False, json_path: str = None) -> str:
    """Store results to JSON file"""

    # Create results directory if it doesn't exist
    results_dir = "benchmark_results"
    os.makedirs(results_dir, exist_ok=True)

    # Create simplified storage format
    simplified_results = {
        "name": name,
        "timestamp": datetime.now().isoformat(),
        "trace_file": os.path.basename(results['trace_file']),
        "metrics": {}
    }

    # Extract key stats for each found metric
    for pattern, stats in results['metrics'].items():
        if stats['found'] and stats['count'] > 0:
            simplified_results["metrics"][pattern] = {
                "count": stats['count'],
                "avg_ms": round(stats['duration_ms']['average'], 3),
                "median_ms": round(stats['duration_ms']['median'], 3),
                "p90_ms": round(stats['duration_ms']['p90'], 3),
                "p95_ms": round(stats['duration_ms']['p95'], 3),
                "p99_ms": round(stats['duration_ms']['p99'], 3),
                "max_ms": round(stats['duration_ms']['max'], 3)
            }

    # Include JSON benchmark metrics if requested
    if include_json and json_path:
        try:
            json_data = parse_benchmark_json(json_path)
            simplified_results["benchmark_json"] = json_data
        except Exception as e:
            print(f"Warning: Could not parse benchmark JSON: {e}")

    # Save to file
    output_file = os.path.join(results_dir, f"{name}.json")
    with open(output_file, 'w') as f:
        json.dump(simplified_results, f, indent=2)

    return output_file

def main():
    parser = argparse.ArgumentParser(
        description='Analyze Perfetto traces for custom metrics',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Extract specific metrics
  python trace_analyzer.py trace.perfetto-trace BlurEffect#applyEffect flush

  # Use wildcards for pattern matching
  python trace_analyzer.py trace.perfetto-trace "BlurEffect*" "*flush*"

  # List all available metrics
  python trace_analyzer.py trace.perfetto-trace --list

  # Export results as JSON
  python trace_analyzer.py trace.perfetto-trace BlurEffect --format json

  # Store results for later comparison
  python trace_analyzer.py trace.perfetto-trace BlurEffect#applyEffect --store baseline
        """
    )

    parser.add_argument('trace_file', nargs='?', help='Path to the Perfetto trace file (optional with --latest)')
    parser.add_argument('metrics', nargs='*', help='Metric names or patterns to extract')
    parser.add_argument('--list', '-l', action='store_true', help='List available metrics in trace')
    parser.add_argument('--format', '-f', choices=['table', 'json'], default='table',
                       help='Output format (default: table)')
    parser.add_argument('--limit', type=int, default=50,
                       help='Limit for listing metrics (default: 50)')
    parser.add_argument('--store', '-s', metavar='NAME', help='Store results with given name')
    parser.add_argument('--latest', action='store_true', help='Analyze most recent benchmark results (auto-finds JSON and trace)')
    parser.add_argument('--include-json', action='store_true', help='Include benchmark JSON metrics in stored results')

    args = parser.parse_args()

    # Handle --latest mode
    if args.latest:
        json_path, trace_path = find_latest_benchmark()
        if not trace_path:
            print("❌ No recent benchmark trace file found")
            sys.exit(1)
        args.trace_file = trace_path
        print(f"🔍 Using latest trace: {trace_path}")
        if json_path:
            print(f"📊 Using latest JSON: {json_path}")
        else:
            print("⚠️  No benchmark JSON file found")
            json_path = None

    if not os.path.exists(args.trace_file):
        print(f"❌ Trace file not found: {args.trace_file}")
        sys.exit(1)

    if not args.metrics and not args.list:
        print("❌ No metrics specified. Use --list to see available metrics or provide metric names.")
        sys.exit(1)

    try:
        with TraceAnalyzer(args.trace_file) as analyzer:

            if args.list:
                print(f"Available metrics in {args.trace_file}:")
                print("=" * 50)
                metrics = analyzer.list_available_metrics(args.limit)
                for i, metric in enumerate(metrics, 1):
                    print(f"{i:3d}. {metric}")

                if len(metrics) == args.limit:
                    print(f"\nShowing first {args.limit} metrics. Use --limit N for more.")
                return

            results = analyzer.extract_metrics(args.metrics)

            # Store results if requested
            if args.store:
                json_path_for_storage = json_path if args.latest and args.include_json else None
                stored_file = store_results(results, args.store, args.include_json, json_path_for_storage)
                print(f"✅ Stored results to: {stored_file}")

            # Display results
            output = format_results(results, args.format)
            print(output)

    except Exception as e:
        print(f"❌ Error analyzing trace: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)

if __name__ == "__main__":
    main()