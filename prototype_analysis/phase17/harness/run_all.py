#!/usr/bin/env python3
"""Run the Phase 17 offline harness: synthetic scenario traces, then the tests.

Usage (offline; no device required)::

    python prototype_analysis/phase17/harness/run_all.py

Everything printed is a SYNTHETIC model result. It does not reproduce the
tracker or the Fitbit backend.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

HARNESS = Path(__file__).resolve().parent
PHASE17 = HARNESS.parent
if str(PHASE17) not in sys.path:
    sys.path.insert(0, str(PHASE17))

from harness.scenarios import (  # noqa: E402
    s1_bootstrap_fallback,
    s2_resource_policy,
    s3_md_session,
    s4_extended_error,
    s5_hypothesis_predictions,
)

SCENARIOS = (
    s1_bootstrap_fallback,
    s2_resource_policy,
    s3_md_session,
    s4_extended_error,
    s5_hypothesis_predictions,
)

BANNER = (
    "=" * 78
    + "\nPhase 17 offline authentication harness -- ALL OUTPUT IS SYNTHETIC."
    + "\nNo device, phone, tracker, ADB, build, or APK was contacted or modified.\n"
    + "=" * 78
)


def main() -> int:
    print(BANNER)
    for mod in SCENARIOS:
        print()
        print(mod.run())

    print()
    print("=" * 78)
    print("Running harness test suite")
    print("=" * 78)
    loader = unittest.TestLoader()
    suite = loader.discover(str(HARNESS / "tests"), top_level_dir=str(PHASE17))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
