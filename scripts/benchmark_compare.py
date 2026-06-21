#!/usr/bin/env python3
"""
Compare stored benchmark results
Usage: python benchmark_compare.py baseline.json optimization.json

Examples:
  python benchmark_compare.py benchmark_results/baseline.json benchmark_results/optimization.json
"""

import json
import sys
import os
import argparse

def load_results(file_path):
    """Load benchmark results from JSON file"""
    try:
        with open(file_path, 'r') as f:
            return json.load(f)
    except Exception as e:
        print(f"❌ Error loading {file_path}: {e}")
        return None

class BenchmarkCompare:
    def __init__(self):
        self.improvements = []
        self.regressions = []

    def compare_trace_metrics(self, baseline, optimization):
        """Compare trace metrics between two benchmark results"""
        baseline_metrics = baseline.get('metrics', {})
        opt_metrics = optimization.get('metrics', {})

        # Find all metrics present in either result
        all_metrics = set(baseline_metrics.keys()) | set(opt_metrics.keys())

        print("🔍 TRACE METRICS COMPARISON")
        print("=" * 50)

        for metric in sorted(all_metrics):
            baseline_data = baseline_metrics.get(metric)
            opt_data = opt_metrics.get(metric)

            print(f"\n📊 {metric}")

            if baseline_data and opt_data:
                # Both have data - compare
                baseline_avg = baseline_data['avg_ms']
                opt_avg = opt_data['avg_ms']

                if baseline_avg > 0:
                    change_percent = ((opt_avg - baseline_avg) / baseline_avg) * 100
                    change_str = f"{change_percent:+.1f}%"

                    if change_percent < -1:
                        self.improvements.append(("trace", metric, change_percent, baseline_avg, opt_avg))
                        status = "✅ IMPROVED"
                    elif change_percent > 1:
                        self.regressions.append(("trace", metric, change_percent, baseline_avg, opt_avg))
                        status = "❌ REGRESSED"
                    else:
                        status = "➖ NEUTRAL"

                    print(f"   Baseline:  {baseline_avg:.3f}ms")
                    print(f"   Opt:       {opt_avg:.3f}ms")
                    print(f"   Change:    {change_str} {status}")
                else:
                    print(f"   Baseline:  {baseline_avg:.3f}ms")
                    print(f"   Opt:       {opt_avg:.3f}ms")
                    print(f"   Change:    N/A (baseline was ~0)")

            elif baseline_data:
                print(f"   ⚠️  Missing in optimization")
                print(f"   Baseline:  {baseline_data['avg_ms']:.3f}ms")
            elif opt_data:
                print(f"   ✅ New metric in optimization")
                print(f"   Opt:       {opt_data['avg_ms']:.3f}ms")

    def compare_json_metrics(self, baseline_json, opt_json, baseline_label="Baseline", opt_label="Optimization"):
        """Compare JSON benchmark metrics"""
        if not baseline_json or not opt_json:
            print("\n⚠️  JSON benchmark data not available for comparison")
            return

        baseline_metrics = baseline_json.get('metrics', {})
        opt_metrics = opt_json.get('metrics', {})

        # Find all metrics present in either result
        all_metrics = set(baseline_metrics.keys()) | set(opt_metrics.keys())

        print("\n📈 FRAME PERFORMANCE METRICS COMPARISON")
        print("=" * 50)

        for metric in sorted(all_metrics):
            # Skip trace section metrics here - they're already handled
            if 'AverageMs' in metric:
                continue

            baseline_data = baseline_metrics.get(metric)
            opt_data = opt_metrics.get(metric)

            print(f"\n🎯 {metric}")

            if baseline_data and opt_data:
                # Handle different metric types
                if 'median' in baseline_data:
                    baseline_val = baseline_data['median']
                    opt_val = opt_data.get('median', 0)
                    unit = "ms" if "Time" in metric else ("%" if "jank" in metric or "Percent" in metric else "frames")
                else:
                    # Frame overrun metrics with percentiles
                    if isinstance(baseline_data, dict) and 'P50' in baseline_data:
                        baseline_val = baseline_data['P50']
                        opt_val = opt_data.get('P50', 0)
                        unit = "ms"
                    else:
                        baseline_val = 0
                        opt_val = 0
                        unit = "unknown"

                # For frame time metrics, lower is better
                # For frame count, higher might be better
                # For jank percentage, lower is better
                lower_is_better = "Time" in metric or "jank" in metric or "Overrun" in metric

                if baseline_val != 0:
                    change_percent = ((opt_val - baseline_val) / abs(baseline_val)) * 100
                    change_str = f"{change_percent:+.1f}%"

                    if lower_is_better:
                        if change_percent < -1:
                            self.improvements.append(("json", metric, change_percent, baseline_val, opt_val))
                            status = "✅ IMPROVED"
                        elif change_percent > 1:
                            self.regressions.append(("json", metric, change_percent, baseline_val, opt_val))
                            status = "❌ REGRESSED"
                        else:
                            status = "➖ NEUTRAL"
                    else:
                        # Higher is better (like frame count)
                        if change_percent > 1:
                            self.improvements.append(("json", metric, change_percent, baseline_val, opt_val))
                            status = "✅ IMPROVED"
                        elif change_percent < -1:
                            self.regressions.append(("json", metric, change_percent, baseline_val, opt_val))
                            status = "❌ REGRESSED"
                        else:
                            status = "➖ NEUTRAL"

                    print(f"   {baseline_label}: {baseline_val:.1f}{unit}")
                    print(f"   {opt_label}:     {opt_val:.1f}{unit}")
                    print(f"   Change:        {change_str} {status}")
                else:
                    print(f"   {baseline_label}: {baseline_val:.1f}{unit}")
                    print(f"   {opt_label}:     {opt_val:.1f}{unit}")
                    print(f"   Change:        N/A (baseline was 0)")

            elif baseline_data:
                val = baseline_data.get('median', baseline_data.get('P50', 0))
                unit = "ms" if "Time" in metric else ("%" if "jank" in metric else "frames")
                print(f"   ⚠️  Missing in {opt_label}")
                print(f"   {baseline_label}: {val:.1f}{unit}")
            elif opt_data:
                val = opt_data.get('median', opt_data.get('P50', 0))
                unit = "ms" if "Time" in metric else ("%" if "jank" in metric else "frames")
                print(f"   ✅ New metric in {opt_label}")
                print(f"   {opt_label}: {val:.1f}{unit}")

        # Compare custom trace section metrics from JSON
        custom_metrics = [k for k in all_metrics if 'AverageMs' in k]
        if custom_metrics:
            print("\n⚡ CUSTOM TRACE SECTIONS COMPARISON")
            print("=" * 40)

            for metric in sorted(custom_metrics):
                baseline_data = baseline_metrics.get(metric)
                opt_data = opt_metrics.get(metric)

                print(f"\n⚡ {metric}")

                if baseline_data and opt_data:
                    baseline_val = baseline_data.get('median', 0)
                    opt_val = opt_data.get('median', 0)

                    if baseline_val > 0:
                        change_percent = ((opt_val - baseline_val) / baseline_val) * 100
                        change_str = f"{change_percent:+.1f}%"

                        if change_percent < -1:
                            self.improvements.append(("json", metric, change_percent, baseline_val, opt_val))
                            status = "✅ IMPROVED"
                        elif change_percent > 1:
                            self.regressions.append(("json", metric, change_percent, baseline_val, opt_val))
                            status = "❌ REGRESSED"
                        else:
                            status = "➖ NEUTRAL"

                        print(f"   {baseline_label}: {baseline_val:.3f}ms")
                        print(f"   {opt_label}:     {opt_val:.3f}ms")
                        print(f"   Change:        {change_str} {status}")

    def print_summary(self):
        """Print comparison summary"""
        print("\n📊 COMPARISON SUMMARY")
        print("=" * 50)

        # Group improvements and regressions by type
        trace_improvements = [i for i in self.improvements if i[0] == "trace"]
        json_improvements = [i for i in self.improvements if i[0] == "json"]
        trace_regressions = [r for r in self.regressions if r[0] == "trace"]
        json_regressions = [r for r in self.regressions if r[0] == "json"]

        if trace_improvements:
            print(f"✅ Trace Improvements: {len(trace_improvements)}")
            for _, metric, change, baseline_val, opt_val in trace_improvements:
                print(f"   {metric}: {baseline_val:.3f}ms → {opt_val:.3f}ms ({change:+.1f}%)")

        if json_improvements:
            print(f"📈 Performance Improvements: {len(json_improvements)}")
            for _, metric, change, baseline_val, opt_val in json_improvements:
                print(f"   {metric}: {baseline_val:.1f} → {opt_val:.1f} ({change:+.1f}%)")

        if trace_regressions:
            print(f"❌ Trace Regressions: {len(trace_regressions)}")
            for _, metric, change, baseline_val, opt_val in trace_regressions:
                print(f"   {metric}: {baseline_val:.3f}ms → {opt_val:.3f}ms ({change:+.1f}%)")

        if json_regressions:
            print(f"⚠️  Performance Regressions: {len(json_regressions)}")
            for _, metric, change, baseline_val, opt_val in json_regressions:
                print(f"   {metric}: {baseline_val:.1f} → {opt_val:.1f} ({change:+.1f}%)")

        if not self.improvements and not self.regressions:
            print("   No significant changes detected")

        total_improvements = len(self.improvements)
        total_regressions = len(self.regressions)

        print(f"\n🎯 Overall: {total_improvements} improvements, {total_regressions} regressions")

        return total_improvements, total_regressions

