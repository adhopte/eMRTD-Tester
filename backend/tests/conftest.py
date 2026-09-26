import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


@pytest.fixture
def client(tmp_path):
    from fastapi.testclient import TestClient

    from app import main
    from app.config import Settings

    settings = Settings(pki_dir=str(tmp_path / "pki"), csca_dir=str(tmp_path / "csca"), require_csca_trust=False)
    main.init_state(settings)
    with TestClient(main.app) as c:
        yield c
