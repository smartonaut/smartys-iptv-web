"""
Smartys IPTV Backend — API + Web-UI
Pairing: App zeigt MAC + Passwort -> Login auf der Webseite -> Playlist verwalten.
Security: Argon2, AES-GCM, Session-Cookies (HttpOnly, Secure), Rate-Limit am Login.
"""
import os
import time
import secrets
from fastapi import FastAPI, Request, Form, HTTPException, Depends
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.templating import Jinja2Templates
from starlette.middleware.sessions import SessionMiddleware
import db
import licensing

app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)  # keine offene API-Doku
app.add_middleware(SessionMiddleware, secret_key=os.environ["SESSION_SECRET"], max_age=12 * 3600,
                   same_site="lax", https_only=True)

BASE = os.path.dirname(os.path.abspath(__file__))
templates = Jinja2Templates(directory=os.path.join(BASE, "templates"))

# ---- simpler In-Memory-Rate-Limit (pro MAC) ----
_login_attempts: dict[str, list[int]] = {}
RATE_LIMIT = 8          # Versuche
RATE_WINDOW = 15 * 60   # pro 15 Minuten


def _rate_limited(mac: str) -> bool:
    now = time.time()
    lst = [t for t in _login_attempts.get(mac, []) if now - t < RATE_WINDOW]
    lst.append(now)
    _login_attempts[mac] = lst
    return len(lst) > RATE_LIMIT


# ---------- Web-UI ----------

@app.get("/", response_class=HTMLResponse)
def login_page(request: Request):
    if request.session.get("player_mac"):
        return RedirectResponse("/players", status_code=302)
    return templates.TemplateResponse("login.html", {"request": request, "error": None})


@app.post("/login")
def login(request: Request, mac: str = Form(...), password: str = Form(...)):
    mac = mac.strip().upper()
    if _rate_limited(mac):
        raise HTTPException(status_code=429, detail="Zu viele Versuche. Bitte später erneut.")
    if not db.verify_pairing_auto(mac, password):
        return templates.TemplateResponse(
            "login.html", {"request": request, "error": "MAC oder Passwort falsch."},
            status_code=401)
    player = db.get_player(mac)
    request.session["player_mac"] = mac
    request.session["player_name"] = player["name"]
    return RedirectResponse("/players", status_code=302)


def _require_session(request: Request):
    mac = request.session.get("player_mac")
    if not mac:
        raise HTTPException(status_code=303, headers={"Location": "/"})
    return mac


def _players_ctx(request: Request, mac: str) -> dict:
    return {"mac": mac, "name": request.session.get("player_name"),
            "providers": db.list_providers(mac), "saved": None}


@app.get("/players", response_class=HTMLResponse)
def players_page(request: Request):
    mac = request.session.get("player_mac")
    if not mac:
        return RedirectResponse("/", status_code=302)
    providers = db.list_providers(mac)
    return templates.TemplateResponse(
        "players.html",
        {"request": request, "mac": mac, "name": request.session.get("player_name"),
         "providers": providers, "saved": request.query_params.get("saved")})


def parse_m3u_url(m3u_url: str):
    """Extrahiert (server_url, username, password) aus einer M3U/Xtream-URL.
    Unterstützt:
      - http://host:port/get.php?username=X&password=Y&type=m3u_plus
      - http://host:port/user/Y/...  (nicht-Xtream: nur server_url nutzbar)
    Rückgabe None, wenn die URL keine Xtream-Credentials enthält."""
    from urllib.parse import urlparse, parse_qs
    try:
        p = urlparse(m3u_url.strip())
        if not p.scheme or not p.hostname:
            return None
        base = f"{p.scheme}://{p.netloc}"
        if p.path and "get.php" in p.path:
            qs = parse_qs(p.query)
            user = qs.get("username", [None])[0]
            pw = qs.get("password", [None])[0]
            if user and pw:
                return base, user, pw
        return None
    except Exception:
        return None


@app.post("/providers/add")
def provider_add(request: Request, label: str = Form(...),
                 server_url: str = Form(""), username: str = Form(""),
                 password: str = Form(""), epg_url: str = Form(""),
                 m3u_url: str = Form("")):
    mac = _require_session(request)
    # M3U-Modus: volle URL inkl. User/Pass eingetragen =>
    # Felder automatisch ableiten (server_url/username/password leer lassen).
    if m3u_url.strip() and not (server_url.strip() and username.strip()):
        parsed = parse_m3u_url(m3u_url)
        if not parsed:
            return templates.TemplateResponse(
                "players.html",
                {"request": request, "error": "M3U-URL enthält keine erkennbaren Xtream-Zugangsdaten (erwartet: get.php?username=…&password=…).",
                 **_players_ctx(request, mac)},
                status_code=400)
        server_url, username, password = parsed
    if not (server_url.strip() and username.strip() and password):
        return templates.TemplateResponse(
            "players.html",
            {"request": request, "error": "Bitte entweder Xtream-Codes (Server/User/Passwort) oder eine vollständige M3U-URL angeben.",
             **_players_ctx(request, mac)},
            status_code=400)
    label = (label.strip() or "Meine Line")
    db.add_provider(mac, label, server_url.strip(), username.strip(), password, epg_url.strip())
    return RedirectResponse("/players?saved=1", status_code=302)


