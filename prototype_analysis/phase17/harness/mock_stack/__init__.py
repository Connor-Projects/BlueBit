"""Phase 17 offline mock stack.

A pure-Python *model* of the BlueBit/inspire-3 protocol shape:

    gattlink prefix  ->  IPv4  ->  UDP:5684  ->  DTLS-PSK  ->  CoAP

It is an oracle and a regression fixture. It does NOT implement real DTLS
cryptography and does NOT reproduce the tracker firmware or the Fitbit backend.
See ``../README.md`` for exactly what it can and cannot establish.
"""

from __future__ import annotations
