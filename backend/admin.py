#!/usr/bin/env python3
"""
Smartys IPTV — Admin-CLI
AUSSCHLIESSLICH auf dem VPS via docker exec (kein Netzweg => von aussen
nicht erreichbar). Verwaltet Player, Freischaltungen und Lizenz-Tokens.

Befehle:
  add <Player-ID> [Name]     Player anlegen (Player-ID zeigt die App an)
  list                       Player + Freischalt-Status
  activate <Player-ID>       Freischalten (direkt, ohne Token)
  deactivate <Player-ID>     Sperren
  token <Player-ID>          Lizenz-Token erstellen (72h gueltig, einmalig)
  del <Player-ID>            Loeschen
  reset-pw <Player-ID>       Neues Pairing-Passwort
"""
import sys
import db
import licensing


def main():
    db.init_db()
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    cmd = sys.argv[1]
    if cmd == "add":
        if len(sys.argv) < 3:
            print("add <Player-ID> [Name]"), sys.exit(1)
        mac = sys.argv[2].strip().upper()
        name = " ".join(sys.argv[3:]) or f"Player {mac[-8:]}"
        _, pw = db.create_player(mac, name)
        print(f"ANGELEGT: {mac} ({name}) — Status: NICHT freigeschaltet")
        print(f"Pairing-Passwort: {pw}")
        print("Freischalten mit: activate " + mac)
    elif cmd == "list":
        for p in db.list_players():
            status = "FREIGESCHALTET" if p["active"] else "GESPERRT"
            print(f"{p['mac']}  [{status}]  {p['name']}  (zuletzt aktiv: {p['last_seen'] or 'nie'})")
    elif cmd == "activate":
        mac = sys.argv[2].strip().upper()
        db.set_active(mac, True)
        print("Freigeschaltet:", mac)
    elif cmd == "deactivate":
        mac = sys.argv[2].strip().upper()
        db.set_active(mac, False)
        print("Gesperrt:", mac)
    elif cmd == "token":
        mac = sys.argv[2].strip().upper()
        p = db.get_player(mac)
        if not p:
            print("Player nicht gefunden:", mac); sys.exit(1)
        token = licensing.create_license_token(p["id"], ttl_hours=72)
        db.save_license_token(mac, token)
        print(f"Lizenz-Token für {mac} (72h gültig, einmalig):")
        print(token)
    elif cmd == "del":
        db.delete_player(sys.argv[2].strip().upper())
        print("Gelöscht:", sys.argv[2])
    elif cmd == "reset-pw":
        mac = sys.argv[2].strip().upper()
        pw = db.rotate_pairing_pw(mac)
        print(f"Neues Pairing-Passwort für {mac}: {pw}")
    else:
        print("Unbekannt:", cmd)


if __name__ == "__main__":
    main()