@app.post("/providers/{pid}/delete")
def provider_delete(request: Request, pid: int):
    _require_session(request)
    # Ownership prüfen: Provider muss zum eingeloggten Player gehören
    mac = request.session.get("player_mac")
    rows = db.list_providers(mac)
    if not any(r["id"] == pid for r in rows):
        raise HTTPException(404)
    db.delete_provider(pid)
    return RedirectResponse("/players", status_code=302)


@app.post("/providers/{pid}/update")
def provider_update(request: Request, pid: int, label: str = Form(...), server_url: str = Form(...),
                    username: str = Form(...), password: str = Form(...), epg_url: str = Form(""),
                    active: str = Form(None)):
    mac = request.session.get("player_mac")
    rows = db.list_providers(mac)
    if not any(r["id"] == pid for r in rows):
        raise HTTPException(404)
    db.update_provider(pid, label.strip(), server_url.strip(), username.strip(), password,
                       epg_url.strip(), active == "on")
    return RedirectResponse("/players?saved=1", status_code=302)


@app.get("/logout")
def logout(request: Request):
    request.session.clear()
    return RedirectResponse("/", status_code=302)


# ---------- App-API (Pairing / Konfiguration abrufen) ----------

@app.post("/api/v1/pair")
async def api_pair(mac: str = None, password: str = None, request: Request = None):
    """App authentifiziert sich mit MAC+PW, bekommt Session-Token."""
    if mac is None or password is None:
        try:
            body = await request.form()
            mac = mac or body.get("mac")
            password = password or body.get("password")
        except Exception:
            pass
    if not mac or not password:
        raise HTTPException(422, "mac und password erforderlich")
    mac = mac.strip().upper()
    if _rate_limited("api:" + mac):
        raise HTTPException(429)
    if not db.verify_pairing_auto(mac, password):
        raise HTTPException(401, "Ungültige Pairing-Daten")
    player = db.get_player(mac)
    token = db.create_session(player["id"], kind="app")
    db.touch_player(mac)
    return {"token": token, "name": player["name"], "expires_in": 12 * 3600}


@app.get("/api/v1/providers")
def api_providers(request: Request):
    """App zieht ihre Playlist-Konfiguration (Header: X-Session-Token)."""
    token = request.headers.get("X-Session-Token", "") or \
            request.headers.get("Authorization", "").removeprefix("Bearer ").strip()
    player = db.session_player(token)
    if not player:
        raise HTTPException(401)
    db.touch_player(player["mac"])
    provs = db.list_providers(player["mac"])
    return {"name": player["name"], "providers": [
        {"id": p["id"], "label": p["label"], "server_url": p["server_url"],
         "username": p["username"], "password": p["password"],
         "epg_url": p["epg_url"], "active": bool(p["active"])}
        for p in provs if p["active"]
    ]}


@app.get("/api/v1/health")
def health():
    return {"ok": True}


@app.get("/api/v1/player/info")
def api_player_info(mac: str):
    """App fragt ihren Aktivierungs-Status ab.
    SICHERHEIT: Gibt NIEMALS das Pairing-Passwort zurueck (das sah/frueher die
    unsichere Variante). Nur: existiert der Player + ist er freigeschaltet?
    Passwort sieht der User nur im Web-Login bzw. via Admin-CLI."""
    p = db.get_player(mac.strip().upper())
    if not p:
        return {"mac": mac.upper(), "exists": False, "active": False}
    return {"mac": p["mac"], "exists": True, "active": bool(p["active"]),
            "name": p["name"]}


@app.post("/api/v1/redeem")
async def api_redeem(request: Request):
    """App reicht Lizenz-Token ein (den NUR der Admin erstellen kann) =>
    Player wird freigeschaltet. Ohne gueltigen Token keine Freischaltung.
    Tokens sind HMAC-signiert, an genau EINEN Player gebunden und befristet."""
    try:
        body = await request.json()
    except Exception:
        body = {}
    token = str(body.get("token", ""))[:200]
    player_id = str(body.get("player_id", "")).strip().upper()[:40]
    if _rate_limited("redeem:" + player_id):
        raise HTTPException(429)
    if licensing.redeem_license_token(token, player_id):
        db.mark_token_redeemed(token)
        db.touch_player(player_id)
        return {"ok": True, "active": True}
    raise HTTPException(403, "Lizenz ungültig, abgelaufen oder für einen anderen Player")


SUPPORT_FILE = os.environ.get("IPTV_SUPPORT_FILE", "/data/support.log")


@app.post("/api/v1/support")
async def api_support(request: Request):
    """Support-Anfrage aus der App -> Log-Datei (Admin liest /data/support.log)."""
    import datetime
    try:
        body = await request.json()
    except Exception:
        body = {}
    mac = str(body.get("mac", "unbekannt"))[:40]
    msg = str(body.get("message", ""))[:2000]
    version = str(body.get("app_version", "?"))[:20]
    ts = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    entry = f"[{ts}] Player={mac} v{version}\n{msg}\n{'-'*50}\n"
    with open(SUPPORT_FILE, "a", encoding="utf-8") as f:
        f.write(entry)
    return {"ok": True}


@app.on_event("startup")
def _startup():
    db.init_db()