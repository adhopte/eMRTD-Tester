"""Extension point for third-party document authenticity services.

The built-in checks are heuristics. For production-grade genuineness verification
(security feature analysis, template matching against document databases such as
PRADO/Keesing/Regula) implement `AuthenticityProvider` and register it in settings.
"""
from __future__ import annotations

from typing import Protocol

from ..report import Section


class AuthenticityProvider(Protocol):
    name: str

    def verify(self, front: bytes, back: bytes | None, section: Section) -> float:
        """Add checks to `section` and return a score contribution in [0, 1]."""
        ...


PROVIDERS: list[AuthenticityProvider] = []


def register(provider: AuthenticityProvider) -> None:
    PROVIDERS.append(provider)
