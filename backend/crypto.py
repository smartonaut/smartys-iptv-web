"""
Smartys IPTV Backend — Crypto-Layer
AES-256-GCM-Feldverschlüsselung für alle sensiblen Spalten.
Der Schlüssel liegt NICHT in der DB, sondern als Datei neben der DB
(bereits auf dem verschlüsselten VPS-Dateisystem, 0600).
"""
import os
import base64
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.backends import default_backend

KEY_FILE = os.environ.get("IPTV_KEY_FILE", "/data/crypto.key")


def _load_key() -> bytes:
    with open(KEY_FILE, "rb") as f:
        master = f.read()
    # HKDF leitet pro Installation einen AES-256-Schlüssel ab
    return HKDF(
        algorithm=hashes.SHA256(), length=32, salt=None,
        info=b"smartys-iptv-field-encryption", backend=default_backend()
    ).derive(master)


def encrypt(plaintext: str) -> str:
    """Verschlüsselt einen String, Ausgabe: base64(nonce||ciphertext)."""
    if plaintext is None:
        return ""
    key = _load_key()
    nonce = os.urandom(12)
    ct = AESGCM(key).encrypt(nonce, plaintext.encode("utf-8"), None)
    return base64.b64encode(nonce + ct).decode("ascii")


def decrypt(token: str) -> str:
    """Entschlüsselt encrypt()-Ausgabe; bei Defekt -> leerer String (nie crashen)."""
    if not token:
        return ""
    try:
        key = _load_key()
        raw = base64.b64decode(token)
        return AESGCM(key).decrypt(raw[:12], raw[12:], None).decode("utf-8")
    except Exception:
        return ""