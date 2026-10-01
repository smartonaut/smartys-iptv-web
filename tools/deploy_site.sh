#!/usr/bin/env bash
# deploy_site.sh — sicheres Deploy der Smartys-IPTV-Statikseiten (Hub/Player/CamViewer).
#
# Zweck: Verhindert das Überschreiben eines neueren VPS-Stands durch einen
# alten lokalen Stand (Incident 01.10.2026). Der VPS ist die WAHRHEIT für die
# site-*/Dateien — dieses Skript erzwingt, dass VOR jedem Deploy der aktuelle
# VPS-Stand gesichert und mit dem lokalen Stand verglichen wird.
#
# Aufruf:  deploy_site.sh <ordner> <datei> [--force]
#   ordner: site-nginx | site-player | site-camviewer  (lokaler + VPS-Name identisch)
#   datei:  index.html | impressum.html | ... (Pfad relativ zum Ordner)
#   --force: Deploy auch bei inhaltlicher Abweichung (nach bewusster Prüfung).
#
# Ablauf:
#   1. VPS-Datei runterziehen und lokal in /tmp spiegeln.
#   2. md5-Vergleich VPS vs. lokal.
#      - identisch:  nichts zu tun.
#      - abweichend: VPS-Stand auf dem VPS nach backups/ sichern, WARNUNG ausgeben,
#                    und NUR mit --force deployen (sonst Abbruch mit exit 3).
#   3. Vor jedem Deploy wird der VPS-Stand zusätzlich nach
#      ~/docker-host/iptv-backend/backups/<ordner>/ timestamp-gesichert.
#   4. scp + docker compose up -d --build <service>.
#   5. curl-Verifikation (HTTP 200).

set -euo pipefail

SSH="ssh -o ConnectTimeout=10 hostinger"
VPS_BASE="/home/smarty/docker-host/iptv-backend"
LOCAL_BASE="$HOME/Projekte/SmartysIPTV"

# Ordner-Name -> Docker-Service-Name (unterscheiden sich!)
declare -A SERVICE_MAP=(
  [site-nginx]="iptv-site"
  [site-player]="player-site"
  [site-camviewer]="camviewer-site"
)
declare -A URL_MAP=(
  [site-nginx]="https://smartys.tech/"
  [site-player]="https://player.smartys.tech/"
  [site-camviewer]="https://camviewer.smartys.tech/"
)

usage() {
  echo "Aufruf: deploy_site.sh <site-nginx|site-player|site-camviewer> <datei> [--force]" >&2
  exit 2
}

[ $# -ge 2 ] || usage
ORDNER="$1"; DATEI="$2"; FORCE=0
[ $# -ge 3 ] && [ "$3" = "--force" ] && FORCE=1

[ -n "${SERVICE_MAP[$ORDNER]:-}" ] || { echo "Unbekannter Ordner: $ORDNER" >&2; usage; }
SERVICE="${SERVICE_MAP[$ORDNER]}"
URL="${URL_MAP[$ORDNER]}"

LOCAL="$LOCAL_BASE/$ORDNER/$DATEI"
VPS="$VPS_BASE/$ORDNER/$DATEI"
TMP_VPS="/tmp/vps_${ORDNER}_$(basename "$DATEI")"

[ -f "$LOCAL" ] || { echo "Lokale Datei fehlt: $LOCAL" >&2; exit 2; }

echo "== 1) VPS-Stand ziehen: $ORDNER/$DATEI"
$SSH "cat '$VPS'" > "$TMP_VPS" || { echo "FEHLER: VPS-Datei nicht lesbar: $VPS" >&2; exit 3; }

LOCAL_MD5=$(md5sum "$LOCAL" | cut -d' ' -f1)
VPS_MD5=$(md5sum "$TMP_VPS" | cut -d' ' -f1)

echo "== 2) Backup des aktuellen VPS-Stands anlegen (immer)"
$SSH "mkdir -p '$VPS_BASE/backups/$ORDNER' && cp '$VPS' '$VPS_BASE/backups/$ORDNER/${DATEI}.$(date +%Y%m%d_%H%M%S)' && echo '   Backup ok'"

if [ "$LOCAL_MD5" = "$VPS_MD5" ]; then
  echo "== 3) Identisch (md5 $LOCAL_MD5). Deploy überspringen — nichts zu tun."
  exit 0
fi

echo ""
echo "⚠️  ABWEICHUNG: VPS-Stand und lokaler Stand unterscheiden sich!"
echo "    VPS   md5: $VPS_MD5"
echo "    lokal md5: $LOCAL_MD5"
echo "    Der VPS-Stand wurde soeben nach backups/ gesichert."
echo ""

if [ "$FORCE" != "1" ]; then
  echo "ABBRUCH. Das Skript überschreibt NICHTS ohne --force."
  echo "Prüfe zuerst, welcher Stand der richtige ist:"
  echo "  diff '$TMP_VPS' '$LOCAL'"
  echo "Falls der LOKALE Stand gewollt ist:  deploy_site.sh $ORDNER $DATEI --force"
  echo "Falls der VPS-Stand der richtige ist, ziehe ihn lokal zurück:"
  echo "  scp hostinger:'$VPS' '$LOCAL'  (dann commit + push)"
  exit 3
fi

echo "== 3) --force gesetzt: deploye lokalen Stand."
scp -o ConnectTimeout=10 "$LOCAL" "hostinger:$VPS"
echo "== 4) Container neu bauen: $SERVICE"
$SSH "cd '$VPS_BASE' && docker compose up -d --build '$SERVICE'"
echo "== 5) Verifikation"
sleep 3
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$URL")
echo "    $URL -> HTTP $CODE"
[ "$CODE" = "200" ] || { echo "FEHLER: HTTP $CODE statt 200" >&2; exit 4; }
echo "✓ Deploy fertig."
