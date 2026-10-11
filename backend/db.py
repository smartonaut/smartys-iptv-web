"""
Smartys IPTV Backend — Datenbank-Layer
SQLite mit feldweiser AES-GCM-Verschlüsselung aller sensiblen Daten
(Xtream-Zugangsdaten, M3U-URLs, Pairing-Passwörter als Argon2-Hash).
"""
import os
import sqlite3
import secrets
import hashlib
import time
import threading
from argon2 import PasswordHasher
import crypto

DB_FILE = os.environ.get("IPTV_DB_FILE", "/data/iptv.db")
ph = PasswordHasher()
_local = threading.local()

SCHEMA = """
CREATE TABLE IF NOT EXISTS players (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    mac TEXT UNIQUE NOT NULL,
    name TEXT NOT NULL,
    pairing_pw_hash TEXT NOT NULL,     -- Argon2-Hash des Pairing-Passworts
    pairing_pw_enc TEXT NOT NULL,     -- AES-verschlüsseltes Klartext-PW (Anzeige nur via Login)
    active INTEGER DEFAULT 0,          -- Freischaltung: 0 = gesperrt, 1 = freigeschaltet
    created_at INTEGER NOT NULL,
    last_seen INTEGER
);
CREATE TABLE IF NOT EXISTS license_tokens (
    token_id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_id INTEGER NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    token TEXT UNIQUE NOT NULL,       -- HMAC-signierter Token
    redeemed_at INTEGER,              -- NULL = unbenutzt
    created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS providers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_id INTEGER NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    label TEXT NOT NULL,
    -- alles Folgende AES-256-GCM verschlüsselt (Master-Key ODER Nutzer-Schlüssel):
    server_url TEXT NOT NULL,
    username TEXT NOT NULL,
    password TEXT NOT NULL,
    epg_url TEXT DEFAULT '',
    active INTEGER DEFAULT 1,
    sort_order INTEGER DEFAULT 0,
    created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
    token_hash TEXT PRIMARY KEY,       -- SHA-256 des Session-Tokens
    player_id INTEGER NOT NULL,
    kind TEXT NOT NULL,                -- 'web' | 'app'
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_providers_player ON providers(player_id);
CREATE INDEX IF NOT EXISTS idx_sessions_player ON sessions(player_id);
CREATE INDEX IF NOT EXISTS idx_tokens_player ON license_tokens(player_id);
"""

# Migration: Sicherheits-Passwort-Spalten (Player-gebundenes Nutzer-Passwort).
# sec_salt = PBKDF2-Salt (base64), sec_verify = AES-GCM-Token zum Prüfen des
# Passworts OHNE es zu speichern. Beide leer => Player nutzt Master-Key-Layer.
MIGRATIONS = [
    ("players", "sec_salt", "TEXT NOT NULL DEFAULT ''"),
    ("players", "sec_verify", "TEXT NOT NULL DEFAULT ''"),
]


def conn() -> sqlite3.Connection:
    """Thread-lokale Verbindung mit WAL-Modus."""
    c = getattr(_local, "c", None)
    if c is None:
        c = sqlite3.connect(DB_FILE, timeout=10)
        c.row_factory = sqlite3.Row
        c.execute("PRAGMA journal_mode=WAL")
        c.execute("PRAGMA foreign_keys=ON")
        _local.c = c
    return c


def normalize_mac(mac: str) -> str:
    """Normalisiert eine Player-ID/MAC auf das kanonische Format
    AA:BB:CC:DD:EE:FF (uppercase, mit Doppelpunkten).
    "32:38:6A:63:74:A0" und "32386A6374A0" werden zum selben Key."""
    if not mac:
        return mac
    hexchars = "0123456789ABCDEF"
    raw = "".join(ch for ch in mac.upper() if ch in hexchars)
    if not raw:
        return mac
    raw = raw[:12]
    return ":".join(raw[i:i + 2] for i in range(0, len(raw), 2))


def init_db():
    c = conn()
    c.executescript(SCHEMA)
    # Spalten-Migration für Bestands-DBs (CREATE TABLE IF NOT EXISTS fügt
    # keine neuen Spalten hinzu — ALTER TABLE schon).
    for table, column, decl in MIGRATIONS:
        cols = [r[1] for r in c.execute("PRAGMA table_info(%s)" % table)]
        if column not in cols:
            c.execute("ALTER TABLE %s ADD COLUMN %s %s" % (table, column, decl))
    c.commit()


