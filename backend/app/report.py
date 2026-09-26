"""Verification report primitives shared by the eMRTD and document-image pipelines."""
from __future__ import annotations

from dataclasses import asdict, dataclass, field
from enum import Enum


class Status(str, Enum):
    PASS = "pass"
    FAIL = "fail"
    WARN = "warn"
    SKIPPED = "skipped"


@dataclass
class Check:
    name: str
    status: Status
    detail: str = ""
    data: dict | None = None


@dataclass
class Section:
    """A group of checks, e.g. passive authentication."""
    name: str
    checks: list[Check] = field(default_factory=list)

    def add(self, name: str, status: Status, detail: str = "", data: dict | None = None) -> Check:
        c = Check(name, status, detail, data)
        self.checks.append(c)
        return c

    def passed(self, name: str, detail: str = "", data: dict | None = None) -> Check:
        return self.add(name, Status.PASS, detail, data)

    def failed(self, name: str, detail: str = "", data: dict | None = None) -> Check:
        return self.add(name, Status.FAIL, detail, data)

    def warn(self, name: str, detail: str = "", data: dict | None = None) -> Check:
        return self.add(name, Status.WARN, detail, data)

    def skipped(self, name: str, detail: str = "") -> Check:
        return self.add(name, Status.SKIPPED, detail)

    @property
    def status(self) -> Status:
        statuses = {c.status for c in self.checks}
        if Status.FAIL in statuses:
            return Status.FAIL
        if not statuses or statuses == {Status.SKIPPED}:
            return Status.SKIPPED
        if Status.WARN in statuses:
            return Status.WARN
        return Status.PASS

    def to_dict(self) -> dict:
        return {
            "name": self.name,
            "status": self.status.value,
            "checks": [{**asdict(c), "status": c.status.value} for c in self.checks],
        }
