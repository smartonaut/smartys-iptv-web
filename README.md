# Smartys IPTV Web

Landing-Page und Download-Portal für das Smartys-IPTV-Ökosystem.

## Struktur

- `site-player/` — player.smartys.tech
- `site-camviewer/` — camviewer.smartys.tech
- `site-nginx/` — smartys.tech
- `tools/` — Deploy-Skripte

## Deploy

```bash
./tools/deploy_site.sh <site> <datei> [--force]
```

## Hinweis

DSGVO-konform: Schriften werden lokal gehostet (keine Google-Fonts-CDN).

## Lizenz

MIT — siehe [LICENSE](LICENSE).
