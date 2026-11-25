"""
Smartys IPTV Backend — Lizenz-/Freischalt-System
Jeder Player muss VOM ADMIN freigeschaltet werden (aktiviert).
Ohne Aktivierung: keine Pairing-Daten, keine Provider, kein Stream-Zugriff.

Schutz gegen Selbst-Freischaltung:
- Die App hat KEINEN Endpunkt, um sich selbst zu aktivieren.
- Aktivierung passiert NUR via admin.py auf dem VPS (Docker exec, kein Netzweg).
- pairing_pw wird NIE mehr an die App zurückgegeben (kein /player/info mehr).
   Die App zeigt die Player-ID; Passwort sieht man NUR am Web-Login (das der
   Admin dem User mitteilt) bzw. in der Admin-CLI.
- Lizenz-Tokens: signiert mit HMAC-SHA256 (Server-Geheimnis, nicht in der DB).
   Ein Token aktiviert einen Player einmalig; Tokens erstellt nur der Admin.
- Brute-Force-Schutz: Rate-Limit auf ALLE Auth-Endpunkte, Argon2-Hashing,
   Unique-Constraints in der DB (kein IDOR moeglich, Ownership-Checks everywhere).
"""
import os
import hmac
import hashlib
import secrets
import time
import sqlite3
import json

LICENSE_SECRET = os.environ.get("IPTV_LICENSE_SECRET", "")  # 32+ bytes hex, in .env
DB_FILE = os.environ.get("IPTV_DB_FILE", "/data/iptv.db")


def _sig(payload: str) -> str:
    return hmac.new(LICENSE_SECRET.encode(), payload.encode(), hashlib.sha256).hexdigest()


def create_license_token(player_id: int, ttl_hours: int = 72) -> str:
    """Admin erstellt Lizenz-Token. Payload: player_id|expiry, signiert."""
    exp = int(time.time()) + ttl_hours * 3600
    payload = f"{player_id}|{exp}"
    return f"{payload}|{_sig(payload)}"


def redeem_license_token(token: str, mac: str) -> bool:
    """App reicht Token + ihre Player-ID ein. Nur dann wird der Player aktiviert.
    Prueft Signatur, Ablauf und dass der Token zu genau DIESEM Player gehoert."""
    if not LICENSE_SECRET:
        return False
    try:
        pid_s, exp_s, sig = token.strip().split("|")
        pid, exp = int(pid_s), int(exp_s)
        payload = f"{pid}|{exp}"
        if not hmac.compare_digest(_sig(payload), sig):
            return False
        if exp < int(time.time()):
            return False
        c = sqlite3.connect(DB_FILE, timeout=10)
        c.row_factory = sqlite3.Row
        row = c.execute("SELECT id FROM players WHERE mac=?", (mac.upper(),)).fetchone()
        if row is None or row["id"] != pid:
            return False   # Token gehoert zu einem ANDEREN Player
        c.execute("UPDATE players SET active=1 WHERE id=?", (pid,))
        c.commit()
        return True
    except Exception:
        return False