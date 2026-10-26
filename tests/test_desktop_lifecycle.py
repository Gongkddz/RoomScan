"""Exercise the OS guarantee that protects against orphaned GPU processes."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import time

import psutil
import pytest


@pytest.mark.skipif(sys.platform != 'win32', reason='Windows job object')
def test_desktop_job_closes_server_and_descendants(tmp_path):
    spec = importlib.util.spec_from_file_location('roomscan_desktop', Path(__file__).resolve().parents[1] / 'scripts/desktop.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    child_file = tmp_path / 'child.txt'
    child_script = tmp_path / 'server.py'
    child_script.write_text("import subprocess,sys,time,pathlib\np=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'])\npathlib.Path(sys.argv[1]).write_text(str(p.pid))\ntime.sleep(60)\n")
    process = subprocess.Popen([sys.executable, str(child_script), str(child_file)], creationflags=subprocess.CREATE_NO_WINDOW)
    kernel, job = module.own_process_tree(process)
    try:
        deadline = time.monotonic() + 8
        while not child_file.exists() and time.monotonic() < deadline:
            time.sleep(.05)
        child = psutil.Process(int(child_file.read_text()))
        assert process.poll() is None and child.is_running()
    finally:
        kernel.CloseHandle(job)
    process.wait(timeout=5)
    child.wait(timeout=5)
    assert process.poll() is not None and not child.is_running()
