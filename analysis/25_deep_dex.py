"""Stage 25: DEEP DEX exploration — find every method that:
- Loads dex (DexClassLoader, InMemoryDexClassLoader, defineClass)
- Saves files (FileOutputStream, ByteArrayOutputStream + write)
- Does HTTP (HttpURLConnection, OkHttp, URL.openConnection, Socket)
- Does byte-array XOR WITHOUT Base64 (i.e. stream-XOR)
- Uses reflection (Class.forName, getMethod, invoke)
- Has long byte-array literals (>= 16 bytes — potential AES key/IV)
"""
import os, sys, io, re, json, logging, struct
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from loguru import logger
logger.remove()
logging.disable(logging.CRITICAL)
from androguard.misc import AnalyzeAPK

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

# Decryption keys discovered earlier (to decrypt string literals on the fly)
import base64
KEYS = {
    "RASMLAR_18_fixed.apk":          {"base": b"JYTAs0m31lxvwkQE42Y10Ktm",        "top": b"3183701586F97GhYNSURErMMwPeAS33H"},
    "RASMLAR_8_fixed.apk":           {"base": b"UmmYqVRrK46tVxAG5PDVUI5rSF72a6p1", "top": b"551712693drkGqsgO05tb31vbvZGkA0P"},
    "toydanfotolar9jpg_10_fixed.apk":{"base": b"AVbmxP9CNlQZRrzvnJhFw92n",         "top": b"347692886QFqc74H0w07DWz51sMHjqB4"},
}
for f in os.listdir(UNPACK):
    if f.startswith("VID_23856") and f.endswith("_fixed.apk"):
        KEYS[f] = {"base": b"C49yA8hXYvs5cz7wGeEX7cQLTzYn", "top": b"358200732kyGoz80aYtUropyXbvrXv9h"}

def decrypt_string(s, key):
    try:
        pad = (-len(s)) % 4
        raw = base64.b64decode(s + "="*pad, validate=False)
    except Exception:
        return None
    pt = bytes(b ^ key[i%len(key)] for i, b in enumerate(raw))
    # check valid utf-8 and printable
    try:
        decoded = pt.decode("utf-8")
        printable_ratio = sum(1 for c in decoded if 32 <= ord(c) < 127 or c in "\n\r\t") / max(1, len(decoded))
        if printable_ratio > 0.85:
            return decoded
    except:
        pass
    return None

INTEREST = {
    "DEX_LOADER":      [r"DexClassLoader", r"PathClassLoader", r"InMemoryDexClassLoader", r"BaseDexClassLoader", r"defineClass"],
    "FILE_IO":         [r"FileOutputStream", r"FileInputStream", r"RandomAccessFile;->write", r"openFileOutput", r"ByteArrayOutputStream"],
    "HTTP":            [r"HttpURLConnection", r"openConnection", r"okhttp3/OkHttpClient", r"OkHttpClient\$Builder", r"retrofit2/Retrofit", r"java/net/URL", r"java/net/Socket"],
    "REFLECTION":      [r"Class;->forName", r"getMethod", r"getDeclaredMethod", r"Method;->invoke"],
    "EXEC":            [r"Runtime;->exec", r"ProcessBuilder", r"Process;->getInputStream"],
    "WEBSOCKET":       [r"WebSocket", r"newWebSocket"],
    "BYTE_XOR":        [r"xor-int", r"xor-long"],
    "CIPHER_LIKELY":   [r"Cipher", r"SecretKey", r"IvParameterSpec", r"javax/crypto", r"Mac;"],
}

apks = list(KEYS.keys())
for apk_name in apks:
    apk = os.path.join(UNPACK, apk_name)
    if not os.path.isfile(apk): continue
    print(f"\n{'#'*72}\n# {apk_name}\n{'#'*72}")
    keys = KEYS[apk_name]
    a, d, dx = AnalyzeAPK(apk)

    methods_data = []
    for m in dx.get_methods():
        try:
            if m.is_external(): continue
            method = m.get_method()
            if not method or not method.get_code(): continue
            lines = []
            for ins in method.get_instructions():
                try:
                    lines.append(ins.get_name() + " " + ins.get_output())
                except: pass
            body = "\n".join(lines)
            methods_data.append({"name": str(m.full_name), "body": body})
        except: pass

    print(f"  {len(methods_data)} non-external methods")

    # Categorize
    for cat, patterns in INTEREST.items():
        hits = []
        for md in methods_data:
            if any(re.search(p, md["body"]) for p in patterns):
                # extract const-strings
                lits = re.findall(r'const-string [vp]\d+, "([^"]*)"', md["body"])
                lits = [l for l in lits if l]
                hits.append((md["name"], lits, md["body"]))
        print(f"\n  [{cat}] {len(hits)} methods")
        for name, lits, body in hits[:6]:
            print(f"    method: {name}")
            # decrypt each literal
            decrypted_lits = []
            for l in lits:
                if re.match(r"^[A-Za-z0-9+/]{4,400}={0,2}$", l):
                    for kname, k in keys.items():
                        d_str = decrypt_string(l, k)
                        if d_str and len(d_str) >= 3:
                            decrypted_lits.append(f"{kname}->{d_str[:160]}")
                            break
            if decrypted_lits:
                print(f"      decrypted literals:")
                for dl in decrypted_lits[:10]:
                    print(f"        {dl}")
            else:
                print(f"      literals ({len(lits)}): {lits[:5]}")
