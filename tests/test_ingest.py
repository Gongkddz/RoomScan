import hashlib

import pytest
from fastapi.testclient import TestClient

from roomscan.api import MAX_CHUNK, create_app


@pytest.fixture
def context(tmp_path):
    app = create_app(tmp_path, {"server_id": "test-server"}, "123456")
    client = TestClient(app)
    response = client.post("/v1/pair", json={"code": "123456", "name": "test phone"})
    assert response.status_code == 200
    headers = {"Authorization": "Bearer " + response.json()["token"]}
    return client, headers, app


def project(client, headers, data=b"capture-data"):
    pid = client.post("/v1/projects", headers=headers, json={"name": "capture"}).json()["project_id"]
    sha = hashlib.sha256(data).hexdigest()
    manifest = {"objects": [{"sha256": sha, "size": len(data), "kind": "image"}]}
    assert client.put(f"/v1/projects/{pid}/manifest", headers=headers, json=manifest).status_code == 200
    return pid, sha


def test_requires_pairing(context):
    client, _, _ = context
    assert client.get("/v1/projects").status_code == 401
    assert client.get("/v1/health").json()["training_ready"] is False


def test_pairing_is_single_use(context):
    client, _, _ = context
    assert client.post("/v1/pair", json={"code": "123456", "name": "other"}).status_code == 403


def test_resume_integrity_and_idempotent_job(context):
    client, headers, _ = context
    data = b"capture-data"
    pid, sha = project(client, headers, data)
    base = f"/v1/projects/{pid}"
    assert client.post(base + "/jobs", headers=headers).status_code == 409
    assert client.put(base + f"/objects/{sha}", headers=headers, content=data[:5]).json()["offset"] == 5
    assert client.put(base + f"/objects/{sha}", headers=headers, content=data[5:]).status_code == 409
    assert client.get(base + "/objects", headers=headers).json()["objects"][0]["offset"] == 5
    assert client.put(base + f"/objects/{sha}?offset=5", headers=headers, content=data[5:]).json()["complete"]
    assert client.put(base + f"/objects/{sha}", headers=headers, content=b"ignored").json()["complete"]
    first = client.post(base + "/jobs", headers=headers).json()
    second = client.post(base + "/jobs", headers=headers).json()
    assert first["id"] == second["id"]
    assert first["state"] == "waiting_engine"


def test_corrupt_object_restarts_cleanly(context):
    client, headers, _ = context
    pid, sha = project(client, headers)
    url = f"/v1/projects/{pid}/objects/{sha}"
    assert client.put(url, headers=headers, content=b"badbadbadbad").status_code == 422
    assert client.get(f"/v1/projects/{pid}/objects", headers=headers).json()["objects"][0]["offset"] == 0


def test_isolation(context):
    client, headers, app = context
    pid, sha = project(client, headers)
    app.state.pairing_expires = float("inf")
    token = client.post("/v1/pair", json={"code": "123456", "name": "second"}).json()["token"]
    other = {"Authorization": "Bearer " + token}
    assert client.get(f"/v1/projects/{pid}/objects", headers=other).status_code == 404
    assert client.put(f"/v1/projects/{pid}/objects/{sha}", headers=other, content=b"x").status_code == 404


def test_budget_and_immutable_manifest(context):
    client, headers, _ = context
    pid, _ = project(client, headers)
    different = {"objects": [{"sha256": "f" * 64, "size": 1, "kind": "poses"}]}
    assert client.put(f"/v1/projects/{pid}/manifest", headers=headers, json=different).status_code == 409
    different["objects"][0]["size"] = 100 * 1024 * 1024
    assert client.put(f"/v1/projects/{pid}/manifest", headers=headers, json=different).status_code == 422


def test_oversized_chunk_rolls_back(context):
    client, headers, _ = context
    data = b"x" * (MAX_CHUNK + 1)
    pid, sha = project(client, headers, data)
    assert client.put(f"/v1/projects/{pid}/objects/{sha}", headers=headers, content=data).status_code == 413
    assert client.get(f"/v1/projects/{pid}/objects", headers=headers).json()["objects"][0]["offset"] == 0


def test_restart_keeps_tokens_and_partial_input(context, tmp_path):
    client, headers, _ = context
    pid, sha = project(client, headers)
    client.put(f"/v1/projects/{pid}/objects/{sha}", headers=headers, content=b"cap")
    restarted = TestClient(create_app(tmp_path, {"server_id": "test-server"}))
    assert restarted.get(f"/v1/projects/{pid}/objects", headers=headers).json()["objects"][0]["offset"] == 3

