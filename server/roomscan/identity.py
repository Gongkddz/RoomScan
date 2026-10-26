from __future__ import annotations

import hashlib
import json
import os
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID


def ensure_identity(root: Path) -> dict:
    root.mkdir(parents=True, exist_ok=True)
    identity_path = root / "identity.json"
    cert_path, key_path = root / "server.crt", root / "server.key"
    if not identity_path.exists():
        identity_path.write_text(json.dumps({"server_id": str(uuid.uuid4())}), encoding="utf-8")
    identity = json.loads(identity_path.read_text(encoding="utf-8"))
    if not cert_path.exists() or not key_path.exists():
        key = ec.generate_private_key(ec.SECP256R1())
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "RoomScan " + identity["server_id"])])
        now = datetime.now(UTC)
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
                .public_key(key.public_key()).serial_number(x509.random_serial_number())
                .not_valid_before(now - timedelta(minutes=5)).not_valid_after(now + timedelta(days=3650))
                .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
                .sign(key, hashes.SHA256()))
        key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                               serialization.NoEncryption()))
        cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        if os.name != "nt":
            key_path.chmod(0o600)
    certificate = x509.load_pem_x509_certificate(cert_path.read_bytes())
    identity.update(cert=str(cert_path), key=str(key_path),
                    fingerprint=hashlib.sha256(certificate.public_bytes(serialization.Encoding.DER)).hexdigest())
    return identity

