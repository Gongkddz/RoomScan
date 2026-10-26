import hashlib
from io import BytesIO
import json
from zipfile import ZipFile

import pytest

from roomscan import engine_setup


def upstream_wheel(tmp_path, monkeypatch, member='pycolmap/__init__.py', digest=None):
    stream = BytesIO()
    with ZipFile(stream, 'w') as archive:
        archive.writestr(member, "__version__ = '4.2.1'\n")
    data = stream.getvalue()
    metadata = tmp_path / 'wheel.json'
    metadata.write_text(json.dumps({'url': 'https://example.invalid/engine.whl', 'size': len(data),
                                    'sha256': digest or hashlib.sha256(data).hexdigest()}))
    monkeypatch.setattr(engine_setup, 'urlopen', lambda *args, **kwargs: BytesIO(data))
    return metadata


def test_verified_install_is_reused_without_network(tmp_path, monkeypatch):
    metadata = upstream_wheel(tmp_path, monkeypatch)
    installed = engine_setup.ensure_engine(tmp_path, metadata)
    assert (installed / 'pycolmap/__init__.py').is_file()
    monkeypatch.setattr(engine_setup, 'urlopen', lambda *a, **k: pytest.fail('Unexpected second download'))
    assert engine_setup.ensure_engine(tmp_path, metadata) == installed


def test_corrupt_download_is_rejected_before_install(tmp_path, monkeypatch):
    metadata = upstream_wheel(tmp_path, monkeypatch, digest='0' * 64)
    with pytest.raises(ValueError, match='checksum'):
        engine_setup.ensure_engine(tmp_path, metadata)
    assert not (tmp_path / '.tools/pycolmap').exists()


def test_archive_cannot_escape_install_directory(tmp_path, monkeypatch):
    metadata = upstream_wheel(tmp_path, monkeypatch, member='../escape.txt')
    with pytest.raises(ValueError, match='Unsafe'):
        engine_setup.ensure_engine(tmp_path, metadata)
    assert not (tmp_path / '.tools/pycolmap').exists()
    assert not list(tmp_path.rglob('escape.txt'))
