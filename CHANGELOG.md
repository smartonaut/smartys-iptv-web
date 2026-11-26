# Changelog — Smartys Player (Android / Fire TV)

Neueste Aenderungen oben. Versionen ohne eigenen Eintrag waren Zwischenbauten
derselben Aenderungen (Versionsbump waehrend der Diagnose am Geraet).

## 0.8.50 (Build 69) — 26.11.2026

- **Kamera-Wand: Raster-Modus.** Der Server setzt alle Kameras per
  ffmpeg-xstack zu EINEM HLS-Stream zusammen (`/hls/wall/index.m3u8`); das
  Geraet dekodiert einen Strom statt fuenf. Umschalten per MENU/INFO oder
  Knopf — die Bedienleiste blendet nach 6 s aus und ist per Fernbedienung
  nicht zuverlaessig erreichbar.
- **Kein Standby mehr bei stummen Kamera-Streams.** Fire OS hielt das Geraet
  fuer unt taetig und schickte den Fernseher nach ~20 Minuten per CEC in
  Standby. Die Wand meldet jetzt eine laufende Wiedergabe an (MediaSession),
  haelt einen PARTIAL_WAKE_LOCK und spielt einen unhoerbaren 19-Hz-Ton.
- **Basis-Adresse** der Raster-URL wird aus den geladenen Kameras abgeleitet —
  ohne gespeicherten Server war die URL relativ und der Player forderte nie
  etwas an (0 Zugriffe).
- **Firmenserver-Adresse** kommt aus `firma.properties` (Build-Zeit,
  `BuildConfig.FIRMA_SERVER`, nicht versioniert) statt aus dem Quelltext.

## 0.8.47 — 26.11.2026

- Kamera-Wand haelt das Geraet wach (Ton + MediaSession + WakeLock).

## 0.8.44 (Build 63)

- Kamera-Wand, Fokus-Navigation, Vollbild, Quellen-Schalter.
