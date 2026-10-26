"""Bounded Camera2 -> COLMAP -> undistorted images -> Brush pipeline."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import time
from pathlib import Path

from .store import Store

PREPARATION_VERSION = "colmap4.2.1-sift1280-4096-seq8-v1"


def status(output: Path, stage: str, detail: str):
    data = {"stage": stage, "detail": detail, "updated": time.time()}
    pending = output / "status.pending"
    pending.write_text(json.dumps(data), encoding="utf-8")
    pending.replace(output / "status.json")
    print(stage + ": " + detail, flush=True)


def prepare(store: Store, project_id: str, output: Path, limit: int = 180) -> tuple[dict, int]:
    with store.connect() as db:
        row = db.execute("SELECT manifest FROM projects WHERE id=?", (project_id,)).fetchone()
    if row is None or not row["manifest"]:
        raise ValueError("Capture manifest missing")
    manifest = json.loads(row["manifest"])
    objects = {item["sha256"]: item for item in manifest["objects"]}
    files = manifest.get("capture", {}).get("files", {})
    folder = store.project_dir(project_id)
    capture = None
    images = {}
    for sha, name in files.items():
        if sha not in objects or not re.fullmatch("[a-f0-9]{64}", sha):
            raise ValueError("Invalid input object")
        path = folder / sha
        with path.open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != sha:
                raise ValueError("Input integrity check failed")
        if name == "capture.json":
            capture = json.loads(path.read_text(encoding="utf-8"))
        elif re.fullmatch(r"frame_[0-9]+\.jpg", name):
            images[name] = path
    if capture is None or len(images) < 8:
        raise ValueError("At least 8 clear image viewpoints are required; capture more views")
    names = sorted(images)
    if len(names) > limit:
        names = [names[round(i * (len(names)-1) / (limit-1))] for i in range(limit)]
    destination = output / "raw/images"
    destination.mkdir(parents=True, exist_ok=True)
    for name in names:
        target = destination / name
        if not target.exists():
            try:
                os.link(images[name], target)
            except OSError:
                shutil.copyfile(images[name], target)
    return capture, len(names)


def reconstruct(root: Path, project_id: str, output: Path, brush: Path, steps: int = 3000):
    import pycolmap
    import numpy as np

    output.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    store = Store(root)
    status(output, "preparing", "Verifying selected capture images")
    capture, count = prepare(store, project_id, output)
    images, database = output / "raw/images", output / "features.db"
    sparse = output / "raw/sparse"
    sparse.mkdir(parents=True, exist_ok=True)
    threads = min(8, os.cpu_count() or 4)
    if not (output / "sfm-report.json").exists():
        # A partially populated database can be safely reused by COLMAP's own image checks.
        reader = pycolmap.ImageReaderOptions()
        reader.camera_model = "SIMPLE_RADIAL"
        physical = capture.get("physical_sensor_mm")
        frames = capture.get("keyframes", [])
        if physical and frames and physical[0] > 0:
            focal_mm = frames[0].get("focal_length_mm")
            if focal_mm:
                w, h = capture["image_width"], capture["image_height"]
                reader.camera_params = f"{focal_mm*w/physical[0]},{w/2},{h/2},0"
        extract = pycolmap.FeatureExtractionOptions()
        extract.max_image_size = 1280
        extract.num_threads = threads
        extract.sift.max_num_features = 4096
        status(output, "features", f"Extracting features from {count} selected images")
        pycolmap.extract_features(database, images, camera_mode=pycolmap.CameraMode.SINGLE,
                                 reader_options=reader, extraction_options=extract, device=pycolmap.Device.cpu)
        matching = pycolmap.FeatureMatchingOptions()
        matching.num_threads = threads
        pairs = pycolmap.SequentialPairingOptions()
        pairs.overlap = 8
        pairs.loop_detection = False
        status(output, "matching", "Matching neighboring viewpoints")
        pycolmap.match_sequential(database, matching_options=matching, pairing_options=pairs, device=pycolmap.Device.cpu)
        options = pycolmap.IncrementalPipelineOptions()
        options.num_threads = threads
        options.max_num_models = 3
        options.min_model_size = 8
        options.max_runtime_seconds = 240
        options.random_seed = 42
        status(output, "mapping", "Estimating camera positions and optimizing scene geometry")
        models = pycolmap.incremental_mapping(database, images, sparse, options=options)
        if not models:
            raise ValueError("Unable to connect viewpoints; move around a textured static target and rescan")
        model = max(models.values(), key=lambda item: item.num_reg_images())
        registered = model.num_reg_images()
        if registered < max(4, math.ceil(count*.5)) or model.num_points3D() < 100:
            raise ValueError(f"Only {registered}/{count} views registered; capture more overlapping viewpoints")
        selected = output / "selected-sparse"
        selected.mkdir(exist_ok=True)
        model.write(selected)
        # Save camera-to-world in OpenGL convention for the local viewer.
        first = min((model.image(i) for i in model.reg_image_ids()), key=lambda item: item.name)
        matrix = first.cam_from_world().inverse().matrix()
        matrix[:, 1:3] *= -1
        points = np.array([point.xyz for point in model.points3D.values()])
        lower, upper = np.quantile(points, [.05, .95], axis=0)
        view = {"camera_to_world": np.vstack((matrix, [0,0,0,1])).tolist(),
                "center": np.median(points, axis=0).tolist(), "bounds_min": lower.tolist(), "bounds_max": upper.tolist(),
                "scale": "arbitrary", "ar_supported": False, "reference_image": first.name,
                "display_rotation_degrees": capture.get("orientation_degrees", capture.get("sensor_orientation",0))}
        reference = next((item for item in capture.get("keyframes",[]) if item.get("file")==first.name),None)
        if reference and reference.get("gravity_device"):
            view["gravity_device"] = reference["gravity_device"]
        (output / "view.json").write_text(json.dumps(view), encoding="utf-8")
        report = {"input_images": count, "registered_images": registered, "points": model.num_points3D(),
                  "reprojection_error": model.compute_mean_reprojection_error(), "pose_source": "COLMAP SfM", "scale": "arbitrary",
                  "preparation_version": PREPARATION_VERSION}
        (output / "sfm-report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    undistorted = output / "dataset"
    if not (output / "undistorted.done").exists():
        status(output, "undistorting", "Preparing calibrated images for training")
        options = pycolmap.UndistortCameraOptions()
        options.max_image_size = 1280
        pycolmap.undistort_images(undistorted, output / "selected-sparse", images,
                                 undistort_options=options, num_threads=threads)
        (output / "undistorted.done").write_text("ok")
    status(output, "training", "Training a Gaussian scene on the desktop GPU")
    model_dir = output / "models"
    model_dir.mkdir(exist_ok=True)
    command = [str(brush.resolve()), str(undistorted.resolve()), "--total-steps", str(steps),
               "--max-splats", "200000", "--max-resolution", "1280", "--sh-degree", "1",
               "--export-every", str(steps), "--export-path", str(model_dir.resolve()), "--export-name", "scene_{iter}.ply"]
    with (output / "brush.log").open("w", encoding="utf-8") as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=600, check=True)
    model_file = model_dir / f"scene_{steps}.ply"
    if not model_file.exists() or model_file.stat().st_size < 500:
        raise ValueError("Training did not produce a valid scene")
    with model_file.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    result = {"model": str(model_file.relative_to(output)), "model_sha256": digest,
              "model_bytes": model_file.stat().st_size, "elapsed_s": round(time.monotonic()-started, 3),
              "steps": steps, "view": json.loads((output / "view.json").read_text()),
              "reconstruction": json.loads((output / "sfm-report.json").read_text())}
    (output / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    status(output, "ready", "Scene generated")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    parser.add_argument("project")
    parser.add_argument("output", type=Path)
    parser.add_argument("--brush", type=Path, required=True)
    parser.add_argument("--steps", type=int, default=3000)
    args = parser.parse_args()
    try:
        print(json.dumps(reconstruct(args.root.resolve(), args.project, args.output.resolve(), args.brush, args.steps), indent=2))
    except Exception as error:
        args.output.mkdir(parents=True, exist_ok=True)
        status(args.output, "failed", str(error))
        raise
