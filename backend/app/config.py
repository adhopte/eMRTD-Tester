from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="PID_", env_file=".env", extra="ignore")

    public_base_url: str = "http://localhost:8000"
    data_dir: str = "data"
    pki_dir: str = "data/pki"
    csca_dir: str = "data/csca"

    issuer_country: str = "EU"
    issuer_organization: str = "IN Groupe"
    issuing_authority: str = "IN Groupe Issuer (TEST)"
    pid_max_validity_days: int = 365

    # --- eMRTD issuance policy ---
    # Require the DSC to chain to a configured CSCA. Keep False only for testing with
    # passports whose CSCA you have not imported.
    require_csca_trust: bool = False
    # Require a server-verified clone-detection proof (AA or CA) when the chip supports it
    require_chip_genuineness: bool = True
    session_ttl_seconds: int = 300

    # --- Document image policy ---
    scan_min_score: float = 0.75
    scan_allow_specimen: bool = False


@lru_cache
def get_settings() -> Settings:
    return Settings()
