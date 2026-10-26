"""Preserve installed distribution license texts and machine-readable provenance."""
import importlib.metadata as metadata
import json
from pathlib import Path
import shutil
import sys

root = Path(__file__).resolve().parents[1]
destination = root / 'licenses' / 'python'
destination.mkdir(parents=True, exist_ok=True)
inventory = []
build_only = {'roomscan-server', 'pip', 'pytest', 'httpx', 'httpcore', 'iniconfig',
              'pluggy', 'pygments', 'altgraph', 'pefile', 'pyinstaller-hooks-contrib',
              'pywin32-ctypes', 'pycolmap'}
for dist in sorted(metadata.distributions(), key=lambda d: d.metadata['Name'].lower()):
    name = dist.metadata['Name']
    if name.lower() in build_only:
        continue
    texts = []
    for item in dist.files or []:
        relative = str(item).replace('\\', '/')
        if '.dist-info/' not in relative or not any(part in item.name.lower() for part in ('license', 'copying', 'notice', 'authors')):
            continue
        source = Path(dist.locate_file(item))
        if not source.is_file():
            continue
        target = destination / name / relative.split('.dist-info/', 1)[1]
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        texts.append(str(target.relative_to(root)).replace('\\', '/'))
    inventory.append({'name': name, 'version': dist.version,
                      'license': dist.metadata.get('License-Expression') or dist.metadata.get('License'),
                      'project_urls': dist.metadata.get_all('Project-URL') or [], 'license_files': texts})
(destination / 'inventory.json').write_text(json.dumps(inventory, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
# CPython and bundled Tcl/Tk are distributed along with the frozen interpreter.
base = Path(sys.base_prefix)
for source in [base / 'LICENSE.txt', *base.glob('tcl/*/license.terms')]:
    target = destination / 'interpreter' / source.parent.name / source.name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
print(f'Collected license metadata for {len(inventory)} runtime / bootloader distributions')
