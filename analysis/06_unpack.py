"""Stage 6: Patch ZIP general-purpose bit 0x01 to 0 in both LFH and CDH, save fixed APK, extract everything."""
import os, sys, io, struct, hashlib, json, zipfile
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

SAMPLES_DIR = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\новые вирусы"
OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")
os.makedirs(UNPACK, exist_ok=True)

LFH = b"PK\x03\x04"
CDH = b"PK\x01\x02"

def find_all(buf, sig):
    out = []; i = 0
    while True:
        j = buf.find(sig, i)
        if j < 0: break
        out.append(j); i = j + 1
    return out

def patch_apk(p):
    with open(p, "rb") as f:
        buf = bytearray(f.read())
    # zero general purpose bit flag in every LFH (offset +6, 2 bytes)
    lfh = find_all(bytes(buf), LFH)
    cdh = find_all(bytes(buf), CDH)
    for o in lfh:
        flag_off = o + 6
        flags = struct.unpack_from("<H", buf, flag_off)[0]
        new = flags & ~0x0001 & ~0x0040 & ~0x2000  # clear "encrypted" + "strong encryption" + "encrypted central dir"
        struct.pack_into("<H", buf, flag_off, new)
    for o in cdh:
        flag_off = o + 8  # in CDH the GPBF is at +8
        flags = struct.unpack_from("<H", buf, flag_off)[0]
        new = flags & ~0x0001 & ~0x0040 & ~0x2000
        struct.pack_into("<H", buf, flag_off, new)
    return bytes(buf), len(lfh), len(cdh)

samples = sorted([f for f in os.listdir(SAMPLES_DIR) if f.lower().endswith(".apk")])
summary = {}

for s in samples:
    p = os.path.join(SAMPLES_DIR, s)
    short = s.replace(" ", "_").replace("(", "").replace(")", "").replace(".apk", "")
    short = "".join(c for c in short if c.isalnum() or c in "_-")[:60]
    print(f"\n{'='*72}\n[+] {s} -> {short}\n{'='*72}")
    fixed, lfh_n, cdh_n = patch_apk(p)
    fixed_path = os.path.join(UNPACK, short + "_fixed.apk")
    with open(fixed_path, "wb") as f:
        f.write(fixed)
    print(f"  patched {lfh_n} LFH + {cdh_n} CDH  -> {fixed_path}")

    # extract everything
    dest = os.path.join(UNPACK, short)
    os.makedirs(dest, exist_ok=True)
    extracted = []
    with zipfile.ZipFile(fixed_path) as z:
        for info in z.infolist():
            try:
                z.extract(info, dest)
                extracted.append((info.filename, info.file_size))
            except Exception as e:
                extracted.append((info.filename, f"ERR:{e}"))
    print(f"  extracted {len(extracted)} files")
    for n, sz in extracted:
        print(f"     {sz:>10}  {n}")
    summary[s] = {"fixed_apk": fixed_path, "out_dir": dest, "files": extracted}

with open(os.path.join(OUT, "06_unpack.json"), "w", encoding="utf-8") as f:
    json.dump(summary, f, ensure_ascii=False, indent=2, default=str)
print(f"\n[+] Saved -> {OUT}\\06_unpack.json")
