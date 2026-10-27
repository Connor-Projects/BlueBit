"""Phase 17 offline authentication test harness.

A pure-Python oracle + regression fixture that models the *shape* of the
BlueBit <-> Inspire 3 protocol and, most importantly, asks whether the competing
explanations for ``PUT bond/control -> 4.01`` make **different predictions**.

Nothing in this package contacts a device, reads a secret, or reproduces the
Fitbit implementation. See ``README.md``.
"""

from __future__ import annotations
