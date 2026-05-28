"""Stage 34: Extract raw encKey bytes from libnative-lib.so via pyelftools."""
import os, sys, io, struct, re, hashlib
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from Crypto.Cipher import AES
from elftools.elf.elffile import ELFFile
from elftools.elf.sections import SymbolTableSection

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

SO_ARM = os.path.join(UNPACK, "VIDEO20012026mp4_2", "assets", "libs", "arm64-v8a", "libnative-lib.so")

# Open ELF
with open(SO_ARM, "rb") as f:
    elf = ELFFile(f)
    # find _ZL6encKey symbol
    for section in elf.iter_sections():
        if isinstance(section, SymbolTableSection):
            for sym in section.iter_symbols():
                name = sym.name
                if name and ("encKey" in name or "secret" in name.lower() or "key" in name.lower()):
                    print(f"  symbol: {name}  type={sym['st_info']['type']}  bind={sym['st_info']['bind']}  st_value=0x{sym['st_value']:x}  st_size={sym['st_size']}")
                    if sym['st_size'] > 0 and sym['st_size'] < 4096:
                        # Read those bytes from the corresponding section
                        addr = sym['st_value']
                        # Find section that contains this addr
                        for sect in elf.iter_sections():
                            sh_addr = sect['sh_addr']; sh_size = sect['sh_size']
                            if sh_addr <= addr < sh_addr + sh_size:
                                offset_in_sect = addr - sh_addr
                                file_off = sect['sh_offset'] + offset_in_sect
                                f.seek(file_off)
                                data = f.read(sym['st_size'])
                                print(f"    section: {sect.name}  file_off=0x{file_off:x}")
                                print(f"    raw bytes ({len(data)}): {data.hex()}")
                                print(f"    ascii: {data!r}")
                                break

# Dump all readable strings near 'encKey' in the .so
with open(SO_ARM, "rb") as f:
    so = f.read()
for m in re.finditer(b"encKey", so):
    j = m.start()
    print(f"\n  encKey marker at file_off=0x{j:x}")
    # Show 256 bytes context
    a = max(0, j - 64); b = min(len(so), j + 256)
    print(f"    ascii: {so[a:b]!r}")
