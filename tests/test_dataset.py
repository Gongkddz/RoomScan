import math

import pytest

from roomscan.dataset import camera_to_world, export_capture


def test_opengl_pose_convention():
    matrix = camera_to_world([1, 2, 3], [0, 0, 0, 1])
    assert matrix == [[1, 0, 0, 1], [0, 1, 0, 2], [0, 0, 1, 3], [0, 0, 0, 1]]
    rotated = camera_to_world([0, 0, 0], [0, math.sqrt(.5), 0, math.sqrt(.5)])
    # +90 degrees about world Y maps the camera forward (-Z) onto world -X.
    assert -rotated[0][2] == pytest.approx(-1)


def fixture_capture(tmp_path):
    image = tmp_path / "source.jpg"
    image.write_bytes(b"test-image")
    frames = []
    sources = {}
    for i in range(8):
        name = f"frame_{i}.jpg"
        sources[name] = image
        frames.append({"file": name, "tracking": "TRACKING", "timestamp_ns": i,
                       "image_timestamp_ns": i, "width": 640, "height": 480,
                       "image_dimensions": [1280, 960], "focal_length": [1000, 1000],
                       "principal_point": [640, 480], "translation": [i * .1, 0, 0],
                       "quaternion_xyzw": [0, 0, 0, 1]})
    return {"keyframes": frames}, sources


def test_export_scales_intrinsics_and_rejects_overwrite(tmp_path):
    capture, sources = fixture_capture(tmp_path)
    output = tmp_path / "dataset"
    report = export_capture(capture, sources, output)
    assert report["frames"] == 8
    import json
    data = json.loads((output / "transforms.json").read_text())
    assert data["frames"][0]["fl_x"] == 500
    assert data["frames"][0]["cx"] == 320
    with pytest.raises(ValueError, match="already"):
        export_capture(capture, sources, output)


def test_rejects_temporally_misaligned_and_stationary_captures(tmp_path):
    capture, sources = fixture_capture(tmp_path)
    capture["keyframes"][0]["image_timestamp_ns"] = 100_000_000
    with pytest.raises(ValueError, match="aligned"):
        export_capture(capture, sources, tmp_path / "first")
    for frame in capture["keyframes"]:
        frame["image_timestamp_ns"] = frame["timestamp_ns"]
        frame["translation"] = [0, 0, 0]
    with pytest.raises(ValueError, match="translation"):
        export_capture(capture, sources, tmp_path / "second")


def test_rejects_unsafe_image_names(tmp_path):
    capture, sources = fixture_capture(tmp_path)
    capture["keyframes"][0]["file"] = "../../unexpected.jpg"
    with pytest.raises(ValueError, match="Unsafe"):
        export_capture(capture, sources, tmp_path / "third")

