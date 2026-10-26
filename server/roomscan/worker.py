from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import threading
import shutil
from pathlib import Path

import psutil

from .store import Store
from .pipeline import PREPARATION_VERSION


class Worker:
    """One persistent queue, one isolated reconstruction process at a time."""
    def __init__(self, store: Store, brush: Path):
        self.store = store
        self.brush = brush.resolve()
        self.stop_event = threading.Event()
        self.thread = threading.Thread(target=self.run, name="reconstruction-worker", daemon=True)
        self.process: subprocess.Popen | None = None

    def update(self, job_id: str, state: str, stage: str, detail: str):
        with self.store.connect() as db:
            db.execute("UPDATE jobs SET state=?,stage=?,detail=? WHERE id=?", (state, stage, detail, job_id))

    def start(self):
        with self.store.connect() as db:
            db.execute("UPDATE jobs SET state='interrupted',detail='Service stopped before completion; retry from saved stages' WHERE state='running'")
        self.thread.start()

    def close(self):
        self.stop_event.set()
        if self.process and self.process.poll() is None:
            try:
                parent = psutil.Process(self.process.pid)
                for child in parent.children(recursive=True):
                    child.terminate()
                parent.terminate()
            except psutil.Error:
                pass
        self.thread.join(timeout=5)

    def run(self):
        while not self.stop_event.is_set():
            with self.store.connect() as db:
                row = db.execute("SELECT * FROM jobs WHERE state IN ('queued','waiting_engine') ORDER BY created LIMIT 1").fetchone()
                if row:
                    db.execute("UPDATE jobs SET state='running',stage='preparing' WHERE id=?", (row["id"],))
            if row:
                self.execute(dict(row))
            else:
                self.stop_event.wait(2)

    def execute(self, job: dict):
        output = self.store.root.resolve() / "jobs" / job["id"]
        output.mkdir(parents=True, exist_ok=True)
        try:
            result_file = output / "result.json"
            if not result_file.exists():
                self.reuse_preparation(job, output)
                settings = json.loads((output / "config.json").read_text()) if (output / "config.json").exists() else {}
                steps = settings.get("steps", 3000)
                if steps not in {1000, 3000, 6000}:
                    raise ValueError("Unsupported training budget")
                prefix = [sys.executable, "--roomscan-pipeline"] if getattr(sys, 'frozen', False) else [sys.executable, "-m", "roomscan.pipeline"]
                command = prefix + [str(self.store.root.resolve()),
                           job["project_id"], str(output), "--brush", str(self.brush), "--steps", str(steps)]
                with (output / "pipeline.log").open("a", encoding="utf-8") as log:
                    self.process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
                    while self.process.poll() is None:
                        if self.stop_event.wait(1):
                            self.update(job["id"], "interrupted", "paused", "Service stopped")
                            return
                        try:
                            stage = json.loads((output / "status.json").read_text())
                            self.update(job["id"], "running", stage["stage"], stage["detail"])
                        except (OSError, ValueError):
                            pass
                    if self.process.returncode:
                        raise ValueError("Reconstruction failed; see saved stage report")
            result = json.loads(result_file.read_text())
            model = (output / result["model"]).resolve()
            if not model.is_relative_to(output):
                raise ValueError("Invalid output path")
            with model.open("rb") as stream:
                if hashlib.file_digest(stream, "sha256").hexdigest() != result["model_sha256"]:
                    raise ValueError("Output integrity check failed")
            self.update(job["id"], "ready", "ready", "Scene generated and verified")
        except Exception as error:
            try:
                detail = json.loads((output / "status.json").read_text()).get("detail", str(error))
            except (OSError, ValueError):
                detail = str(error)
            self.update(job["id"], "failed", "failed", detail)
        finally:
            self.process = None

    def reuse_preparation(self, job: dict, output: Path):
        if (output / "sfm-report.json").exists():
            return
        with self.store.connect() as db:
            others = db.execute("SELECT id FROM jobs WHERE project_id=? AND state='ready' AND id<>? ORDER BY created DESC", (job["project_id"], job["id"])).fetchall()
        for item in others:
            source = self.store.root.resolve() / "jobs" / item["id"]
            try:
                report = json.loads((source / "sfm-report.json").read_text())
                if report.get("preparation_version") != PREPARATION_VERSION or not (source / "undistorted.done").exists():
                    continue
                if not (output / "dataset").exists():
                    def link_or_copy(src, dst):
                        try:
                            os.link(src, dst)
                        except OSError:
                            shutil.copyfile(src, dst)
                    shutil.copytree(source / "dataset", output / "dataset", copy_function=link_or_copy)
                for name in ["view.json", "sfm-report.json", "undistorted.done"]:
                    shutil.copyfile(source / name, output / name)
                return
            except (OSError, ValueError):
                continue
