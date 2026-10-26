import hashlib
import json
from pathlib import Path

from fastapi.testclient import TestClient

from roomscan.api import create_app
from roomscan.worker import Worker


def ready_scene(tmp_path):
    app = create_app(tmp_path, {"server_id": "test"}, "123456", training_enabled=True)
    client = TestClient(app)
    token = client.post("/v1/pair", json={"code": "123456", "name": "phone"}).json()["token"]
    headers = {"Authorization": "Bearer " + token}
    project = client.post("/v1/projects", headers=headers, json={"name": "scan"}).json()["project_id"]
    data = b"input"
    sha = hashlib.sha256(data).hexdigest()
    client.put(f"/v1/projects/{project}/manifest", headers=headers, json={"objects": [{"sha256": sha, "size": 5, "kind": "image"}]})
    client.put(f"/v1/projects/{project}/objects/{sha}", headers=headers, content=data)
    job = client.post(f"/v1/projects/{project}/jobs", headers=headers).json()
    output = tmp_path / "jobs" / job["id"]
    output.mkdir(parents=True, exist_ok=True)
    model = b"ply\n" + b"x" * 1000
    (output / "scene.ply").write_bytes(model)
    (output / "result.json").write_text(json.dumps({"model": "scene.ply", "model_sha256": hashlib.sha256(model).hexdigest(), "model_bytes": len(model), "view": {"scale": "arbitrary"}}))
    return app, client, headers, job, model


def test_existing_verified_result_is_reused_and_range_download_is_authenticated(tmp_path, monkeypatch):
    app, client, headers, job, data = ready_scene(tmp_path)
    monkeypatch.setattr("roomscan.worker.subprocess.Popen", lambda *args, **kwargs: (_ for _ in ()).throw(AssertionError("Must not train twice")))
    Worker(app.state.store, Path("unused-brush")).execute(job)
    assert client.get(f"/v1/jobs/{job['id']}", headers=headers).json()["state"] == "ready"
    assert client.get(f"/v1/jobs/{job['id']}/model").status_code == 401
    response = client.get(f"/v1/jobs/{job['id']}/model", headers={**headers, "Range": "bytes=10-19"})
    assert response.status_code == 206 and response.content == data[10:20]
    assert response.headers["content-range"] == f"bytes 10-19/{len(data)}"
    assert client.get("/v1/sync", headers=headers).json()["jobs"][0]["model"]["bytes"] == len(data)


def test_corrupt_cached_result_cannot_be_published(tmp_path):
    app, client, headers, job, _ = ready_scene(tmp_path)
    (tmp_path / "jobs" / job["id"] / "scene.ply").write_bytes(b"tampered")
    Worker(app.state.store, Path("unused-brush")).execute(job)
    assert client.get(f"/v1/jobs/{job['id']}", headers=headers).json()["state"] == "failed"
    assert client.get(f"/v1/jobs/{job['id']}/model", headers=headers).status_code == 409


def test_quality_upgrade_creates_a_new_immutable_job(tmp_path):
    _, client, headers, job, _ = ready_scene(tmp_path)
    response = client.post(f"/v1/projects/{job['project_id']}/jobs", headers=headers, json={"quality": "high"})
    assert response.status_code == 202 and response.json()["id"] != job["id"]
    again = client.post(f"/v1/projects/{job['project_id']}/jobs", headers=headers, json={"quality": "high"})
    assert again.json()["id"] == response.json()["id"]
    assert client.post(f"/v1/projects/{job['project_id']}/jobs", headers=headers, json={"quality": "unbounded"}).status_code == 422
