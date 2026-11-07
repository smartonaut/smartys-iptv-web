"""Rytec-EPG als XMLTV für die Player (FireTV, Android, Desktop, webOS).

Quelle: epg.vuplus-community.net (dieselben Dateien, die das Enigma2-Plugin
auf der VU+ schon benutzt). Die beiden Teile werden zu EINEM XMLTV
zusammengeführt und gzip-gecacht; der Endpunkt liefert die Cache-Datei aus.

Bewusst so gebaut:
- kein Provider-Zugang nötig (unabhängig von der Line)
- Kennungen der Kanäle bleiben die Rytec-IDs (z. B. "ARD.de") — die passen
  bei den meisten Lines direkt auf die tvg-id
- Aktualisierung alle 6 Stunden im Hintergrund, Auslieferung aus der Datei
  (kein Warten im Request)
"""
import gzip
import lzma
import os
import threading
import time
import urllib.request
import xml.etree.ElementTree as ET

QUELLEN = [
    "https://epg.vuplus-community.net/rytecDE_Basic.xz",
    "https://epg.vuplus-community.net/rytecDE_Common.xz",
]
CACHE = "/data/rytec_de.xml.gz"
MAX_ALTER_S = 6 * 3600
_sperre = threading.Lock()
_laeuft = False


def _hole(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "SmartysIPTV-EPG/1.0"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def _merge(ziel: str) -> dict:
    """Lädt beide Rytec-Teile, führt sie zusammen und schreibt gzip-XMLTV."""
    tmp = ziel + ".part"
    kanacount = 0
    progcount = 0
    gesehen = set()
    with gzip.open(tmp, "wb") as out:
        out.write(b'<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="Smartys">\n')
        for url in QUELLEN:
            roh = _hole(url)
            xml = lzma.decompress(roh)
            # iterparse auf Bytes; jedes <channel>/<programme> komplett serialisieren
            for event, elem in ET.iterparse(_Bytes(xml), events=("end",)):
                tag = elem.tag
                if tag == "channel":
                    cid = elem.get("id")
                    if cid and cid not in gesehen:
                        gesehen.add(cid)
                        out.write(ET.tostring(elem, encoding="utf-8"))
                        out.write(b"\n")
                        kanacount += 1
                    elem.clear()
                elif tag == "programme":
                    out.write(ET.tostring(elem, encoding="utf-8"))
                    out.write(b"\n")
                    progcount += 1
                    elem.clear()
        out.write(b"</tv>\n")
    os.replace(tmp, ziel)
    return {"kanaele": kanacount, "programme": progcount, "bytes": os.path.getsize(ziel)}


class _Bytes:
    """Kleiner Adapter: ElementTree erwartet eine Datei mit read()."""

    def __init__(self, daten: bytes):
        self._d = daten

    def read(self, n=-1):
        if n is None or n < 0:
            n = len(self._d)
        teil, self._d = self._d[:n], self._d[n:]
        return teil


def _alter() -> float:
    try:
        return time.time() - os.path.getmtime(CACHE)
    except OSError:
        return 1e9


def _im_hintergrund() -> None:
    global _laeuft
    with _sperre:
        if _laeuft:
            return
        _laeuft = True
    try:
        ergebnis = _merge(CACHE)
        print(f"[epg] Rytec aktualisiert: {ergebnis}", flush=True)
    except Exception as e:
        print(f"[epg] Rytec fehlgeschlagen: {type(e).__name__}: {e}", flush=True)
    finally:
        with _sperre:
            _laeuft = False


def datei() -> str | None:
    """Pfad zur Cache-Datei; stößt bei Bedarf eine Aktualisierung an."""
    if _alter() > MAX_ALTER_S:
        threading.Thread(target=_im_hintergrund, daemon=True).start()
    return CACHE if os.path.exists(CACHE) else None


if __name__ == "__main__":
    t0 = time.time()
    print(_merge(CACHE), f"in {time.time() - t0:.0f}s")
