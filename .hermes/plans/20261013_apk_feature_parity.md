# Android-APK Feature-Parität (Desktop 1.5.21 + Enigma2 0.3.0) — Implementierungsplan

> **Für Hermes:** Nativ im Android-Player umsetzen. Desktop/Enigma2 nur LESEND als Vorlage (bindende Regel `regel_eigenstaendige_player`). NUR den Android-Player anfassen.

**Goal:** Android-APK (com.smartys.iptv, aktuell 0.4.4/versionCode 16) auf Feature-Parität mit Desktop (1.5.21) und Enigma2 (0.3.0) bringen.

**Architecture:** Kotlin + Compose + Media3/ExoPlayer 1.8.1, Multi-Provider via CredStore (AES-GCM EncryptedSharedPreferences). Build auf Raiden: JDK17 + SDK35 + Keystore (keystore.properties, gitignored).

**Tech Stack:** Kotlin, Jetpack Compose, Media3 ExoPlayer, Retrofit/Gson, Coil, EncryptedSharedPreferences.

---

## Feature-Abstand (Ist vs. Desktop/Enigma2)

### FEHLT in Android (muss gebaut werden):
1. **Aufnahme** — Live-TV R/S-Aufnahme + EPG-Timer-Aufnahme. Desktop hat recording-daemon, Enigma2 `recorder.py` (ffmpeg -c copy mpegts).
2. **Decoding-Settings** — HW/SW-Decoder-Auswahl + Deinterlace-AUTO. Enigma2 `settings.py` + `enigma_insidious_cpfa` (HW-Decoder kann manche Streams nicht).
3. **Timeshift** — Live-TV pausieren. `CredStore.timeshiftUrl()` + `LiveStream.tv_archive` existieren schon im Code, aber unklar ob verdrahtet.
4. **Sec-Passwort Unlock** — AES-256-GCM + PBKDF2 (Enigma2 `crypto.py`, Windows `SecCrypto.kt` = validierte Referenz). Android nutzt noch Legacy-Klartext-Pfad.
5. **Pairing löschen** — Enigma2 GELB-Taste (`/api/v1/player/delete`).
6. **Sleep-Timer** — `offen_sleeptimer` (in KEINER Version fertig, nur Wunsch — NICHT Teil dieses Auftrags).

### HAT Android schon (nicht anfassen):
- Live-TV + Kanalliste + Kategorien + EPG (XmltvEpg, EpgGrid)
- VOD Filme + Serien mit Detailseiten, TMDb-Metadaten, Trailer, Resume
- VOD-Download (VodDownloader), Downloads-Screen
- ParentalControl, Profiles, CreditsWatcher (Abspann-Erkennung)
- QR-Login/Pairing, Multi-Provider CredStore

---

## Priorität (Smarty-Vorgabe aus Brain `apk_prioritaet`):
1. **Aufnahme** (R/S + EPG-Timer) — ZUERST
2. **Decoding-Settings** (HW/SW, Deinterlace) — danach
3. Timeshift, Sec-PW-Unlock, Pairing-Löschen — danach, je nach Bedarf

---

## Plan (Task-Reihenfolge)

### Task 1: Aufnahme (R/S im Live-Player)
- **Ziel:** ROT/S-Taste im Live-TV startet/stoppt ffmpeg-Aufnahme des aktuellen Streams.
- **Files:** neu `Recorder.kt`; ändern `MainActivity.kt` (Key-Handler Live-TV), `RobustLivePlayer.kt` (aktuelle Stream-URL + Channel-ID exposen).
- **Ansatz:** ffmpeg als System-Binary auf Fire TV? NEIN — Fire TV hat kein ffmpeg. Stattdessen Media3/ExoPlayer kann nicht direkt muxen. Prüfen: ExoPlayer `DefaultAudioSink`/MediaCodec-Transcode ist zu komplex. Realistische Android-Lösung: `MediaCodec`-basierte Aufnahme ODER — wenn Fire-TV-fremd — aufnehmen via ExoPlayer + `Transformer` (Media3 1.8 hat Transformer API für Video-Export).
- **Verifikation:** Aufnahme auf Cube startet, Datei entsteht, Wiedergabe der Datei läuft.

### Task 2: EPG-Timer-Aufnahme
- **Ziel:** Timer für kommende Sendung setzen (aus EPG), läuft auch im Hintergrund.
- **Files:** neu `RecordingScheduler.kt`; ändern `EpgGrid.kt`/`MainActivity.kt` (Timer-Set aus EPG).
- **Ansatz:** WorkManager/AlarmManager + Recorder aus Task 1. Persistenz via SharedPreferences.

### Task 3: Decoding-Settings (HW/SW + Deinterlace)
- **Ziel:** Settings-Option HW/SW-Decoder; bei "Source error"/cpfa-Problemen SW-Fallback.
- **Files:** ändern `RobustLivePlayer.kt` (TrackSelector/Renderer-Auswahl), `MainActivity.kt` (Settings-Screen).
- **Ansatz:** ExoPlayer `MediaCodecSelector` bzw. `setVideoDecoderFactory`. Deinterlace ist bei ExoPlayer nicht trivial — dokumentieren was machbar ist.

### Task 4: Timeshift verdrahten
- **Ziel:** Live-TV pausieren via `timeshiftUrl()`.
- **Files:** ändern `MainActivity.kt` (Pause → Timeshift-URL), `RobustLivePlayer.kt`.

### Task 5: Sec-Passwort Unlock
- **Ziel:** Panel-Sec-Player entschlüsseln (PBKDF2-HMAC-SHA256 310k + AES-256-GCM, AAD=MAC).
- **Files:** neu `SecCrypto.kt`; ändern `CredStore.kt`/Pairing-Logik.
- **Referenz (LESEN):** Windows `SecCrypto.kt` (validiert), Enigma2 `crypto.py`.

### Task 6: Pairing löschen
- **Ziel:** GELB-Taste/Einstellung löscht Line + Pairing + Panel-Eintrag.
- **Files:** ändern `MainActivity.kt`/Pairing, neu Endpoint-Call.

---

## Offene Fragen / Risiken
1. **Aufnahme auf Fire TV ohne ffmpeg** — der wichtigste Architektur-Knackpunkt. Media3 `Transformer` (Video-Editing-API) ist der wahrscheinlichste Weg; muss verifiziert werden dass es auf dem Cube (Android 9, AFTR) läuft.
2. **Deinterlace** — ExoPlayer hat keine native Deinterlace-Option; HW-Decoder macht das meist automatisch. Ggf. als No-Op dokumentieren.
3. **Cube offline/neue IP** — Cube ist jetzt 192.168.4.247 (ADB verbunden). Deployment dort.
4. **Version-Bump** — 0.4.4 → nächste Version, versionCode erhöhen.

## Verifikation gesamt
- Build: `cd ~/Projekte/Smartys Player/Android && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug`
- Install: `adb -s 192.168.4.247:5555 install -r app/build/outputs/apk/debug/app-debug.apk`
- User-Test am Cube (Smarty bestätigt Features live).
