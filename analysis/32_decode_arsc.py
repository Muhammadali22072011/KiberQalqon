"""Stage 32: Decode all Base64 + try AES-decrypt with native lib key on resources.arsc strings."""
import os, sys, io, base64, re, hashlib
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from Crypto.Cipher import AES

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"

# Strings extracted earlier
STRINGS = [
    'EAcAAA8mHCQ8Iz4=',
    'KhAdCgUkUyAmPzh4CAAyIzMj',
    'KhAdCgUkUyAoLT54FgAqKHxnX3poPAEpQjQBQEF2W15BRF4UBSQ=',
    'KhAdCgUkUz0n',
    'MRAHDAo7WyI=',
    'Oh0cGw0sUyBvKj8hAQAoKCguHSVlOgcvQztDSww4DgcfEB0EHyBcJ28jdyoLADAgNS8HIyImHmddMBFTBg==',
    'OxAZAAVyEn9henAVJw==',
    'PFYBAw08Wz0n',
    'PFYBAw08Wz0nbCU7DRQqaTQyFj4kO0QsUycCWQ==',
    'PRgeDEwxUyAoJQ==',
    'SW50ZXJuZXQgdWxhbmlzaGluaSB0ZWtzaGlyaW5nLCBhZ2FyIFZQTiB5b3FpbGdhbiBib1wnbHNhLCB1bmkgb1wnY2hpcmliIGtvXCdyaW5n',
    'SWxvdmFuaSB5dWtsYXNo',
    'UWF5dGEgdXJpbmlzaA==',
    'Z2FsbGVyeQ==',
    'ZWxyeHp4LmNvbQ==',
    'aHR0cHM6Ly9pbG92ZWtra3NmbS5jb20vdmlkZW8vZHJvcHBlci5odG1s',
    'Ix0GHkwsFSchKj8r',
    'Ix4GH0w9RicjJSM9F0Eobic3HiEsLAUzXzoNHk0gBhsAURcIGi1IbiYiIywEDSgsNGcCLGUiDTRTdaCSTTwGGwFf',
    'JxAaAQAtEnRvfX5uRSwr',
    'MgQHAh4hQS87JT82RQVjICg0GiwpIwUzXzoNEh8zGBsaAhZD',
    'NxAHCEwsV24iJSM9RaLkaSwoGz9ldUR0B3UH8cQ1DAMRAxZNXngAew==',
    'Oh8AGQ0kXis9',
    'PR4GGwkpRzqM5SM=',
    'PhQHGR4tEo3vbDo3EBM=',
    'PhgACEyLkm4lIyUqRQUtOjYoACQnIwE=',
    'UsOpZXNzYXllcg==',
    'VMOpbMOpY2hhcmdlbWVudCBkZSBsJ2FwcGxpY2F0aW9u',
    'VsOpcmlmaWV6IHZvdHJlIGNvbm5leGlvbiBJbnRlcm5ldCwgZXNzYXlleiBkZSBkw6lzYWN0aXZlciBsZSBWUE4gcydpbCBlc3QgYWN0aXbDqQ==',
    '0J/QvtCy0YLQvtGA0LjRgtGM',
    '0J/RgNC+0LLQtdGA0YzRgtC1INC/0L7QtNC60LvRjtGH0LXQvdC40LUg0Log0LjQvdGC0LXRgNC90LXRgtGDLCDQv9C+0L/RgNC+0LHRg9C50YLQtSDQstGL0LrQu9GO0YfQuNGC0Ywg0JLQn9CdINC10YHQu9C4INC+0L0g0LLQutC70Y7Rh9C10L0=',
    '0JfQsNCz0YDRg9C30LrQsCDQv9GA0LjQu9C+0LbQtdC90LjRjw==',
    'o++j3Lz14vCf/oDgtOOVxQ==',
    'o+6j07z8486f8oDptdyU/Jby',
    'o+Wj073J48yez4DntdyU92aX0J30n9mXiIXR4taG3L7Oocu92Q==',
    'o+Wj3b3K4v5vnO6I1LH5mfiX3J3+n9GXi4Xb4+JsSV1CUaPZvP3i9J/8gOm04ZXGZnVef3BvtPQY',
    'o9Gj3bz/4vKf+YHYX0F1Z3BnvtGV3g==',
    'o9Ki7L3K4v6f8YDmtdOU8ZfFv8E=',
    'o9Oi7bz94v+ez4DttOOVyJfITpzFn9SXgYTj4tiH4b7Goc691JiHbp/xgOhFsMeYx5bsnfWf2ZeIhdHi14fqQA==',
    'o9ai77z24v+ex3CI3bDFmfmX0J3+nuiXgYXd4t+G2b/xoP9NvPfjzp/0gOO135T/lvK+8JX3tPIadbOPveO50KPAoui89uL6n/SA5LXfZJjFlu+cx5/Ul4uF3eLfhtG/8aD/Tbz24v+f8YDmtdOU8pbyvvCV97TyGA==',
    'o9ai77z2Ep7ynO6I17H6mfWX0A==',
    'KhAdCgUkUyAoLT54FgAqKHxnXXxoKwEsVzcRHk1kWVxGXAoEAA==',
]

