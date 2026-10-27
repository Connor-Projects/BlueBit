"""FIFO / one-outstanding-write serializer *model* (Python analog).

Mirrors the invariant enforced by
``BlueBitApp/.../commonMain/.../diagnostics/GattlinkTxSerializer.kt`` (Phase 14):
Android's GATT client permits one outstanding request, so the next frame may be
submitted only from the previous write's completion callback. A refused
submission or a non-success completion must be reported, never hidden.

This is a model used by the Python suite; the authoritative test is the JVM
``GattlinkTxSerializerTest`` against the real class.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable, List, Optional, Tuple


@dataclass(frozen=True)
class PendingWrite:
    frame: bytes
    seq: int


class SerialTxSink:
    def __init__(self, submit: Callable[[bytes], None]) -> None:
        self._submit = submit
        self._queue: List[bytes] = []
        self._pending: Optional[PendingWrite] = None
        self._seq = 0
        self.submitted: List[bytes] = []
        self.failures: List[Tuple[bytes, str]] = []

    @property
    def pending(self) -> Optional[PendingWrite]:
        return self._pending

    def enqueue(self, frame: bytes) -> None:
        self._queue.append(frame)
        self._pump()

    def _pump(self) -> None:
        if self._pending is not None or not self._queue:
            return
        frame = self._queue.pop(0)
        seq = self._seq
        self._seq += 1
        self._pending = PendingWrite(frame, seq)
        try:
            self._submit(frame)
        except Exception as exc:  # platform refusal
            self._pending = None
            self.failures.append((frame, f"submit-refused:{exc}"))
            return
        self.submitted.append(frame)

    def complete(self, ok: bool = True) -> None:
        pending = self._pending
        self._pending = None
        if pending is not None and not ok:
            self.failures.append((pending.frame, "completion-failed"))
        self._pump()
