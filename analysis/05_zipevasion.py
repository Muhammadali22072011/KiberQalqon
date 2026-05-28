"""Stage 5: Hunt hidden ZIP/DEX content in tampered APKs.
Python's zipfile only sees a tiny innocent payload — Android's parser reads more.
Strategy: scan raw bytes for ALL PK signatures and DEX magic, list every ZIP local file header,
and dump the real central directory.
"""
import os, sys, io, struct, hashlib, json
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

SAMPLES_DIR = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\новые вирусы"
OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"

# ZIP signatures
LFH = b"PK\x03\x04"   # local file header
CDH = b"PK\x01\x02"   # central directory file header
EOCD= b"PK\x05\x06"   # end of central directory
DDS = b"PK\x07\x08"   # data descriptor
ZIP64_EOCD = b"PK\x06\x06"
ZIP64_LOC  = b"PK\x06\x07"

DEX_MAGIC = b"dex\n0"

def find_all(buf, sig):
    out = []
    i = 0
    while True:
        j = buf.find(sig, i)
        if j < 0: break
        out.append(j)
        i = j + 1
    return out

def parse_lfh(buf, off):
    if buf[off:off+4] != LFH: return None
    (sig, ver, flags, method, mt, md, crc, csize, usize, nlen, elen) = struct.unpack_from("<IHHHHHIIIHH", buf, off)
    name = buf[off+30:off+30+nlen].decode("utf-8", "replace")
    return {
        "off": off,
        "version_extract": ver,
        "flags": flags,
        "method": method,
        "crc": crc,
        "comp_size": csize,
        "uncomp_size": usize,
        "name_len": nlen,
        "extra_len": elen,
        "name": name,
        "data_off": off + 30 + nlen + elen,
    }

def parse_cdh(buf, off):
    if buf[off:off+4] != CDH: return None
    fmt = "<IHHHHHHIIIHHHHHII"
    (sig, vmade, vex, flags, method, mt, md, crc, csize, usize,
     nlen, elen, clen, dnum, ia, ea, lhof) = struct.unpack_from(fmt, buf, off)
    name = buf[off+46:off+46+nlen].decode("utf-8", "replace")
    return {
        "off": off,
        "name": name,
        "comp_size": csize,
        "uncomp_size": usize,
        "method": method,
        "crc": crc,
        "lhof": lhof,
        "flags": flags,
    }

def parse_eocd(buf, off):
    if buf[off:off+4] != EOCD: return None
    (sig, dnum, sdir, ndents_disk, ndents_total, cdsize, cdoff, clen) = struct.unpack_from("<IHHHHIIH", buf, off)
    return {"off": off, "ndents": ndents_total, "cd_offset": cdoff, "cd_size": cdsize, "comment_len": clen}

samples = sorted([f for f in os.listdir(SAMPLES_DIR) if f.lower().endswith(".apk")])
results = {}

for s in samples:
    p = os.path.join(SAMPLES_DIR, s)
    with open(p, "rb") as f:
        buf = f.read()
    print(f"\n{'='*72}\n[+] {s}  size={len(buf):,}\n{'='*72}")

    lfh = find_all(buf, LFH)
    cdh = find_all(buf, CDH)
    eocd= find_all(buf, EOCD)
    dex_off = find_all(buf, DEX_MAGIC)
    elf_off = find_all(buf, b"\x7fELF")
    print(f"  LFH={len(lfh)}  CDH={len(cdh)}  EOCD={len(eocd)}  DEX_magic={len(dex_off)}  ELF_magic={len(elf_off)}")

    # Parse all EOCD records
    eocds = [parse_eocd(buf, o) for o in eocd]
    for e in eocds:
        if e:
            print(f"  EOCD @ {e['off']:#x}  cd_off={e['cd_offset']:#x}  cd_size={e['cd_size']}  entries={e['ndents']}  comment_len={e['comment_len']}")

    # Parse all LFH and list names
    files = []
    for o in lfh:
        rec = parse_lfh(buf, o)
        if rec:
            files.append(rec)
    print(f"  --- local file headers ({len(files)}) ---")
    for r in files:
        flag = "ENC" if (r["flags"] & 0x1) else "   "
        print(f"    [{flag}] off={r['off']:#9x}  m={r['method']:<2} csize={r['comp_size']:>9}  usize={r['uncomp_size']:>9}  ext={r['extra_len']:>4}  name={r['name']}")

    # Show parsed central directory entries (these are what Android actually iterates)
    print(f"  --- central directory ({len(cdh)}) ---")
    for o in cdh:
        rec = parse_cdh(buf, o)
        if rec:
            print(f"    cd_off={rec['off']:#9x}  lhof={rec['lhof']:#9x}  csize={rec['comp_size']:>9}  usize={rec['uncomp_size']:>9}  m={rec['method']:<2}  name={rec['name']}")

    print(f"  --- DEX magic locations ---")
    for o in dex_off[:10]:
        ver = buf[o+4:o+8]
        print(f"    DEX @ {o:#x}  version={ver}")

    results[s] = {
        "size": len(buf),
        "lfh_count": len(lfh),
        "cdh_count": len(cdh),
        "eocd_count": len(eocd),
        "dex_offsets": dex_off,
        "elf_offsets": elf_off,
        "local_files": files,
        "eocds": eocds,
        "central_dir": [parse_cdh(buf, o) for o in cdh],
    }

with open(os.path.join(OUT, "05_zipevasion.json"), "w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=2, default=str)
print(f"\n[+] Saved -> {OUT}\\05_zipevasion.json")