# ---------- Player / Pairing ----------

def create_player(mac: str, name: str) -> tuple[str, str]:
    """Legt Player an, erzeugt zufälliges Pairing-Passwort. Rückgabe: (mac, pairing_pw)."""
    pw = secrets.token_urlsafe(9)  # ~12 Zeichen, gut tippen
    c = conn()
    c.execute(
        "INSERT INTO players (mac, name, pairing_pw_hash, pairing_pw_enc, created_at) VALUES (?,?,?,?,?)",
        (mac, name, ph.hash(pw), crypto.encrypt(pw), int(time.time()))
    )
    c.commit()
    return mac, pw


def create_player_with_pw(mac: str, pw: str, name: str = None) -> bool:
    """Selbst-Registrierung (IBPlayer-Modell): Player-ID + Passwort werden
    VOM GERÄT generiert; beim ersten Login/Web-Login wird der Player hiermit
    angelegt. Existiert die ID schon => False (kein Überschreiben!)."""
    c = conn()
    try:
        c.execute(
            "INSERT INTO players (mac, name, pairing_pw_hash, pairing_pw_enc, active, created_at) VALUES (?,?,?,?,1,?)",
            (mac, name or f"Player {mac[-8:]}", ph.hash(pw), crypto.encrypt(pw), int(time.time()))
        )
        c.commit()
        return True
    except sqlite3.IntegrityError:
        return False


def verify_pairing_auto(mac: str, pw: str) -> bool:
    """IBPlayer-Modell: Unbekannte ID => Player wird MIT DIESEM Passwort
    selbst-registriert (aktiv, aber OHNE Playlist bis der User eine im
    Web-Panel zuweist). Bekannte ID => normales Argon2-Verify (Rate-Limit
    greift und schützt vor Brute-Force auf bestehende IDs)."""
    row = conn().execute("SELECT 1 FROM players WHERE mac = ?", (mac,)).fetchone()
    if row is None:
        return create_player_with_pw(mac, pw)
    return verify_pairing(mac, pw)


def verify_pairing(mac: str, pw: str) -> bool:
    row = conn().execute("SELECT pairing_pw_hash, active FROM players WHERE mac = ?", (mac,)).fetchone()
    if row is None:
        # Dummy-Verify gegen Timing-Angriffe
        ph.hash("dummy")
        return False
    if not row["active"]:
        # nicht freigeschaltet => Login verweigert (Dummy-Verify fuer konstante Zeit)
        ph.verify(row["pairing_pw_hash"], pw)
        return False
    try:
        return ph.verify(row["pairing_pw_hash"], pw)
    except Exception:
        return False


def rotate_pairing_pw(mac: str) -> str:
    pw = secrets.token_urlsafe(9)
    c = conn()
    c.execute("UPDATE players SET pairing_pw_hash=?, pairing_pw_enc=? WHERE mac=?",
              (ph.hash(pw), crypto.encrypt(pw), mac))
    c.commit()
    return pw


def delete_player(mac: str):
    c = conn()
    c.execute("DELETE FROM players WHERE mac=?", (mac,))
    c.commit()


def get_player(mac: str) -> dict | None:
    row = conn().execute("SELECT * FROM players WHERE mac=?", (mac,)).fetchone()
    return dict(row) if row else None


def list_players() -> list[dict]:
    rows = conn().execute(
        "SELECT mac, name, active, created_at, last_seen FROM players ORDER BY name"
    ).fetchall()
    return [dict(r) for r in rows]


def set_active(mac: str, active: bool):
    c = conn()
    c.execute("UPDATE players SET active=? WHERE mac=?", (1 if active else 0, mac))
    c.commit()


def save_license_token(player_mac: str, token: str):
    c = conn()
    c.execute(
        "INSERT INTO license_tokens (player_id, token, created_at) VALUES "
        "((SELECT id FROM players WHERE mac=?), ?, ?)",
        (player_mac, token, int(time.time()))
    )
    c.commit()


def mark_token_redeemed(token: str):
    c = conn()
    c.execute("UPDATE license_tokens SET redeemed_at=? WHERE token=?", (int(time.time()), token))
    c.commit()


def touch_player(mac: str):
    c = conn()
    c.execute("UPDATE players SET last_seen=? WHERE mac=?", (int(time.time()), mac))
    c.commit()


