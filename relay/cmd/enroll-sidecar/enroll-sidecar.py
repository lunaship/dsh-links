#!/usr/bin/env python3
"""dsh-links enroll sidecar - public invite dispenser in front of loopback Control.
Runs on the Relay host (same machine as control), holds the admin bearer
token locally, exposes a rate-limited public API for the DSH Web UI button.
Returns the full enroll paste string so the plugin pastes in one step.
  GET  /health              -> {"ok": true}
  POST /api/official-invite -> {"enroll","inviteCode","expiresAt","expiresIn"}
Only stdlib. Python >= 3.8.
"""
import json, os, sqlite3, ssl, threading, time, urllib.request, urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CONTROL_URL = os.environ.get("CONTROL_URL", "https://127.0.0.1:8080").rstrip("/")
_admin_token = os.environ.get("ADMIN_TOKEN", "")
_token_file = os.environ.get("ADMIN_TOKEN_FILE", "")
if not _admin_token and _token_file:
    try:
        with open(_token_file) as f:
            _admin_token = f.read().strip()
    except OSError:
        pass
ADMIN_TOKEN = _admin_token
BIND = os.environ.get("BIND", "127.0.0.1")
PORT = int(os.environ.get("PORT", "8787"))
DB = os.environ.get("DB", os.path.join(os.path.dirname(os.path.abspath(__file__)), "enroll-sidecar.db"))
IP_WINDOW = int(os.environ.get("IP_LIMIT_WINDOW_S", "86400"))
IP_MAX = int(os.environ.get("IP_LIMIT_MAX", "1"))
GLOBAL_PER_MIN = int(os.environ.get("GLOBAL_PER_MIN", "20"))
INVITE_TTL = os.environ.get("INVITE_TTL", "8h")
TURNSTILE_SECRET = os.environ.get("TURNSTILE_SECRET", "")
CORS_ORIGINS = os.environ.get("CORS_ORIGINS", "*")

_ctx = ssl.create_default_context()
_ctx.check_hostname = False
_ctx.verify_mode = ssl.CERT_NONE
_db_lock = threading.Lock()

def db():
    con = sqlite3.connect(DB, timeout=10)
    con.execute("CREATE TABLE IF NOT EXISTS grants (ip TEXT PRIMARY KEY, count INTEGER, window_start INTEGER)")
    con.execute("CREATE TABLE IF NOT EXISTS hits (ts INTEGER)")
    return con

def client_ip(handler):
    peer = handler.client_address[0] if handler.client_address else ""
    fwd = handler.headers.get("CF-Connecting-IP") or handler.headers.get("X-Forwarded-For") or ""
    if fwd and (peer.startswith("127.") or peer == "::1"):
        return fwd.split(",")[0].strip()
    return peer

def turnstile_ok(token, ip):
    if not TURNSTILE_SECRET:
        return True
    if not token:
        return False
    try:
        req = urllib.request.Request(
            "https://challenges.cloudflare.com/turnstile/v0/siteverify",
            data=json.dumps({"secret": TURNSTILE_SECRET, "response": token, "remoteip": ip}).encode(),
            headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as r:
            return bool(json.loads(r.read().decode()).get("success"))
    except Exception:
        return False

def mint_invite():
    req = urllib.request.Request(
        CONTROL_URL + "/v1/invites",
        data=json.dumps({"ttl": INVITE_TTL}).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + ADMIN_TOKEN})
    try:
        with urllib.request.urlopen(req, context=_ctx, timeout=15) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, {"error": "control_%d" % e.code}

class H(BaseHTTPRequestHandler):
    server_version = "enroll-sidecar/1"
    def log_message(self, *a):
        pass
    def _cors(self):
        o = self.headers.get("Origin") or ""
        allow = "*"
        if CORS_ORIGINS != "*":
            allowed = [x.strip() for x in CORS_ORIGINS.split(",") if x.strip()]
            allow = o if o in allowed else (allowed[0] if allowed else "none")
        self.send_header("Access-Control-Allow-Origin", allow)
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Max-Age", "600")
    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self._cors()
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()
    def do_GET(self):
        if self.path == "/health":
            return self._json(200, {"ok": True, "ts": int(time.time())})
        return self._json(404, {"error": "not_found"})
    def do_POST(self):
        if self.path != "/api/official-invite":
            return self._json(404, {"error": "not_found"})
        length = int(self.headers.get("Content-Length") or 0)
        if length > 8192:
            return self._json(413, {"error": "body_too_large"})
        try:
            body = json.loads(self.rfile.read(length).decode() or "{}") if length else {}
        except Exception:
            return self._json(400, {"error": "bad_json"})
        ip = client_ip(self)
        now = int(time.time())
        if not turnstile_ok(body.get("turnstileToken", ""), ip):
            return self._json(403, {"error": "turnstile_required"})
        with _db_lock:
            con = db()
            try:
                con.execute("DELETE FROM hits WHERE ts < ?", (now - 60,))
                n = con.execute("SELECT COUNT(*) FROM hits").fetchone()[0]
                if n >= GLOBAL_PER_MIN:
                    return self._json(429, {"error": "busy_retry_later"})
                row = con.execute("SELECT count, window_start FROM grants WHERE ip=?", (ip,)).fetchone()
                if row and now - row[1] < IP_WINDOW and row[0] >= IP_MAX:
                    return self._json(429, {"error": "already_issued", "retryAfter": IP_WINDOW - (now - row[1])})
            finally:
                con.close()
        if not ADMIN_TOKEN:
            return self._json(500, {"error": "sidecar_misconfigured"})
        code, data = mint_invite()
        if code == 409:
            return self._json(503, {"error": "relay_full_retry_later"})
        if code != 200 or not data.get("enroll"):
            return self._json(502, {"error": "control_error"})
        with _db_lock:
            con = db()
            try:
                row = con.execute("SELECT count, window_start FROM grants WHERE ip=?", (ip,)).fetchone()
                if row and now - row[1] < IP_WINDOW:
                    con.execute("UPDATE grants SET count=count+1 WHERE ip=?", (ip,))
                else:
                    con.execute("REPLACE INTO grants VALUES (?,?,?)", (ip, 1, now))
                con.execute("INSERT INTO hits VALUES (?)", (now,))
                con.commit()
            finally:
                con.close()
        out = {"enroll": data["enroll"], "inviteCode": data.get("inviteCode", ""),
               "expiresAt": data.get("expiresAt"), "expiresIn": data.get("expiresIn", INVITE_TTL)}
        if data.get("controlUrl"):
            out["controlUrl"] = data["controlUrl"]
        return self._json(200, out)

if __name__ == "__main__":
    if not ADMIN_TOKEN:
        print("WARN: ADMIN_TOKEN empty - /api/official-invite will 500 until set")
    print("enroll-sidecar -> control=%s bind=%s:%d db=%s" % (CONTROL_URL, BIND, PORT, DB))
    ThreadingHTTPServer((BIND, PORT), H).serve_forever()
