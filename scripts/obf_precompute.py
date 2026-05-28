#!/usr/bin/env python3
"""
obf_precompute.py — ObfuscatedSignatures.kt uchun hash()/encode() qiymatlarini
KOMPILYATSIYAGACHA hisoblab beradi.

MUAMMO: ObfuscatedSignatures ilgari `hash("elrxzx.com")` / `encode("frida-server")`
ni RANTAYMда chaqirardi — natijada ochiq matn ("elrxzx.com", C2 domenlari, kalitlar)
DEX ichida literal sifatida qolardi (`strings` ko'rardi). Bu faylning butun maqsadiga zid.

YECHIM: hash()/encode() natijasini shu yerda oldindan hisoblab, .kt ichiga TAYYOR
literal sifatida qo'yamiz. Ochiq matn faqat kommentda qoladi (kompilyatsiya bo'lmaydi).

Sxema (ObfuscatedSignatures.kt bilan AYNAN bir xil):
    hash(s)   = SHA-256(s.lower())[:8] -> UPPERCASE hex (16 belgi)
    encode(s) = Base64( XOR(utf8(s), 0x5A) )   # android.util.Base64.NO_WRAP = standart, padding bor

Ishlatish: python scripts/obf_precompute.py
"""
import base64
import hashlib

XOR_KEY = 0x5A


def obf_hash(s: str) -> str:
    return hashlib.sha256(s.lower().encode("utf-8")).digest()[:8].hex().upper()


def obf_encode(s: str) -> str:
    xored = bytes(b ^ XOR_KEY for b in s.encode("utf-8"))
    return base64.b64encode(xored).decode("ascii")


def obf_decode(b64: str) -> str:
    xored = base64.b64decode(b64)
    return bytes(b ^ XOR_KEY for b in xored).decode("utf-8")


# (token, family_label) — hash() bilan ketadi. Token hash() ichida lower() bo'ladi.
TOKEN_HASHES = [
    ("dashapp-v2.org", "dashapp"),
    ("uzbekchill.com", "uzbekchill"),
    ("taklifnomatoy_robot", "taklif_bot"),
    ("BotSigner", "bot_internal"),
    ("BotOrg", "bot_internal"),
    ("googleadst", "googleadst_dropper"),
    ("elrxzx.com", "Ajina.Banker.C2"),
    ("JYTAs0m31lxvwkQE42Y10Ktm", "Ajina.Banker.key"),
    ("3183701586F97GhYNSURErMM", "Ajina.Banker.key"),
    ("ydbllnjd.com", "RoundRift.dropper"),
    ("ilovekkksfm.com", "RoundRift.C2"),
    ("sqsmlH2NOLPXeaDIFGnMEOdG6Uc2mVin", "RoundRift.key"),
    ("_zl6enckey", "RoundRift.native_sym"),
    ("/video/dropper.html", "RoundRift.endpoint"),
    ("/api/upload_sms", "banker.sms_exfil"),
    ("/api/inject", "banker.overlay_inject"),
    ("/admin/banks", "banker.admin_panel"),
]

# (plain, family_label) — encode() bilan ketadi (substring qidiruvi uchun).
XOR_SIGS = [
    ("/commends", "bot_endpoint"),
    ("jetski", "jetski_family"),
    ("frida-server", "anti.frida"),
    ("frida/gadget", "anti.frida"),
    ("com.topjohnwu.magisk", "anti.magisk"),
    ("/data/local/tmp/frida-server", "anti.frida"),
    ("android.net.VpnService", "anti.vpn"),
    ("WindowManager.LayoutParams", "overlay.windowmgr"),
    ("TYPE_APPLICATION_OVERLAY", "overlay.type"),
    ("AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED", "overlay.text_grab"),
]


def main():
    # round-trip self-check
    for plain, _ in XOR_SIGS:
        assert obf_decode(obf_encode(plain)) == plain, plain
    print(f"# round-trip OK: {len(XOR_SIGS)} encode-sig\n")

    print("// ===== MALICIOUS_TOKEN_HASHES (oldindan hisoblangan, plaintext olib tashlangan) =====")
    for token, label in TOKEN_HASHES:
        print(f'        "{obf_hash(token)}" to "{label}",   // hash("{token}")')
    print()

    print("// ===== XOR_ENCRYPTED_SIGNATURES (oldindan kodlangan) =====")
    for plain, label in XOR_SIGS:
        print(f'        "{obf_encode(plain)}" to "{label}",   // encode("{plain}")')


if __name__ == "__main__":
    main()
