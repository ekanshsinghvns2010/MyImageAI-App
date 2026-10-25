import base64

from fastapi.testclient import TestClient

from app.adapters.custom import MyImageAIAdapter
from app.adapters.mock import MockAdapter
from app.main import create_app

BODY = {"model_id": "m1", "prompt": "a fox", "width": 128, "height": 128, "steps": 5, "seed": 3, "num_images": 2}


def client(adapter, token=None):
    return TestClient(create_app(adapter=adapter, token=token, model_id="m1", model_path=None))


def test_mock_generates_labelled_simulated_pngs():
    with client(MockAdapter()) as c:
        assert c.get("/v1/health").json()["simulated"] is True
        r = c.post("/v1/generate", json=BODY)
        assert r.status_code == 200
        data = r.json()
        assert data["simulated"] is True and len(data["images"]) == 2
        assert base64.b64decode(data["images"][0]["data_base64"])[:8] == b"\x89PNG\r\n\x1a\n"


def test_token_is_enforced():
    with client(MockAdapter(), token="secret-token") as c:
        assert c.post("/v1/generate", json=BODY).status_code == 401
        assert c.post("/v1/generate", json=BODY, headers={"Authorization": "Bearer wrong"}).status_code == 401
        assert c.post("/v1/generate", json=BODY, headers={"Authorization": "Bearer secret-token"}).status_code == 200


def test_unimplemented_custom_adapter_reports_not_loaded_and_503():
    with client(MyImageAIAdapter()) as c:
        assert c.get("/v1/health").json()["model_loaded"] is False
        assert c.post("/v1/generate", json=BODY).status_code == 503


def test_unknown_model_id_rejected():
    with client(MockAdapter()) as c:
        r = c.post("/v1/generate", json={**BODY, "model_id": "other"})
        assert r.status_code == 422
