"""Short-lived in-memory issuance sessions holding server-side challenges.

A single-process store is enough for a demo deployment; use Redis or similar when
running several workers.
"""
from __future__ import annotations

import secrets
import threading
import time
from dataclasses import dataclass, field

from .emrtd.chip_auth import CaChallenge


@dataclass
class IssuanceSession:
    id: str
    created: float
    aa_challenge: bytes
    dg14_hash: bytes | None = None
    ca: CaChallenge | None = None
    ca_error: str | None = None
    used: bool = False
    extra: dict = field(default_factory=dict)


class SessionStore:
    def __init__(self, ttl_seconds: int) -> None:
        self._ttl = ttl_seconds
        self._items: dict[str, IssuanceSession] = {}
        self._lock = threading.Lock()

    def create(self) -> IssuanceSession:
        s = IssuanceSession(secrets.token_urlsafe(24), time.time(), secrets.token_bytes(8))
        with self._lock:
            self._purge()
            self._items[s.id] = s
        return s

    def get(self, sid: str) -> IssuanceSession | None:
        with self._lock:
            self._purge()
            return self._items.get(sid)

    def consume(self, sid: str) -> IssuanceSession | None:
        """Fetch a session for final use; each session can issue at most one PID."""
        with self._lock:
            self._purge()
            s = self._items.pop(sid, None)
        return s

    def _purge(self) -> None:
        now = time.time()
        for k in [k for k, v in self._items.items() if now - v.created > self._ttl]:
            del self._items[k]