# ---------- Sicherheits-Passwort (Nutzer-Schlüssel-Layer) ----------
#
# Ist für einen Player sec_salt/ sec_verify gesetzt, werden seine Provider
# NUR mit dem vom Nutzer gewählten Passwort verschlüsselt. Der Server kennt
# den Klartext dann NICHT mehr (kein Master-Key-Layer) — ohne das Passwort
# ist die Line nicht wiederherstellbar (auch nicht für uns).

SEC_VERIFY_PLAINTEXT = "smartys-sec-password-ok"


def player_has_sec(mac: str) -> bool:
    row = conn().execute("SELECT sec_salt FROM players WHERE mac=?", (mac,)).fetchone()
    return bool(row and row["sec_salt"])


def get_sec_salt(mac: str) -> str:
    row = conn().execute("SELECT sec_salt FROM players WHERE mac=?", (mac,)).fetchone()
    return (row["sec_salt"] if row else "")


def verify_sec_password(mac: str, password: str):
    """Prüft das Sicherheits-Passwort; Rückgabe (ok, key)."""
    row = conn().execute("SELECT sec_salt, sec_verify FROM players WHERE mac=?",
                         (mac,)).fetchone()
    if not row or not row["sec_salt"] or not row["sec_verify"]:
        return False, None
    key = crypto.derive_user_key(password, row["sec_salt"])
    plain = crypto.user_decrypt(key, row["sec_verify"], mac)
    return plain == SEC_VERIFY_PLAINTEXT, key


def set_sec_password(mac: str, password: str):
    """Setzt/rotiert das Sicherheits-Passwort und re-verschlüsselt alle
    bestehenden Provider (Master-Key -> Nutzer-Schlüssel). Rückgabe: key."""
    salt = crypto.new_user_salt()
    key = crypto.derive_user_key(password, salt)
    verify_token = crypto.user_encrypt(key, SEC_VERIFY_PLAINTEXT, mac)
    c = conn()
    # Bestehende Provider vom Master-Key auf den Nutzer-Schlüssel umziehen.
    rows = c.execute(
        "SELECT id, server_url, username, password, epg_url FROM providers "
        "WHERE player_id=(SELECT id FROM players WHERE mac=?)", (mac,)).fetchall()
    for r in rows:
        c.execute(
            "UPDATE providers SET server_url=?, username=?, password=?, epg_url=? WHERE id=?",
            (crypto.user_encrypt(key, crypto.decrypt(r["server_url"]), mac),
             crypto.user_encrypt(key, crypto.decrypt(r["username"]), mac),
             crypto.user_encrypt(key, crypto.decrypt(r["password"]), mac),
             crypto.user_encrypt(key, crypto.decrypt(r["epg_url"]), mac),
             r["id"]))
    c.execute("UPDATE players SET sec_salt=?, sec_verify=? WHERE mac=?",
              (salt, verify_token, mac))
    c.commit()
    return key


def reset_sec_password(mac: str):
    """'Passwort vergessen': Sicherheits-Passwort + ALLE Provider löschen
    (die sind ohne Passwort unwiederbringlich). Line muss neu hinterlegt werden."""
    c = conn()
    c.execute("UPDATE players SET sec_salt='', sec_verify='' WHERE mac=?", (mac,))
    c.execute("DELETE FROM providers WHERE player_id=(SELECT id FROM players WHERE mac=?)",
              (mac,))
    c.commit()


# ---------- Provider (Xtream-Lines) pro Player ----------

class SecLocked(Exception):
    """Player ist Nutzer-Schlüssel-verschlüsselt, aber es wurde kein
    Passwort/Key mitgegeben. Aufrufer muss erst entsperren."""


def _provider_key(mac: str, user_key):
    """Layer-Key bestimmen: Nutzer-Schlüssel wenn gesetzt, sonst Master-Key."""
    if player_has_sec(mac):
        if user_key is None:
            raise SecLocked()
        return user_key
    return None  # None => Master-Key (crypto.encrypt/decrypt)