def compare_metrics(baseline, optimization):
    """Legacy function for backward compatibility"""
    comparator = BenchmarkCompare()
    comparator.compare_trace_metrics(baseline, optimization)
    return comparator.print_summary()

def main():
    parser = argparse.ArgumentParser(description='Compare benchmark results')
    parser.add_argument('baseline', help='Baseline results file')
    parser.add_argument('optimization', help='Optimization results file')

    args = parser.parse_args()

    # Check if files exist
    for file_path in [args.baseline, args.optimization]:
        if not os.path.exists(file_path):
            print(f"❌ File not found: {file_path}")
            sys.exit(1)

    # Load results
    baseline = load_results(args.baseline)
    optimization = load_results(args.optimization)

    if not baseline or not optimization:
        sys.exit(1)

    # Print header
    print(f"📊 Comparing '{baseline.get('name', 'Baseline')}' vs '{optimization.get('name', 'Optimization')}'")
    print("=" * 80)
    print(f"Baseline:   {baseline.get('timestamp', 'N/A')}")
    print(f"Optimization: {optimization.get('timestamp', 'N/A')}")
    print()

    # Use enhanced comparison
    comparator = BenchmarkCompare()

    # Compare trace metrics
    comparator.compare_trace_metrics(baseline, optimization)

    # Compare JSON metrics if available
    baseline_json = baseline.get('benchmark_json')
    opt_json = optimization.get('benchmark_json')
    comparator.compare_json_metrics(baseline_json, opt_json,
                                   baseline.get('name', 'Baseline'),
                                   optimization.get('name', 'Optimization'))

    # Print summary and get counts
    improvements, regressions = comparator.print_summary()

    # Exit with appropriate code
    if regressions > 0:
        sys.exit(1)  # Regressions detected
    elif improvements > 0:
        sys.exit(0)  # Improvements detected
    else:
        sys.exit(0)  # No changes

if __name__ == "__main__":
    main()