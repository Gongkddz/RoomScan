from __future__ import annotations

import asyncio
import hashlib
import json
import re
import secrets
import time
import uuid
from collections import defaultdict, deque
from pathlib import Path
from typing import Annotated, Literal

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field, model_validator

from . import __version__
from .store import Store

HEX = re.compile(r"^[0-9a-f]{64}$")
MAX_OBJECT = 32 * 1024 * 1024
MAX_PROJECT = 1024 * 1024 * 1024
MAX_CHUNK = 4 * 1024 * 1024


class PairRequest(BaseModel):
    code: str = Field(min_length=6, max_length=6)
    name: str = Field(min_length=1, max_length=80)


class ProjectRequest(BaseModel):
    name: str = Field(min_length=1, max_length=120)


class JobRequest(BaseModel):
    quality: Literal["preview", "standard", "high"] = "standard"


class ObjectSpec(BaseModel):
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    size: int = Field(gt=0, le=MAX_OBJECT)
    kind: str = Field(pattern=r"^(image|poses|depth|calibration|diagnostic)$")


class Manifest(BaseModel):
    schema_version: int = Field(default=1, ge=1, le=1)
    objects: list[ObjectSpec] = Field(min_length=1, max_length=2000)
    capture: dict = Field(default_factory=dict)

    @model_validator(mode="after")
    def valid_sizes(self):
        if sum(o.size for o in self.objects) > MAX_PROJECT:
            raise ValueError("Project exceeds 1 GiB input budget")
        if len({o.sha256 for o in self.objects}) != len(self.objects):
            raise ValueError("Duplicate object hash")
        if len(json.dumps(self.capture)) > 65536:
            raise ValueError("Capture metadata too large")
        return self


