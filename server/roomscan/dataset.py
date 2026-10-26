"""Convert verified P0 captures to Nerfstudio camera-to-world datasets.

This is an ARCore-prior export, not bundle adjustment. Its quality must be
evaluated on real captures before being enabled as the production pipeline.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import shutil
from pathlib import Path


def camera_to_world(translation: list, quaternion: list) -> list[list[float]]:
    if len(translation) != 3 or len(quaternion) != 4:
        raise ValueError("Invalid camera pose")
    if not all(math.isfinite(v) for v in translation + quaternion):
        raise ValueError("Non-finite camera pose")
    x, y, z, w = quaternion
    norm = math.sqrt(x*x + y*y + z*z + w*w)
    if norm < 1e-6:
        raise ValueError("Zero quaternion")
    x, y, z, w = [v / norm for v in quaternion]
    # Both ARCore camera pose and Nerfstudio transform_matrix use OpenGL camera axes.
    return [
        [1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w), translation[0]],
        [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w), translation[1]],
        [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y), translation[2]],
        [0.0, 0.0, 0.0, 1.0],
    ]


def export_capture(capture: dict, images: dict[str, Path], output: Path, min_frames: int = 8) -> dict:
    if (output / "transforms.json").exists():
        raise ValueError("Output already contains a dataset; use a fresh directory")
    candidates = []
    for frame in capture.get("keyframes", []):
        name = frame["file"]
        if not re.fullmatch(r"frame_[0-9]+\.jpg", name):
            raise ValueError("Unsafe image name")
        if frame.get("tracking") != "TRACKING" or name not in images:
            continue
        if abs(frame["timestamp_ns"] - frame["image_timestamp_ns"]) > 50_000_000:
            continue
        width, height = frame["width"], frame["height"]
        iw, ih = frame["image_dimensions"]
        fx, fy = frame["focal_length"]
        cx, cy = frame["principal_point"]
        if min(width, height, iw, ih, fx, fy) <= 0:
            raise ValueError("Invalid image calibration")
        item = {"file_path": "images/" + name, "w": width, "h": height,
                "fl_x": fx * width / iw, "fl_y": fy * height / ih,
                "cx": cx * width / iw, "cy": cy * height / ih,
                "transform_matrix": camera_to_world(frame["translation"], frame["quaternion_xyzw"])}
        candidates.append((item, images[name]))
    if len(candidates) < min_frames:
        raise ValueError(f"Only {len(candidates)} aligned tracking frames; at least {min_frames} required")
    translations = [item["transform_matrix"] for item, _ in candidates]
    extent = math.sqrt(sum((max(m[i][3] for m in translations)-min(m[i][3] for m in translations))**2 for i in range(3)))
    if extent < .10:
        raise ValueError("Insufficient camera translation; capture more viewpoints")
    image_dir = output / "images"
    image_dir.mkdir(parents=True, exist_ok=True)
    for item, source in candidates:
        shutil.copyfile(source, image_dir / Path(item["file_path"]).name)
    result = {"camera_model": "OPENCV", "frames": [item for item, _ in candidates]}
    cloud = [p for p in capture.get("sparse_points", []) if len(p) == 4 and all(math.isfinite(v) for v in p) and p[3] >= .5]
    if cloud:
        with (output / "init.ply").open("w", encoding="ascii", newline="\n") as stream:
            stream.write("ply\nformat ascii 1.0\nelement vertex " + str(len(cloud)) + "\n")
            stream.write("property float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n")
            for x, y, z, _ in cloud:
                stream.write(f"{x} {y} {z} 128 128 128\n")
        result["ply_file_path"] = "init.ply"
    (output / "transforms.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    summary = {"frames": len(candidates), "camera_extent_m": extent, "initial_points": len(cloud),
               "pose_source": "ARCore prior, no bundle adjustment", "depth_used": False}
    (output / "export-report.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    return summary


def export_project(project_directory: Path, manifest: dict, output: Path) -> dict:
    mapping = manifest.get("capture", {}).get("files", {})
    allowed = {obj["sha256"] for obj in manifest["objects"]}
    images = {}
    capture = None
    for sha, name in mapping.items():
        if sha not in allowed or not re.fullmatch(r"[a-f0-9]{64}", sha):
            raise ValueError("Undeclared object")
        source = project_directory / sha
        with source.open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != sha:
                raise ValueError("Stored object failed integrity verification")
        if name == "capture.json":
            capture = json.loads(source.read_text(encoding="utf-8"))
        elif re.fullmatch(r"frame_[0-9]+\.jpg", name):
            images[name] = source
    if capture is None:
        raise ValueError("No capture calibration object")
    return export_capture(capture, images, output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("capture", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    metadata = json.loads((args.capture / "capture.json").read_text(encoding="utf-8"))
    print(json.dumps(export_capture(metadata, {p.name: p for p in args.capture.glob("frame_*.jpg")}, args.output), indent=2))

