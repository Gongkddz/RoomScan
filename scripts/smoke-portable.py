"""Exercise the shipped runtime without a project virtualenv or source path."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('bundle', type=Path, nargs='?', default=Path('dist/windows/RoomScan'))
args = parser.parse_args()
source = args.bundle.resolve()
with tempfile.TemporaryDirectory(prefix='roomscan-portable-') as temporary:
    bundle = Path(temporary) / 'RoomScan'
    shutil.copytree(source, bundle)
    executable = bundle / 'RoomScan.exe'
    installed = subprocess.run([str(executable), '--install-training'], cwd=bundle, timeout=120)
    assert installed.returncode == 0, 'Official engine installation failed'
    assert json.loads((bundle / '.runtime/engine-check.json').read_text())['version'] == '4.2.1'
    stop = bundle / 'stop.signal'
    process = subprocess.Popen([str(executable), '--roomscan-cli', 'serve', '--advertise', '127.0.0.1',
                                '--port', '18743', '--dashboard-port', '18744', '--stop-file', str(stop)], cwd=bundle)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        deadline = time.monotonic() + 35
        while True:
            try:
                with opener.open('http://127.0.0.1:18744/api/status', timeout=1) as response:
                    status = json.load(response)
                break
            except OSError:
                if process.poll() is not None or time.monotonic() > deadline:
                    logs = '\n'.join(p.read_text(encoding='utf-8', errors='replace') for p in (bundle / '.runtime').glob('*.log'))
                    raise RuntimeError('Frozen service failed: ' + logs)
                time.sleep(.3)
        assert status['training_ready'], status
        with opener.open('http://127.0.0.1:18744/pairing.png', timeout=2) as response:
            assert response.read().startswith(b'\x89PNG'), 'Pairing image unavailable'
        # Invalid input checks that the frozen subprocess enters the pipeline and
        # loads the native COLMAP dependency, then produces its structured failure.
        output = bundle / 'invalid-pipeline'
        result = subprocess.run([str(executable), '--roomscan-pipeline', str(bundle / '.runtime'),
                                 'missing-project', str(output), '--brush', str(bundle / '.tools/brush/brush_app.exe')],
                                cwd=bundle, timeout=20)
        assert result.returncode != 0
        assert (output / 'status.json').is_file(), 'Frozen pipeline entry did not execute'
        print('PASS: isolated frozen service, training readiness, QR image, pipeline dispatch')
    finally:
        stop.touch()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
            raise
    assert process.returncode == 0, process.returncode
    print('PASS: graceful service shutdown')