def create_app(root: Path, identity: dict, pairing_code: str | None = None, training_enabled: bool = False) -> FastAPI:
    store = Store(root)
    app = FastAPI(title="RoomScan", version=__version__, docs_url=None, redoc_url=None)
    app.state.store = store
    app.state.training_enabled = training_enabled
    app.state.pairing_code = pairing_code or f"{secrets.randbelow(1000000):06}"
    app.state.pairing_expires = time.monotonic() + 300
    attempts: dict[str, deque] = defaultdict(deque)
    locks: dict[str, asyncio.Lock] = defaultdict(asyncio.Lock)

    async def authenticate(authorization: Annotated[str | None, Header()] = None) -> str:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(401, "Pair this device first")
        token_hash = hashlib.sha256(authorization[7:].encode()).hexdigest()
        with store.connect() as db:
            row = db.execute("SELECT id FROM devices WHERE token_hash=?", (token_hash,)).fetchone()
        if row is None:
            raise HTTPException(401, "Invalid or revoked device token")
        return row["id"]

    def own_project(project_id: str, device_id: str) -> dict:
        with store.connect() as db:
            row = db.execute("SELECT * FROM projects WHERE id=? AND device_id=?", (project_id, device_id)).fetchone()
        if row is None:
            raise HTTPException(404, "Project not found")
        return dict(row)

    def object_spec(project: dict, sha: str) -> ObjectSpec:
        if not HEX.fullmatch(sha) or not project["manifest"]:
            raise HTTPException(404, "Object not declared")
        manifest = Manifest.model_validate_json(project["manifest"])
        for obj in manifest.objects:
            if obj.sha256 == sha:
                return obj
        raise HTTPException(404, "Object not declared")

    def describe_job(job: dict) -> dict:
        if job["state"] == "ready":
            result_path = root / "jobs" / job["id"] / "result.json"
            result = json.loads(result_path.read_text(encoding="utf-8"))
            job["model"] = {"url": f"/v1/jobs/{job['id']}/model", "sha256": result["model_sha256"],
                            "bytes": result["model_bytes"], "view": result["view"]}
        return job

    @app.get("/v1/health")
    def health():
        return {"server_id": identity["server_id"], "version": __version__, "protocol": 1,
                "capabilities": ["capture-ingest", "resumable-objects", "diagnostics"],
                "training_ready": training_enabled}

    @app.post("/v1/pair")
    def pair(body: PairRequest, request: Request):
        peer = request.client.host if request.client else "unknown"
        history = attempts[peer]
        now = time.monotonic()
        while history and now - history[0] > 60:
            history.popleft()
        if len(history) >= 5:
            raise HTTPException(429, "Too many pairing attempts; retry later")
        history.append(now)
        if now > app.state.pairing_expires or not secrets.compare_digest(body.code, app.state.pairing_code):
            raise HTTPException(403, "Pairing code invalid or expired")
        token, device_id = secrets.token_urlsafe(32), str(uuid.uuid4())
        with store.connect() as db:
            db.execute("INSERT INTO devices VALUES(?,?,?)", (device_id, body.name, hashlib.sha256(token.encode()).hexdigest()))
        # A pairing session authorizes one device. Restart to explicitly open another window.
        app.state.pairing_expires = 0
        return {"device_id": device_id, "token": token, "server_id": identity["server_id"]}

    @app.post("/v1/projects", status_code=201)
    def create_project(body: ProjectRequest, device: str = Depends(authenticate)):
        project_id = str(uuid.uuid4())
        with store.connect() as db:
            db.execute("INSERT INTO projects VALUES(?,?,?,NULL,NULL,?)", (project_id, device, body.name, time.time()))
        return {"project_id": project_id}

    @app.get("/v1/projects")
    def projects(device: str = Depends(authenticate)):
        with store.connect() as db:
            return [dict(row) for row in db.execute(
                "SELECT id,name,created,manifest_hash FROM projects WHERE device_id=? ORDER BY created DESC", (device,))]

    @app.get("/v1/sync")
    def sync(device: str = Depends(authenticate)):
        with store.connect() as db:
            rows = db.execute("SELECT jobs.* FROM jobs JOIN projects ON jobs.project_id=projects.id WHERE projects.device_id=? ORDER BY jobs.created DESC LIMIT 50", (device,)).fetchall()
        return {"jobs": [describe_job(dict(row)) for row in rows], "training_ready": training_enabled}

    @app.put("/v1/projects/{project_id}/manifest")
    async def set_manifest(project_id: str, body: Manifest, device: str = Depends(authenticate)):
        own_project(project_id, device)
        data = body.model_dump_json()
        digest = hashlib.sha256(data.encode()).hexdigest()
        async with locks[project_id]:
            with store.connect() as db:
                existing = db.execute("SELECT manifest_hash FROM projects WHERE id=?", (project_id,)).fetchone()[0]
                if existing and existing != digest:
                    raise HTTPException(409, "Manifest is immutable; create a new capture project")
                db.execute("UPDATE projects SET manifest=?,manifest_hash=? WHERE id=?", (data, digest, project_id))
        return {"manifest_hash": digest}

    @app.get("/v1/projects/{project_id}/objects")
    def objects(project_id: str, device: str = Depends(authenticate)):
        project = own_project(project_id, device)
        if not project["manifest"]:
            raise HTTPException(409, "Submit a manifest first")
        path = store.project_dir(project_id)
        result = []
        for obj in Manifest.model_validate_json(project["manifest"]).objects:
            complete = (path / obj.sha256).is_file()
            partial = path / (obj.sha256 + ".part")
            result.append({"sha256": obj.sha256, "complete": complete,
                           "offset": obj.size if complete else (partial.stat().st_size if partial.exists() else 0)})
        return {"objects": result}

    @app.put("/v1/projects/{project_id}/objects/{sha}")
    async def upload(project_id: str, sha: str, request: Request, offset: int = 0,
                     device: str = Depends(authenticate)):
        project = own_project(project_id, device)
        spec = object_spec(project, sha)
        if offset < 0 or offset > spec.size:
            raise HTTPException(400, "Invalid offset")
        async with locks[project_id]:
            directory = store.project_dir(project_id)
            final, partial = directory / sha, directory / (sha + ".part")
            if final.exists():
                return {"complete": True, "offset": spec.size}
            current = partial.stat().st_size if partial.exists() else 0
            if offset != current:
                raise HTTPException(409, {"expected_offset": current})
            received = 0
            try:
                with partial.open("ab") as f:
                    async for chunk in request.stream():
                        received += len(chunk)
                        if received > MAX_CHUNK or current + received > spec.size:
                            raise HTTPException(413, "Chunk or object exceeds declared budget")
                        f.write(chunk)
            except BaseException:
                # A failed request is not committed; keep the previous resumable prefix.
                with partial.open("r+b") as f:
                    f.truncate(current)
                raise
            length = partial.stat().st_size
            if length == spec.size:
                with partial.open("rb") as f:
                    actual = hashlib.file_digest(f, "sha256").hexdigest()
                if actual != sha:
                    partial.unlink()
                    raise HTTPException(422, "SHA-256 mismatch; re-upload object")
                partial.replace(final)
                return {"complete": True, "offset": length}
            return {"complete": False, "offset": length}

    @app.post("/v1/projects/{project_id}/jobs", status_code=202)
    async def submit_job(project_id: str, body: JobRequest | None = None, device: str = Depends(authenticate)):
        project = own_project(project_id, device)
        if not project["manifest"]:
            raise HTTPException(409, "Missing manifest")
        async with locks[project_id]:
            quality = body.quality if body else "standard"
            settings = {"quality": quality, "steps": {"preview": 1000, "standard": 3000, "high": 6000}[quality], "pipeline_version": 1}
            input_hash = hashlib.sha256((project["manifest_hash"] + json.dumps(settings, sort_keys=True)).encode()).hexdigest()
            for obj in Manifest.model_validate_json(project["manifest"]).objects:
                if not (store.project_dir(project_id) / obj.sha256).exists():
                    raise HTTPException(409, "Input upload incomplete")
            with store.connect() as db:
                existing = db.execute("SELECT id FROM jobs WHERE project_id=? AND input_hash=?",
                                      (project_id, input_hash)).fetchone()
                if existing:
                    job_id = existing["id"]
                else:
                    job_id = str(uuid.uuid4())
                    directory = root / "jobs" / job_id
                    directory.mkdir(parents=True, exist_ok=True)
                    (directory / "config.json").write_text(json.dumps(settings), encoding="utf-8")
                    db.execute("INSERT INTO jobs VALUES(?,?,?,?,?,?,?)", (job_id, project_id,
                               input_hash, "queued" if training_enabled else "waiting_engine", "input_verified",
                               "Input verified; queued for reconstruction" if training_enabled else "Capture input verified. Reconstruction worker is not enabled in this P0 build.", time.time()))
        return store.job(job_id)

    @app.get("/v1/jobs/{job_id}")
    def get_job(job_id: str, device: str = Depends(authenticate)):
        job = store.job(job_id)
        if job is None:
            raise HTTPException(404, "Job not found")
        own_project(job["project_id"], device)
        return describe_job(job)

    @app.get("/v1/jobs/{job_id}/model")
    def model(job_id: str, device: str = Depends(authenticate)):
        job = store.job(job_id)
        if job is None:
            raise HTTPException(404, "Job not found")
        own_project(job["project_id"], device)
        if job["state"] != "ready":
            raise HTTPException(409, "Model not ready")
        folder = (root / "jobs" / job_id).resolve()
        result = json.loads((folder / "result.json").read_text())
        path = (folder / result["model"]).resolve()
        if not path.is_relative_to(folder):
            raise HTTPException(500, "Invalid model path")
        return FileResponse(path, media_type="application/octet-stream", filename="scene.ply",
                            headers={"ETag": '"' + result["model_sha256"] + '"', "Cache-Control": "private, max-age=31536000, immutable"})

    return app

