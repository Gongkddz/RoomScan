from fastapi.testclient import TestClient
from roomscan.api import create_app
from roomscan.dashboard import create_dashboard


def dashboard(tmp_path):
    identity = {"server_id": "server", "fingerprint": "a" * 64}
    companion = create_app(tmp_path, identity, "123456")
    app = create_dashboard(companion, identity, "https://192.168.1.2:8743", 8744)
    return TestClient(app), TestClient(companion), companion


def test_local_pairing_requires_origin_and_rejects_rebinding(tmp_path):
    local, _, _ = dashboard(tmp_path)
    assert local.get("/", headers={"host": "evil.example"}).status_code == 403
    assert local.post("/api/pairing").status_code == 403
    assert local.post("/api/pairing", headers={"origin": "https://evil.example"}).status_code == 403
    assert local.post("/api/pairing", headers={"origin": "http://127.0.0.1:8744"}).status_code == 200


def test_qr_expires_after_pairing_and_refreshes_explicitly(tmp_path):
    local, api, app = dashboard(tmp_path)
    response = local.get("/pairing.svg")
    assert response.status_code == 200 and "image/svg+xml" in response.headers["content-type"]
    assert response.headers["cache-control"] == "no-store"
    assert api.post("/v1/pair", json={"code": "123456", "name": "phone"}).status_code == 200
    assert local.get("/pairing.svg").status_code == 410
    local.post("/api/pairing", headers={"origin": "http://127.0.0.1:8744"})
    assert local.get("/pairing.svg").status_code == 200
    assert api.post("/v1/pair", json={"code": app.state.pairing_code, "name": "second"}).status_code == 200


def test_dashboard_status_never_exposes_credentials(tmp_path):
    local, api, _ = dashboard(tmp_path)
    token = api.post("/v1/pair", json={"code": "123456", "name": "phone"}).json()["token"]
    status = local.get("/api/status")
    assert status.json()["devices"] == [{"name": "phone"}]
    assert token not in status.text and "123456" not in status.text
