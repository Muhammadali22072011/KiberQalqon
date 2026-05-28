"""Stage 23: Grep ALL decrypted plaintexts for crypto-related keywords (AES, Cipher, SecretKey, etc).
Also: try XOR with prefix bytes as candidate per-file key.
"""
import os, sys, io, json, re, hashlib
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from Crypto.Cipher import AES
from Crypto.Util.Padding import unpad

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

with open(os.path.join(OUT, "16_decrypts.json"), encoding="utf-8") as f:
    decrypts = json.load(f)

CRYPTO_KW = re.compile(r"\b(AES|Cipher|SecretKey|IvParameterSpec|getInstance|doFinal|javax\.crypto|crypto|encrypt|decrypt|PKCS5|PKCS7|GCM|CBC|ECB|CTR|HMAC|PBKDF|Mac\b|MessageDigest|SHA-?\d+|MD5|RC4|Blowfish|ChaCha|Poly1305|RSA|getBytes|byte\[\])", re.IGNORECASE)
URL_KW = re.compile(r"(https?://|t\.me/|api\.tele|telegram|bot[A-Za-z]?\d|/sendMessage|/sendDocument|/exfil|/cmd|/api/|/admin|/panel|webhook|ngrok|amazonaws|cloudfront|duckdns|herokuapp|vercel)", re.IGNORECASE)
ASSET_KW = re.compile(r"\.(spe|sps|data|bak|dat)$|getAssets|AssetManager|loadDex|defineClass|DexClassLoader|loadFile|FileInputStream|InputStream;->read", re.IGNORECASE)
BANK_KW = re.compile(r"\b(uzcard|humo|click\.uz|payme|asaka|ipakyo?li|kapital|qishloq|agrobank|xalq|infinbank|hamkor|anor|tbc|alif|davr|orient|capital|sqb|aab|tenge|kapitalbank|hamkorbank|tbcbank|alifbank|anorbank|kapitalbankuz)\b", re.IGNORECASE)

print("="*72)
print("GREP for CRYPTO / URL / ASSET / BANK keywords in decrypted plaintexts")
print("="*72)

for sample, info in decrypts.items():
    pts = [r["pt"] for r in info["all_plaintexts"]]
    print(f"\n----- {sample} ({len(pts)} plaintexts) -----")
    for label, pat in [("CRYPTO", CRYPTO_KW), ("URL", URL_KW), ("ASSET", ASSET_KW), ("BANK", BANK_KW)]:
        hits = []
        seen = set()
        for p in pts:
            if pat.search(p) and p not in seen:
                seen.add(p)
                hits.append(p)
        if hits:
            print(f"  [{label}] {len(hits)} hits")
            # filter generic junk
            JUNK = re.compile(r"(android\.|androidx\.|kotlin|Builder|Compose|LineHeight|byteArray|getBytes\(\)|messageDigest|invalid|attribute|toString)", re.IGNORECASE)
            for h in hits:
                if not JUNK.search(h) or len(h) < 60:
                    print(f"    {h[:240]}")

# Now also try XOR per-file-prefix key on the big assets
print("\n" + "="*72)
print("XOR with prefix-as-key (assume file = [16-byte key][rest XOR-encrypted with key])")
print("="*72)

for sample_dir in sorted(os.listdir(UNPACK)):
    fd = os.path.join(UNPACK, sample_dir)
    if not os.path.isdir(fd): continue
    assets = os.path.join(fd, "assets")
    if not os.path.isdir(assets): continue
    for root, _, files in os.walk(assets):
        for fn in files:
            if not fn.endswith((".spe", ".data", ".bak", ".sps", ".dat")): continue
            p = os.path.join(root, fn)
            sz = os.path.getsize(p)
            if sz < 100000: continue
            with open(p, "rb") as f:
                ct = f.read()
            # try prefix lengths 8, 16, 24, 32, 48, 64, 128, 256
            for plen in (8, 16, 24, 32, 48, 64, 128, 256):
                k = ct[:plen]
                body = ct[plen:]
                pt = bytes(b ^ k[i % plen] for i, b in enumerate(body))
                head = pt[:32]
                if head[:4] in (b"dex\n", b"PK\x03\x04", b"\x7fELF", b"\x1f\x8b") or head[:1] in b"<{[":
                    text = pt[:200].decode("utf-8","replace")
                    print(f"  {sample_dir} :: {fn}  prefix={plen}  -> head: {head[:24]}")
                    print(f"     {text[:140]}")
                # also try first plen bytes used at offset 0..plen as keystream wrap-around
            # Try XOR by repeating the prefix INCLUDING itself (so key = first 16 bytes, decryption from offset 0)
            for plen in (16, 32):
                k = ct[:plen]
                pt = bytes(b ^ k[i % plen] for i, b in enumerate(ct))
                head = pt[:32]
                if head[:4] in (b"dex\n", b"PK\x03\x04", b"\x7fELF", b"\x1f\x8b") or head[:1] in b"<{[":
                    text = pt[:200].decode("utf-8","replace")
                    print(f"  {sample_dir} :: {fn}  full-XOR prefix-key plen={plen}  -> head: {head[:24]}")
                    print(f"     {text[:140]}")