def add_provider(mac: str, label: str, url: str, user: str, pw: str,
                 epg: str = "", user_key=None) -> int:
    key = _provider_key(mac, user_key)
    if key is not None:
        enc = lambda s: crypto.user_encrypt(key, s, mac)  # noqa: E731
    else:
        enc = crypto.encrypt
    c = conn()
    cur = c.execute(
        "INSERT INTO providers (player_id, label, server_url, username, password, epg_url, created_at) "
        "VALUES ((SELECT id FROM players WHERE mac=?),?,?,?,?,?,?)",
        (mac, label, enc(url), enc(user), enc(pw), enc(epg), int(time.time()))
    )
    c.commit()
    return cur.lastrowid


def update_provider(provider_id: int, label: str, url: str, user: str, pw: str,
                    epg: str, active: bool, user_key=None):
    mac = conn().execute(
        "SELECT p.mac FROM providers pr JOIN players p ON p.id=pr.player_id WHERE pr.id=?",
        (provider_id,)).fetchone()["mac"]
    key = _provider_key(mac, user_key)
    if key is not None:
        enc = lambda s: crypto.user_encrypt(key, s, mac)  # noqa: E731
    else:
        enc = crypto.encrypt
    c = conn()
    c.execute(
        "UPDATE providers SET label=?, server_url=?, username=?, password=?, epg_url=?, active=? WHERE id=?",
        (label, enc(url), enc(user), enc(pw), enc(epg),
         1 if active else 0, provider_id)
    )
    c.commit()


def delete_provider(provider_id: int):
    c = conn()
    c.execute("DELETE FROM providers WHERE id=?", (provider_id,))
    c.commit()


def list_providers(mac: str, user_key=None) -> list[dict]:
    """Für authentifizierte Web-Session: entschlüsselt (Master ODER Nutzer-Key).
    Wirft SecLocked, wenn der Player Nutzer-verschlüsselt ist und kein Key kam."""
    key = _provider_key(mac, user_key)
    rows = conn().execute(
        "SELECT * FROM providers WHERE player_id=(SELECT id FROM players WHERE mac=?) ORDER BY sort_order, id",
        (mac,)
    ).fetchall()
    out = []
    for r in rows:
        d = dict(r)
        if key is not None:
            d["server_url"] = crypto.user_decrypt(key, d["server_url"], mac)
            d["username"] = crypto.user_decrypt(key, d["username"], mac)
            d["password"] = crypto.user_decrypt(key, d["password"], mac)
            d["epg_url"] = crypto.user_decrypt(key, d["epg_url"], mac)
        else:
            d["server_url"] = crypto.decrypt(d["server_url"])
            d["username"] = crypto.decrypt(d["username"])
            d["password"] = crypto.decrypt(d["password"])
            d["epg_url"] = crypto.decrypt(d["epg_url"])
        out.append(d)
    return out


def raw_providers(mac: str) -> list[dict]:
    """Roh-Ciphertexte (Nutzer-Schlüssel-Layer) für die App-API: Die App
    entschlüsselt lokal mit dem Nutzer-Passwort — der Server gibt die
    verschlüsselten Felder 1:1 zurück und kennt den Klartext nie."""
    rows = conn().execute(
        "SELECT * FROM providers WHERE player_id=(SELECT id FROM players WHERE mac=?) "
        "AND active=1 ORDER BY sort_order, id",
        (mac,)
    ).fetchall()
    return [dict(r) for r in rows]


# ---------- Sessions ----------

def create_session(player_id: int, kind: str, ttl_hours: int = 12) -> str:
    token = secrets.token_urlsafe(32)
    c = conn()
    c.execute(
        "INSERT INTO sessions (token_hash, player_id, kind, created_at, expires_at) VALUES (?,?,?,?,?)",
        (hashlib.sha256(token.encode()).hexdigest(), player_id, kind,
         int(time.time()), int(time.time()) + ttl_hours * 3600)
    )
    c.commit()
    return token


def session_player(token: str) -> dict | None:
    if not token:
        return None
    row = conn().execute(
        "SELECT p.*, s.expires_at FROM sessions s JOIN players p ON p.id=s.player_id "
        "WHERE s.token_hash=? AND s.expires_at > ?",
        (hashlib.sha256(token.encode()).hexdigest(), int(time.time()))
    ).fetchone()
    return dict(row) if row else None


def delete_session(token: str):
    c = conn()
    c.execute("DELETE FROM sessions WHERE token_hash=?", (hashlib.sha256(token.encode()).hexdigest(),))
    c.commit()


def cleanup_sessions():
    c = conn()
    c.execute("DELETE FROM sessions WHERE expires_at < ?", (int(time.time()),))
    c.commit()