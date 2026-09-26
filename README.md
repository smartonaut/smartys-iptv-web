# Smartys M3U Player

Eigenes IPTV-System: Android-TV-Player-App + Pairing-Backend + Web-Panel.

## Bestandteile

| Ordner | Was | Tech |
|--------|-----|------|
| `app/` | Player-App (Fire TV / Android TV) | Kotlin, Jetpack Compose, Media3/ExoPlayer |
| `backend/` | Pairing-/Playlist-Backend + Admin-CLI | Python, FastAPI, SQLite |
| `backend/templates/` | Web-Panel (Playlist-Verwaltung) | Jinja2 |
| `site-nginx/` | Marken-Hub `smartys.tech` | nginx |
| `site-player/` | Produktseite `player.smartys.tech` | nginx |
| `traefik-*.yml` | Reverse-Proxy-Konfig (VPS) | Traefik |

## Domains / Routing

| Host | Zweck | Container (Port) |
|------|-------|------------------|
| `smartys.tech` | Marken-Hub (Übersicht aller Apps) | iptv-site (8101) |
| `player.smartys.tech` | Produktseite M3U Player | player-site (8102) |
| `panel.smartys.tech` | Web-Panel + App-API | iptv-backend (8100) |

## Wie es funktioniert (Pairing-Modell, IBPlayer-Prinzip)

1. Die App generiert beim ersten Start eine **Player-ID** (Zufall, MAC-Format)
   und ein **Pairing-Passwort** — beides verschlüsselt auf dem Gerät gespeichert.
2. Der Nutzer meldet sich auf `panel.smartys.tech` mit ID + Passwort an
   und hinterlegt dort seine Xtream-/M3U-Line.
3. Die App ruft ihre Playlist-Konfiguration über die API ab und streamt.

Freischaltung ist getrennt vom Pairing: ein Player muss erst `activate` werden
(siehe Admin-CLI), sonst wird der Login verweigert.

## App-Features (Stand v0.4.0)

**Live-TV**
- Kategorie-Sidebar + Kanalliste mit echten Sendernlogos, EPG („Jetzt/Danach"),
  Favoriten, Zuletzt-gesehen-Reihe, Kanaldirektwahl per Zifferntasten
- Robuster Player (Auto-Reconnect, Puffer-Tuning), OSD unten links
- Doppel-EPG-Quellen: Provider-XMLTV + epg.pw-Fallback (Disk-Cache, 12h/daily Refresh)
- EPG-Grid (Tagesübersicht), Parental Control (PIN-Gate, FSK-Bewertung)

**VOD (Filme & Serien)**
- Cover-Grid mit Gold-Fokus (Rahmen nur um das fokussierte Cover)
- Film-/Serien-Detailseiten: Cover, Jahr, Kategorie, Sterne-Bewertung, Trailer-Button
- **TMDB-Beschreibungen** (deutsch) über Backend-Proxy — der TMDB-API-Key liegt
  ausschließlich im Backend-Env (`TMDB_API_KEY`), nie in der App; 24h-Disk-Cache
- **Kategorie-Verwaltung**: OK-Taste auf einer VOD-Kategorie lange drücken
  => Menü „Nach oben / Nach unten / Ausblenden / Alle einblenden"
  (Sortierung + Ausblendung pro Bereich Filme/Serien persistiert)
- Kategoriewahl + Suche überleben das Öffnen/Schließen einer Detailseite
- **Fortsetzen**: Abbruchposition wird pro Film/Episode gespeichert; beim
  erneuten Öffnen springt der Titel an die letzte Stelle (Button „▶ Fortsetzen"),
  daneben „↺ Von vorne" zum Neustart. Titel zu >95 % durchgespielt gelten als fertig.
- Episoden-Liste mit Gold-Fokus-Rahmen am gewählten Eintrag

**Player / Stabilität**
- VOD-Reconnect springt an die aktuelle Position (nicht auf 0); zu Ende
  gespielte VOD-Titel starten nicht neu
- Moderater Puffer (20–40 s) statt 120 s: verhindert, dass 4K-VOD
  (~35 Mbit/s) auf 2-GB-Geräten den CMA-/Heap-Speicher leersaugt und den
  Hardware-Decoder mit „Source error" aussteigen lässt

**Pairing / Account**
- Pairing-Screen beim ersten Start (Player-ID + Passwort, beides gerätegeneriert)
- Selbst-Registrierung beim ersten Web-Login (IBPlayer-Modell)
- Einstellungen: Player-ID/Status, Cache-Größe + Löschen, Support-Formular

## Git / Repo

- Lokales Repo + Bare-Backup auf dem VPS: `origin = smarty@10.13.37.1:git/smartys-iptv.git`
  (kein GitHub). Nach Änderungen: `git push`.

## Backend lokal starten

```bash
cp .env.example .env
# Werte setzen:
#   SESSION_SECRET      openssl rand -hex 32
#   IPTV_LICENSE_SECRET openssl rand -hex 32

docker compose up --build
```

- Backend (Panel/API): `http://127.0.0.1:8100` (Health: `/api/v1/health`)
- Hub: `http://127.0.0.1:8101`
- Produktseite: `http://127.0.0.1:8102`

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
