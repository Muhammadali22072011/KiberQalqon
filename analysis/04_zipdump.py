"""Stage 4: Full zip inventory + entropy + file-type sniff for every APK."""
import os, sys, json, io, zipfile, hashlib, math
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

SAMPLES_DIR = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\новые вирусы"
OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"

MAGIC = {
    b"PK\x03\x04":         "ZIP/APK/JAR",
    b"dex\n035\x00":       "DEX-035",
    b"dex\n036\x00":       "DEX-036",
    b"dex\n037\x00":       "DEX-037",
    b"dex\n038\x00":       "DEX-038",
    b"dex\n039\x00":       "DEX-039",
    b"\x7fELF":            "ELF",
    b"\x89PNG":            "PNG",
    b"\xff\xd8\xff":       "JPEG",
    b"RIFF":               "RIFF/WAV",
    b"OggS":               "OGG",
    b"ID3":                "MP3",
    b"\x00\x00\x00 ftyp":  "MP4",
    b"%PDF":               "PDF",
    b"\x03\x00\x08\x00":   "AXML(MANIFEST)",
}

def entropy(b):
    if not b: return 0
    from collections import Counter
    c = Counter(b)
    n = len(b)
    return -sum((v/n)*math.log2(v/n) for v in c.values())

def sniff(b):
    for m, name in MAGIC.items():
        if b.startswith(m):
            return name
    if all(32 <= x < 127 or x in (9,10,13) for x in b[:64]):
        return "TEXT"
    return "BINARY"

samples = sorted([f for f in os.listdir(SAMPLES_DIR) if f.lower().endswith(".apk")])
all_results = {}

for s in samples:
    p = os.path.join(SAMPLES_DIR, s)
    print(f"\n{'='*72}\n[+] {s}  size={os.path.getsize(p):,}\n{'='*72}")
    rows = []
    with zipfile.ZipFile(p) as z:
        for info in sorted(z.infolist(), key=lambda i: -i.file_size):
            try:
                data = z.read(info.filename)
            except Exception as e:
                rows.append({"name": info.filename, "error": str(e)})
                continue
            head = data[:64]
            row = {
                "name":      info.filename,
                "size":      info.file_size,
                "compsize":  info.compress_size,
                "sha256":    hashlib.sha256(data).hexdigest(),
                "entropy":   round(entropy(data[:1_000_000]), 3),
                "magic":     sniff(head),
                "head_hex":  head[:16].hex(),
            }
            rows.append(row)
            print(f"  {row['size']:>10,}  E={row['entropy']:>5}  {row['magic']:<14}  {row['name']}")
    all_results[s] = rows

with open(os.path.join(OUT, "04_zipdump.json"), "w", encoding="utf-8") as f:
    json.dump(all_results, f, ensure_ascii=False, indent=2)
print(f"\n[+] Saved -> {OUT}\\04_zipdump.json")
