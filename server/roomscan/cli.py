from __future__ import annotations

import argparse
import json
import socket
import subprocess
import threading
import importlib.util
from pathlib import Path

import psutil
import uvicorn
from zeroconf import ServiceInfo, Zeroconf, IPVersion

from .api import create_app
from .identity import ensure_identity
from .dashboard import create_dashboard
from .worker import Worker


def main():
    parser = argparse.ArgumentParser(description="RoomScan local companion (P0)")
    parser.add_argument("command", choices=["doctor", "serve"])
    parser.add_argument("--data", type=Path, default=Path(".runtime"))
    parser.add_argument("--host", default=None)
    parser.add_argument("--advertise", help="Explicit LAN IPv4, recommended on multi-adapter computers")
    parser.add_argument("--port", type=int, default=8743)
    parser.add_argument("--dashboard-port", type=int, default=8744)
    parser.add_argument("--brush", type=Path, default=Path(".tools/brush/brush_app.exe"))
    parser.add_argument("--stop-file", type=Path, help="Desktop lifecycle shutdown signal")
    args = parser.parse_args()
    if args.command == "doctor":
        try:
            gpu = subprocess.run(["nvidia-smi", "--query-gpu=name,memory.total,driver_version", "--format=csv,noheader"],
                                 capture_output=True, text=True, timeout=10).stdout.strip()
        except (OSError, subprocess.TimeoutExpired):
            gpu = None
        print(json.dumps({"gpu": gpu, "ram_gib": round(psutil.virtual_memory().total / 2**30, 1),
                          "ipv4": {name: [a.address for a in addrs if a.family == socket.AF_INET]
                                   for name, addrs in psutil.net_if_addrs().items()},
                          "training_ready": False}, ensure_ascii=False, indent=2))
        return
    root = args.data.resolve()
    identity = ensure_identity(root)
    ready = args.brush.is_file() and importlib.util.find_spec("pycolmap") is not None
    app = create_app(root, identity, training_enabled=ready)
    worker = Worker(app.state.store, args.brush) if ready else None
    if worker:
        worker.start()
    endpoint = f"https://{args.advertise or '127.0.0.1'}:{args.port}"
    (root / "service.json").write_text(json.dumps({"api_endpoint": endpoint, "dashboard": f"http://127.0.0.1:{args.dashboard_port}"}), encoding="utf-8")
    dashboard = create_dashboard(app, identity, endpoint, args.dashboard_port)
    dashboard_server = uvicorn.Server(uvicorn.Config(dashboard, host="127.0.0.1", port=args.dashboard_port, access_log=False))
    dashboard_thread = threading.Thread(target=dashboard_server.run, daemon=True)
    dashboard_thread.start()
    print(f"Companion dashboard: http://127.0.0.1:{args.dashboard_port}", flush=True)
    print("Server identity:", identity["server_id"], flush=True)
    print("TLS SHA-256:", identity["fingerprint"], flush=True)
    print("Pairing code (one device, 5 min):", app.state.pairing_code, flush=True)
    zc = info = None
    if args.advertise:
        ip = socket.inet_aton(args.advertise)
        zc = Zeroconf(interfaces=[args.advertise], ip_version=IPVersion.V4Only)
        info = ServiceInfo("_gsroom._tcp.local.", "RoomScan-" + identity["server_id"][:8] + "._gsroom._tcp.local.",
                           addresses=[ip], port=args.port,
                           properties={"id": identity["server_id"], "protocol": "1", "tls": "1"},
                           server="roomscan-" + identity["server_id"][:8] + ".local.")
        zc.register_service(info)
    server = uvicorn.Server(uvicorn.Config(app, host=args.host or args.advertise or "127.0.0.1", port=args.port,
                            ssl_certfile=identity["cert"], ssl_keyfile=identity["key"], access_log=False))
    finished = threading.Event()
    if args.stop_file:
        def watch_shutdown():
            while not finished.wait(.25):
                if args.stop_file.exists():
                    server.should_exit = True
                    return
        threading.Thread(target=watch_shutdown, daemon=True).start()
    try:
        server.run()
    finally:
        finished.set()
        if worker:
            worker.close()
        dashboard_server.should_exit = True
        dashboard_thread.join(timeout=5)
        if zc:
            zc.unregister_service(info)
            zc.close()


if __name__ == "__main__":
    main()

