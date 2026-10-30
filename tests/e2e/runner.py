#!/usr/bin/env python3
"""E2E Test Suite Runner for Standalone GBA/GB/GBC Android Emulator.
Executes opaque-box test suites across Tiers 1-4, reports ANSI metrics,
and exports JSON test reports.
"""

import argparse
import json
import os
import sys
import time
import unittest
from typing import Dict, List, Optional

# Ensure project root is in sys.path
PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
if PROJECT_ROOT not in sys.path:
    sys.path.insert(0, PROJECT_ROOT)


class AnsiColors:
    GREEN = "\033[92m"
    RED = "\033[91m"
    YELLOW = "\033[93m"
    CYAN = "\033[96m"
    BOLD = "\033[1m"
    RESET = "\033[0m"


class JsonTestResult(unittest.TextTestResult):
    """Custom test result collector capturing timing and outcome per test."""

    def __init__(self, stream, descriptions, verbosity):
        super().__init__(stream, descriptions, verbosity)
        self.test_records: List[Dict[str, any]] = []
        self._start_time = 0

    def startTest(self, test):
        super().startTest(test)
        self._start_time = time.perf_counter()

    def addSuccess(self, test):
        super().addSuccess(test)
        duration = time.perf_counter() - self._start_time
        self.test_records.append({
            "test_id": test.id(),
            "status": "PASS",
            "duration_sec": round(duration, 5),
            "error": None
        })

    def addFailure(self, test, err):
        super().addFailure(test, err)
        duration = time.perf_counter() - self._start_time
        self.test_records.append({
            "test_id": test.id(),
            "status": "FAIL",
            "duration_sec": round(duration, 5),
            "error": self._exc_info_to_string(err, test)
        })

    def addError(self, test, err):
        super().addError(test, err)
        duration = time.perf_counter() - self._start_time
        self.test_records.append({
            "test_id": test.id(),
            "status": "ERROR",
            "duration_sec": round(duration, 5),
            "error": self._exc_info_to_string(err, test)
        })


def discover_suite(tier: Optional[int] = None, feature: Optional[str] = None) -> unittest.TestSuite:
    """Discovers test suites based on optional tier and feature filters."""
    loader = unittest.TestLoader()
    base_dir = os.path.dirname(__file__)

    tier_dirs = {
        1: os.path.join(base_dir, "tier1_happy_path"),
        2: os.path.join(base_dir, "tier2_boundary_edge"),
        3: os.path.join(base_dir, "tier3_interactions"),
        4: os.path.join(base_dir, "tier4_scenarios"),
    }

    selected_dirs = [tier_dirs[tier]] if tier else list(tier_dirs.values())

    combined_suite = unittest.TestSuite()
    pattern = "test_*.py"

    for d in selected_dirs:
        if os.path.exists(d):
            suite = loader.discover(start_dir=d, pattern=pattern, top_level_dir=PROJECT_ROOT)
            combined_suite.addTests(suite)

    # Filter by feature if requested
    if feature:
        feature_clean = feature.upper()
        filtered_suite = unittest.TestSuite()
        for test in _flatten_suite(combined_suite):
            if feature_clean.lower() in test.id().lower():
                filtered_suite.addTest(test)
        return filtered_suite

    return combined_suite


def _flatten_suite(suite):
    for item in suite:
        if isinstance(item, unittest.TestSuite):
            yield from _flatten_suite(item)
        else:
            yield item


def print_summary_table(
    total: int,
    passed: int,
    failed: int,
    errors: int,
    elapsed: float,
    tier_counts: Dict[str, int]
):
    """Prints a beautiful summary table to stdout."""
    c = AnsiColors
    status_str = f"{c.GREEN}ALL PASSED{c.RESET}" if (failed == 0 and errors == 0) else f"{c.RED}FAILED{c.RESET}"

    print(f"\n{c.BOLD}======================================================================{c.RESET}")
    print(f"{c.BOLD}{c.CYAN}       E2E TEST EXECUTION SUMMARY (GBA/GB/GBC EMULATOR){c.RESET}")
    print(f"{c.BOLD}======================================================================{c.RESET}")
    print(f"  Overall Status:        {status_str}")
    print(f"  Total Tests Executed:  {c.BOLD}{total}{c.RESET}")
    print(f"  Passed:                {c.GREEN}{passed}{c.RESET}")
    print(f"  Failed:                {c.RED}{failed}{c.RESET}")
    print(f"  Errors:                {c.RED}{errors}{c.RESET}")
    print(f"  Total Elapsed Time:    {elapsed:.3f} seconds")
    print(f"----------------------------------------------------------------------")
    print(f"  Tier Breakdown:")
    for tier_name, count in tier_counts.items():
        print(f"    - {tier_name:24}: {c.CYAN}{count}{c.RESET} tests")
    print(f"{c.BOLD}======================================================================{c.RESET}\n")


def main():
    parser = argparse.ArgumentParser(description="Run E2E tests for Android mGBA emulator.")
    parser.add_argument("--tier", type=int, choices=[1, 2, 3, 4], help="Run specific tier only")
    parser.add_argument("--feature", type=str, help="Filter by feature ID (e.g. F01, F04)")
    parser.add_argument("--report", type=str, help="Export JSON test report to path")
    parser.add_argument("-v", "--verbose", action="store_true", help="Verbose test logging")
    args = parser.parse_args()

    verbosity = 2 if args.verbose else 1
    suite = discover_suite(tier=args.tier, feature=args.feature)

    start_time = time.perf_counter()
    runner = unittest.TextTestRunner(resultclass=JsonTestResult, verbosity=verbosity)
    result = runner.run(suite)
    elapsed = time.perf_counter() - start_time

    # Calculate tier breakdown
    tier_counts = {"Tier 1 (Happy-Path)": 0, "Tier 2 (Boundary/Edge)": 0, "Tier 3 (Interactions)": 0, "Tier 4 (Scenarios)": 0}
    for rec in getattr(result, "test_records", []):
        tid = rec["test_id"]
        if "tier1_happy_path" in tid:
            tier_counts["Tier 1 (Happy-Path)"] += 1
        elif "tier2_boundary_edge" in tid:
            tier_counts["Tier 2 (Boundary/Edge)"] += 1
        elif "tier3_interactions" in tid:
            tier_counts["Tier 3 (Interactions)"] += 1
        elif "tier4_scenarios" in tid:
            tier_counts["Tier 4 (Scenarios)"] += 1

    total_run = result.testsRun
    total_passed = total_run - len(result.failures) - len(result.errors)
    total_failed = len(result.failures)
    total_errors = len(result.errors)

    print_summary_table(total_run, total_passed, total_failed, total_errors, elapsed, tier_counts)

    # Export JSON report if requested
    if args.report:
        report_data = {
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "total": total_run,
            "passed": total_passed,
            "failed": total_failed,
            "errors": total_errors,
            "elapsed_sec": round(elapsed, 4),
            "tier_counts": tier_counts,
            "tests": getattr(result, "test_records", [])
        }
        with open(args.report, "w", encoding="utf-8") as f:
            json.dump(report_data, f, indent=2)
        print(f"JSON report saved to: {args.report}")

    sys.exit(0 if result.wasSuccessful() else 1)


if __name__ == "__main__":
    main()
