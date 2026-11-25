# OSD-Parität: Android-Styling auf Desktop + Enigma2 übertragen

## Ziel
Das durchsichtige, gestylte Live-TV-OSD der Android-Version (Referenz) auf
Desktop und Enigma2 übertragen, damit alle drei Player gleich aussehen.

## Android-OSD (Referenz — MainActivity.kt, PlayerScreen, showOverlay-Block)
1. Panel: `0x660B0E14` = 40% alpha Dunkelblau, abgerundete Ecken (shapes.large),
   links unten, Breite ~520dp, padding 24/18dp.
2. Zeile 1: Senderlogo (92x52) + Kanalname (24sp bold, 0xFFE8EEFC) + Badges
   (● REC rot, "→ Kanal N" gold).
3. Unterzeile: "Kanal X · Kategorie" (13sp, 0xFF6B7A99).
4. EPG-Zeile: Startzeit gold (0xFFFFC94D) + Titel weiß (0xFFE8EEFC) +
   Endzeit grau (0xFF6B7A99).
5. Goldener Fortschrittsbalken (4dp, track 0xFF232C42, fill 0xFFFFC94D).
6. "Danach: HH:MM Titel" (13sp, 0xFF8FA3C8).
7. Hilfezeile (11sp, 0xFF5A6B8C).
8. Replay-Button falls hasArchive().

## Enigma2 (streamplayer.py) — Gap
Hat bereits: infobar_bg (#a0000000), logo, nowchannel, nowtitle (gold),
nowtime, videoinfo, progress (gold), help.
Fehlt (an Android angleichen):
- "Danach: HH:MM Titel"-Zeile (epg_next vorhanden, wird nicht angezeigt).
- Kategorie-Unterzeile ("Kanal N · Kategorie") — Kategorie muss durchgereicht werden.
- REC-Badge im OSD (Aufnahme-Laufanzeige).
- Optik: volle Breite + flach → linksbündiges Panel mit Abstand (wie Android).
- Transparenz: #a0000000 (62%) → an Android 40% angleichen (#660B0E14-Ton).

## Desktop (Players.kt) — Gap (größter)
Aktuell: nur VLC-Marquee-Text ("Kanalname — EPG Uhrzeit" + ▬-Balken) +
separates Logo-Overlay. Kein Panel, kein Fortschrittsbalken, kein "Danach".
Technische Hürde: Video rendert im heavyweight Canvas (VLC/mpv), der Compose-
Overlays verdeckt. JWindow-Transparenz scheitert unter XWayland (Kommentar im
Code). Ansätze prüfen:
  a) VLC-OSD/Marquee um "Danach"-Zeile + strukturiertere Farben erweitern
     (limitiert: nur Text, kein echtes Panel).
  b) Separates transparentes JWindow (bisher gescheitert).
  c) VLC `osd` mit Logo+Text im Bild (wie jetziges showLogo, nur kombiniert).

## Reihenfolge
1. Enigma2 streamplayer.py anpassen (klar definiert, sofort testbar am Gerät).
2. Desktop: Hürde klären → besten Ansatz umsetzen.

## Regel
Android bleibt Referenz (nur lesen). Desktop/Enigma2 werden angepasst.
