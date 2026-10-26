"""Install the optional upstream PyCOLMAP wheel on the recipient's machine."""
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import tempfile
from urllib.request import Request, urlopen
from zipfile import ZipFile


def ensure_engine(root: Path, metadata_path: Path):
    metadata = json.loads(metadata_path.read_text(encoding='utf-8'))
    destination = root / '.tools' / 'pycolmap'
    if (destination / 'pycolmap/__init__.py').is_file():
        return destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = Request(metadata['url'], headers={'User-Agent': 'RoomScan/0.6.0'})
    with urlopen(request, timeout=60) as response:
        data = response.read(metadata['size'] + 1)
    if len(data) != metadata['size'] or sha256(data).hexdigest() != metadata['sha256']:
        raise ValueError('PyCOLMAP official wheel checksum mismatch')
    with tempfile.TemporaryDirectory(prefix='engine-install-', dir=destination.parent) as temporary:
        unpacked = Path(temporary) / 'pycolmap'
        unpacked.mkdir()
        with ZipFile(BytesIO(data)) as archive:
            for item in archive.infolist():
                if not (unpacked / item.filename).resolve().is_relative_to(unpacked.resolve()):
                    raise ValueError('Unsafe wheel member')
            archive.extractall(unpacked)
        unpacked.rename(destination)
    return destination
