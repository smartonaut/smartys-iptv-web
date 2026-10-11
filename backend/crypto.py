"""
Smartys IPTV Backend — Crypto-Layer
AES-256-GCM-Feldverschlüsselung für alle sensiblen Spalten.
Der Schlüssel liegt NICHT in der DB, sondern als Datei neben der DB
(bereits auf dem verschlüsselten VPS-Dateisystem, 0600).

Zusätzlich (Sicherheits-Passwort-Feature): pro-Player-Verschlüsselung mit
einem vom NUTZER gewählten Passwort (PBKDF2-HMAC-SHA256 -> AES-256-GCM,
AAD = Player-MAC). Für so geschützte Player kennt der Server den Klartext
NICHT mehr — ohne das Nutzer-Passwort ist die Line nicht wiederherstellbar.
"""
import os
import base64
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.backends import default_backend

# KDF-Parameter — identisch in Enigma2-Plugin, Desktop- und Android-App.
USER_KDF_ITERATIONS = 310_000


def _aad(mac: str) -> bytes:
    """AAD bindet den Ciphertext an genau EINEN Player (MAC)."""
    return b"smartys-iptv-sec:" + (mac or "").encode("utf-8")


def new_user_salt() -> str:
    return base64.b64encode(os.urandom(16)).decode("ascii")


def derive_user_key(password: str, salt_b64: str) -> bytes:
    """PBKDF2-HMAC-SHA256, 32-Byte-Schlüssel aus Nutzer-Passwort + Salt."""
    salt = base64.b64decode(salt_b64)
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(), length=32, salt=salt,
        iterations=USER_KDF_ITERATIONS, backend=default_backend())
    return kdf.derive(password.encode("utf-8"))


def user_encrypt(key: bytes, plaintext: str, mac: str) -> str:
    """Verschlüsselt mit Nutzer-Schlüssel. Ausgabe: base64(nonce||ciphertext)."""
    if plaintext is None:
        return ""
    nonce = os.urandom(12)
    ct = AESGCM(key).encrypt(nonce, plaintext.encode("utf-8"), _aad(mac))
    return base64.b64encode(nonce + ct).decode("ascii")


def user_decrypt(key: bytes, token: str, mac: str) -> str:
    """Entschlüsselt user_encrypt()-Ausgabe; bei Defekt leerer String."""
    if not token:
        return ""
    try:
        raw = base64.b64decode(token)
        return AESGCM(key).decrypt(raw[:12], raw[12:], _aad(mac)).decode("utf-8")
    except Exception:
        return ""

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