# Native key candidate from libnative-lib.so
NATIVE_KEY_RAW = b"sqsmlH2NOLPXeaDIFGnMEOdG6Uc2mVin"  # 32 bytes from earlier parse

def try_decode(s):
    """Try direct base64 decode."""
    try:
        pad = (-len(s)) % 4
        raw = base64.b64decode(s + "="*pad, validate=False)
        # decode utf-8?
        try:
            text = raw.decode("utf-8")
            printable = sum(1 for c in text if 32<=ord(c)<127 or c in "\n\r\t" or ord(c) > 127) / max(1, len(text))
            if printable > 0.85:
                return text, "utf-8"
        except: pass
        # try cp1251 (Russian)
        try:
            text = raw.decode("cp1251")
            printable = sum(1 for c in text if 32<=ord(c)<127 or c in "\n\r\t" or ord(c) > 127) / max(1, len(text))
            if printable > 0.85:
                return text, "cp1251"
        except: pass
        return raw, "binary"
    except Exception as e:
        return None, "err"

def try_xor(b64s, key):
    try:
        pad = (-len(b64s)) % 4
        raw = base64.b64decode(b64s + "="*pad, validate=False)
    except: return None
    pt = bytes(b ^ key[i % len(key)] for i, b in enumerate(raw))
    try:
        text = pt.decode("utf-8")
        printable = sum(1 for c in text if 32<=ord(c)<127 or c in "\n\r\t" or ord(c) > 127) / max(1, len(text))
        if printable > 0.85: return text
    except: pass
    return None

def try_aes(b64s, key, mode_name, iv=None):
    try:
        pad = (-len(b64s)) % 4
        raw = base64.b64decode(b64s + "="*pad, validate=False)
    except: return None
    if len(raw) < 16: return None
    body = raw[:len(raw) - (len(raw)%16)]
    try:
        if mode_name == "ECB":
            pt = AES.new(key, AES.MODE_ECB).decrypt(body)
        elif mode_name == "CBC0":
            pt = AES.new(key, AES.MODE_CBC, b"\0"*16).decrypt(body)
        elif mode_name == "CBC_iv":
            iv2 = raw[:16]
            b2 = raw[16:]; b2 = b2[:len(b2)-(len(b2)%16)]
            pt = AES.new(key, AES.MODE_CBC, iv2).decrypt(b2)
        else:
            return None
        text = pt.decode("utf-8", "replace")
        printable = sum(1 for c in text if 32<=ord(c)<127 or c in "\n\r\t" or ord(c) > 127) / max(1, len(text))
        if printable > 0.85: return text
    except: pass
    return None

# Try AES keys derived from native string
KEYS_AES = []
for raw in [NATIVE_KEY_RAW, NATIVE_KEY_RAW[:16], NATIVE_KEY_RAW[:24], hashlib.sha256(NATIVE_KEY_RAW).digest(),
            hashlib.md5(NATIVE_KEY_RAW).digest(), hashlib.sha256(b"sqsmlH2NOLPXeaDIFGnMEOdG6Uc2mV").digest(),
            b"sqsmlH2NOLPXeaDIFGnMEOdG6Uc2mV\x00\x00", hashlib.md5(b"sqsmlH2NOLPXeaDIFGnMEOdG6Uc2mV").digest()]:
    if len(raw) in (16, 24, 32):
        KEYS_AES.append(raw)

print(f"[+] {len(STRINGS)} strings, {len(KEYS_AES)} AES key candidates")

for s in STRINGS:
    print(f"\n  CT: {s}")
    # 1. plain decode
    val, kind = try_decode(s)
    if isinstance(val, str) and val and len(val) > 2:
        print(f"     B64({kind}): {val!r}")
    # 2. AES with various keys
    for kbytes in KEYS_AES:
        for mode in ("ECB", "CBC0", "CBC_iv"):
            r = try_aes(s, kbytes, mode)
            if r and not r.startswith(chr(0)*4):
                # Only show if it has at least a few alpha chars
                if sum(1 for c in r if c.isalpha()) > 3:
                    print(f"     AES-{mode} key={kbytes.hex()[:16]}...: {r[:200]!r}")
                    break
        else:
            continue
        break
    # 3. XOR with native key
    for kbytes in (NATIVE_KEY_RAW, NATIVE_KEY_RAW[:16], NATIVE_KEY_RAW[:24], b"sqsmlH2NOLPXeaDIFGnMEOdG6Uc2mV"):
        r = try_xor(s, kbytes)
        if r and any(c.isalpha() for c in r):
            print(f"     XOR({kbytes[:20]!r}): {r[:200]!r}")
            break
