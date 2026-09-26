# Smartys IPTV

Eigenes IPTV-System: Android-TV-Player-App + Pairing-Backend + Web-Panel.

## Bestandteile

| Ordner | Was | Tech |
|--------|-----|------|
| `app/` | Player-App (Fire TV / Android TV) | Kotlin, Jetpack Compose, Media3/ExoPlayer |
| `backend/` | Pairing-/Playlist-Backend + Admin-CLI | Python, FastAPI, SQLite |
| `backend/templates/` | Web-Panel (Playlist-Verwaltung) | Jinja2 |
| `site-nginx/` | Landing `smartys.tech` | nginx |
| `traefik-*.yml` | Reverse-Proxy-Konfig (VPS) | Traefik |

## Wie es funktioniert (Pairing-Modell, IBPlayer-Prinzip)

1. Die App generiert beim ersten Start eine **Player-ID** (Zufall, MAC-Format)
   und ein **Pairing-Passwort** — beides verschlüsselt auf dem Gerät gespeichert.
2. Der Nutzer meldet sich auf `player.smartys.tech` mit ID + Passwort an
   und hinterlegt dort seine Xtream-/M3U-Line.
3. Die App ruft ihre Playlist-Konfiguration über die API ab und streamt.

Freischaltung ist getrennt vom Pairing: ein Player muss erst `activate` werden
(siehe Admin-CLI), sonst wird der Login verweigert.

## Backend lokal starten

```bash
cp .env.example .env
# Werte setzen:
#   SESSION_SECRET      openssl rand -hex 32
#   IPTV_LICENSE_SECRET openssl rand -hex 32

docker compose up --build
```

- Backend: `http://127.0.0.1:8100` (Health: `/api/v1/health`)
- Landing: `http://127.0.0.1:8101`

## Admin-CLI (nur auf dem VPS, via docker exec)

```bash
docker exec -it iptv-backend python admin.py add <Player-ID> [Name]
docker exec -it iptv-backend python admin.py list
docker exec -it iptv-backend python admin.py activate <Player-ID>
docker exec -it iptv-backend python admin.py token <Player-ID>     # Lizenz-Token (72h)
docker exec -it iptv-backend python admin.py reset-pw <Player-ID>
```

## App bauen

```bash
./gradlew assembleDebug        # Debug-APK
./gradlew assembleRelease      # Release-APK
```

`local.properties` muss `sdk.dir` auf das Android SDK zeigen.

## Sicherheit

- Pairing-Passwörter: Argon2-Hash (+ AES-verschlüsselte Kopie nur fürs Web-Login)
- Provider-Daten: AES-256-GCM-Feldverschlüsselung, Schlüssel als Datei neben der DB
- Session-Cookies: HttpOnly, Secure, SameSite=Lax
- Rate-Limit auf allen Auth-Endpunkten; Lizenz-Tokens HMAC-signiert
- Admin-Funktionen ausschließlich lokal (docker exec), kein Netzweg
