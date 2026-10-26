"""Collect resolved Android notices and verified LGPL corresponding source."""
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
from urllib.request import urlopen
from zipfile import ZipFile, ZIP_DEFLATED
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
destination = ROOT / 'licenses/android'
destination.mkdir(parents=True, exist_ok=True)
artifacts = json.loads((ROOT / '.runtime/android-artifacts.json').read_text(encoding='utf-8'))
inventory = []
notices = []
namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}

def collect_embedded(data, prefix):
    with ZipFile(BytesIO(data)) as archive:
        for name in archive.namelist():
            leaf = name.rsplit('/', 1)[-1].lower()
            if any(leaf.startswith(s) for s in ('license', 'notice', 'copying')) and not name.endswith('/'):
                text = archive.read(name).decode('utf-8', errors='replace')
                notices.append(f'\n=== {prefix} / {name} ===\n{text}')
            if name == 'classes.jar':
                collect_embedded(archive.read(name), prefix + '/classes.jar')

for artifact in artifacts:
    coordinate = artifact['coordinate']
    file = Path(artifact['file'])
    poms = list(file.parent.parent.glob('*/*.pom'))
    license_items = []
    if not poms:
        group, artifact_name, version = coordinate.split(':')
        repository = 'https://dl.google.com/dl/android/maven2' if group.startswith(('androidx.', 'com.google.ar')) else 'https://repo.maven.apache.org/maven2'
        url = f'{repository}/{group.replace(".", "/")}/{artifact_name}/{version}/{artifact_name}-{version}.pom'
        with urlopen(url, timeout=30) as response:
            pom_text = response.read().decode('utf-8')
    else:
        pom_text = poms[0].read_text(encoding='utf-8')
    tree = ET.fromstring(pom_text)
    for entry in tree.findall('m:licenses/m:license', namespace):
        license_items.append({'name': entry.findtext('m:name', namespaces=namespace),
                              'url': entry.findtext('m:url', namespaces=namespace)})
    group, artifact_name, version = coordinate.split(':')
    repository = 'https://dl.google.com/dl/android/maven2' if group.startswith(('androidx.', 'com.google.ar')) else 'https://repo.maven.apache.org/maven2'
    inventory.append({'coordinate': coordinate, 'sha256': sha256(file.read_bytes()).hexdigest(),
                      'metadata_url': f'{repository}/{group.replace(".", "/")}/{artifact_name}/{version}/{artifact_name}-{version}.pom',
                      'licenses': license_items})
    collect_embedded(file.read_bytes(), coordinate)

(destination / 'inventory.json').write_text(json.dumps(inventory, indent=2) + '\n', encoding='utf-8')
(destination / 'EMBEDDED-NOTICES.txt').write_text('\n'.join(notices), encoding='utf-8')
with urlopen('https://www.apache.org/licenses/LICENSE-2.0.txt', timeout=30) as response:
    apache = response.read().decode('utf-8')
(destination / 'Apache-2.0.txt').write_text(apache, encoding='utf-8')
notice = [
    '留境 RoomScan 0.6.0-preview\nCopyright (c) 2026 RoomScan contributors\n',
    (ROOT / 'LICENSE').read_text(encoding='utf-8'),
    '本应用包含 Google 提供的可选 ARCore 功能。使用该功能适用：\n'
    'https://policies.google.com/terms\nhttps://policies.google.com/privacy\n'
    'https://developers.google.com/ar/develop/terms\n',
    'Android runtime components (license metadata from upstream Maven POMs):\n',
    json.dumps(inventory, indent=2, ensure_ascii=False), apache,
    *notices,
    (ROOT / 'licenses/viewer/THREE-LICENSE.txt').read_text(encoding='utf-8'),
    (ROOT / 'licenses/viewer/SPARK-LICENSE.txt').read_text(encoding='utf-8'),
]
app_notices = ROOT / 'android/app/src/main/assets/licenses/NOTICE.txt'
app_notices.parent.mkdir(parents=True, exist_ok=True)
app_notices.write_text('\n\n'.join(notice), encoding='utf-8')

source_manifest = []
source_root = ROOT / '.runtime/dependency-sources'
source_root.mkdir(parents=True, exist_ok=True)
for package, version in [('zeroconf', '0.151.5')]:
    with urlopen(f'https://pypi.org/pypi/{package}/{version}/json', timeout=30) as response:
        release = json.load(response)
    source = next(item for item in release['urls'] if item['packagetype'] == 'sdist')
    with urlopen(source['url'], timeout=60) as response:
        data = response.read()
    assert sha256(data).hexdigest() == source['digests']['sha256'], 'Source checksum mismatch'
    (source_root / source['filename']).write_bytes(data)
    source_manifest.append({'package': package, 'version': version, 'url': source['url'],
                            'sha256': source['digests']['sha256']})
(ROOT / 'licenses/sources.json').write_text(json.dumps(source_manifest, indent=2) + '\n', encoding='utf-8')
(ROOT / 'dist').mkdir(exist_ok=True)
with ZipFile(ROOT / 'dist/RoomScan-third-party-sources.zip', 'w', ZIP_DEFLATED) as archive:
    for file in source_root.iterdir():
        archive.write(file, file.name)
    archive.write(ROOT / 'licenses/sources.json', 'sources.json')
print(f'Collected {len(inventory)} Android artifacts and verified LGPL source')
