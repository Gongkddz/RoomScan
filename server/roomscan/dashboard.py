"""Loopback-only pairing UI. Secrets are not broadcast over mDNS."""
from __future__ import annotations
import io
import json
import secrets
import time
from pathlib import Path
import qrcode
import qrcode.image.svg
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse, Response


def create_dashboard(companion: FastAPI, identity: dict, endpoint: str, admin_port: int) -> FastAPI:
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    store = companion.state.store

    @app.middleware("http")
    async def local_only(request: Request, call_next):
        if request.client and request.client.host not in {"127.0.0.1", "::1", "testclient"}:
            return Response(status_code=403)
        if request.headers.get("host", "").split(":")[0] not in {"127.0.0.1", "localhost", "testserver"}:
            return Response(status_code=403)
        if request.method != "GET" and request.headers.get("origin") != f"http://127.0.0.1:{admin_port}":
            return Response(status_code=403)
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Content-Security-Policy"] = "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self'; frame-ancestors 'none'"
        return response

    @app.get("/", response_class=HTMLResponse)
    def home():
        return Path(__file__).with_name("dashboard.html").read_text(encoding="utf-8")

    @app.get("/api/status")
    def status():
        with store.connect() as db:
            devices = [dict(r) for r in db.execute("SELECT name FROM devices")]
            projects = db.execute("SELECT count(*) FROM projects").fetchone()[0]
            jobs = [dict(r) for r in db.execute("SELECT state,stage,detail FROM jobs ORDER BY created DESC LIMIT 8")]
        return {"product": "roomscan", "devices": devices, "projects": projects, "jobs": jobs,
                "pairing_seconds": max(0, int(companion.state.pairing_expires - time.monotonic())), "training_ready": companion.state.training_enabled}

    @app.post("/api/pairing")
    def renew_pairing():
        companion.state.pairing_code = f"{secrets.randbelow(1000000):06}"
        companion.state.pairing_expires = time.monotonic() + 300
        return {"ok": True}

    @app.get("/pairing.svg")
    def pairing():
        if time.monotonic() > companion.state.pairing_expires:
            raise HTTPException(410, "Pairing expired")
        payload = {"type": "roomscan-pair", "v": 1, "server_id": identity["server_id"],
                   "pin": identity["fingerprint"], "code": companion.state.pairing_code, "endpoint": endpoint}
        image = qrcode.make(json.dumps(payload, separators=(",", ":")), image_factory=qrcode.image.svg.SvgPathImage, box_size=6, border=4)
        output = io.BytesIO(); image.save(output)
        return Response(output.getvalue(), media_type="image/svg+xml")

    @app.get("/pairing.png")
    def pairing_png():
        if time.monotonic() > companion.state.pairing_expires:
            raise HTTPException(410, "Pairing expired")
        payload = {"type": "roomscan-pair", "v": 1, "server_id": identity["server_id"],
                   "pin": identity["fingerprint"], "code": companion.state.pairing_code, "endpoint": endpoint}
        image = qrcode.make(json.dumps(payload, separators=(",", ":")), box_size=3, border=4)
        output = io.BytesIO()
        image.save(output, format="PNG")
        return Response(output.getvalue(), media_type="image/png")

    return app
