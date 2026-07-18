# -*- coding: utf-8 -*-
"""
Простой сервер для приёма опасных APK от приложения KiberQalqon.
Запуск: python app.py
По умолчанию: http://0.0.0.0:5000
- POST /upload — принять APK (multipart, поле "apk")
- GET / — список загруженных APK и ссылки на скачивание

Bezopasnost' (qo'shildi 2026-05-26):
- API_KEY env var orqali oddiy bearer token; har bir endpoint tekshiradi
- secure_filename() har bir foydalanuvchi inputi uchun
- LAN'ga e'lon qilingan endpoint'ga ruxsatsiz kirish endi 403 qaytaradi
"""
import os
import hmac
import hashlib
import time
from datetime import datetime
from functools import wraps
from flask import Flask, request, send_from_directory, abort
from werkzeug.utils import secure_filename

UPLOAD_FOLDER = os.path.join(os.path.dirname(__file__), "uploads")
ALLOWED_EXTENSIONS = {"apk"}

# API_KEY env'da bo'lmasa ham server ishlaydi, lekin TASODIFIY token yaratiladi
# va u logga yoziladi. Tokensiz hech kim hech narsa yuklab/oligolmaydi.
API_KEY = os.environ.get("KIBERQALQON_API_KEY")
if not API_KEY:
    import secrets
    API_KEY = secrets.token_urlsafe(24)
    print(f"[WARN] KIBERQALQON_API_KEY not set, generated session token: {API_KEY}")
    print("[WARN] Set env var KIBERQALQON_API_KEY to a stable value for production.")

os.makedirs(UPLOAD_FOLDER, exist_ok=True)
app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 100 * 1024 * 1024  # 100 MB


def require_api_key(fn):
    @wraps(fn)
    def wrapped(*args, **kwargs):
        # Token X-Api-Key header'da yoki Bearer'da yoki ?token= query'da bo'lishi mumkin
        provided = (
            request.headers.get("X-Api-Key")
            or (request.headers.get("Authorization", "").removeprefix("Bearer ").strip())
            or request.args.get("token")
        )
        if not provided or provided != API_KEY:
            return {"ok": False, "error": "unauthorized"}, 401
        return fn(*args, **kwargs)
    return wrapped


def allowed_file(filename):
    return "." in filename and filename.rsplit(".", 1)[1].lower() in ALLOWED_EXTENSIONS


# Qisqa muddatli, faylga bog'langan imzolangan yuklab-olish tokeni.
# Brauzerdan bosib yuklab olish ishlashi uchun HTML havolalarga uzoq muddatli
# API_KEY o'rniga shu token qo'yiladi — u faqat bitta faylga va bir necha
# daqiqaga amal qiladi, shuning uchun loglarga/tarixga tushsa ham zarari cheklangan.
DOWNLOAD_TOKEN_TTL = 300  # sekund


def _make_download_token(filename, ttl=DOWNLOAD_TOKEN_TTL):
    expiry = int(time.time()) + ttl
    msg = f"{filename}:{expiry}".encode("utf-8")
    sig = hmac.new(API_KEY.encode("utf-8"), msg, hashlib.sha256).hexdigest()
    return f"{expiry}.{sig}"


def _valid_download_token(filename, token):
    if not token or "." not in token:
        return False
    expiry_str, _, sig = token.partition(".")
    try:
        expiry = int(expiry_str)
    except ValueError:
        return False
    if expiry < int(time.time()):
        return False
    msg = f"{filename}:{expiry}".encode("utf-8")
    expected = hmac.new(API_KEY.encode("utf-8"), msg, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, sig)


@app.route("/upload", methods=["POST"])
@require_api_key
def upload():
    if "apk" not in request.files:
        return {"ok": False, "error": "no apk file"}, 400
    f = request.files["apk"]
    if f.filename == "":
        return {"ok": False, "error": "empty filename"}, 400
    if not allowed_file(f.filename):
        return {"ok": False, "error": "not apk"}, 400
    name = secure_filename(f.filename)
    if not name:
        return {"ok": False, "error": "invalid filename"}, 400
    base, ext = os.path.splitext(name)
    timestamp = datetime.utcnow().strftime("%Y%m%d_%H%M%S")
    filename = f"{base}_{timestamp}{ext}"
    path = os.path.join(UPLOAD_FOLDER, filename)
    f.save(path)
    return {"ok": True, "file": filename}


@app.route("/")
@require_api_key
def index():
    if not os.path.isdir(UPLOAD_FOLDER):
        return "<h1>KiberQalqon Server</h1><p>Hozircha yuklamalar yo'q.</p>"
    files = sorted(
        [f for f in os.listdir(UPLOAD_FOLDER) if f.lower().endswith(".apk")],
        key=lambda x: os.path.getmtime(os.path.join(UPLOAD_FOLDER, x)),
        reverse=True,
    )
    lines = [
        "<!DOCTYPE html><html><head><meta charset='utf-8'><title>KiberQalqon</title></head><body>",
        "<h1>KiberQalqon — yuklangan xavfli APK fayllar</h1>",
        "<p>Bu yerda ilova telefondan o'chirib serverga yuborgan fayllar ko'rsatiladi.</p>",
        "<ul>",
    ]
    for f in files:
        # Uzoq muddatli API_KEY havolaga QO'YILMAYDI (u loglar/tarix/Referer orqali
        # sizib chiqadi). O'rniga shu faylga bog'langan qisqa muddatli imzolangan token.
        url = f"/download/{f}?dt={_make_download_token(f)}"
        lines.append(f"<li><a href='{url}'>{f}</a></li>")
    lines.append("</ul></body></html>")
    return "\n".join(lines)


@app.route("/download/<filename>")
def download(filename):
    # secure_filename garantiya beradi: hech qanday "../", absolute path yo'q
    safe = secure_filename(filename)
    if not safe or safe != filename or not safe.lower().endswith(".apk"):
        return "Forbidden", 403
    # Auth: to'liq API kalit (header/Bearer/?token=) YOKI shu faylga bog'langan
    # qisqa muddatli imzolangan token (?dt=). require_api_key'ni bu yerda
    # dekorator sifatida ishlatmaymiz, chunki imzolangan tokenni ham qabul qilamiz.
    provided = (
        request.headers.get("X-Api-Key")
        or (request.headers.get("Authorization", "").removeprefix("Bearer ").strip())
        or request.args.get("token")
    )
    key_ok = bool(provided) and hmac.compare_digest(provided, API_KEY)
    if not (key_ok or _valid_download_token(safe, request.args.get("dt"))):
        return {"ok": False, "error": "unauthorized"}, 401
    full_path = os.path.join(UPLOAD_FOLDER, safe)
    if not os.path.isfile(full_path):
        return "Not found", 404
    return send_from_directory(UPLOAD_FOLDER, safe, as_attachment=True)


if __name__ == "__main__":
    # Default 127.0.0.1 — faqat lokal mashina. LAN'ga ochish kerak bo'lsa
    # KIBERQALQON_BIND=0.0.0.0 env'ni o'rnating va albatta API_KEY ham bering.
    bind = os.environ.get("KIBERQALQON_BIND", "127.0.0.1")
    app.run(host=bind, port=5000, debug=False)
