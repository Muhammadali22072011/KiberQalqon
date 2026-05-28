"""
Rebuilds all Android launcher icons from a single source PNG.

Usage:
    python scripts/rebuild_launcher_icons.py [source.png]

Default source: kiberqalqon_logo_src.png in project root.

Generates:
  - ic_launcher.png in 5 mipmap densities (48/72/96/144/192)
  - ic_launcher_foreground.png in 5 densities (scaled to safe zone)
  - playstore_512.png in project root (for Play Store listing)
"""
from __future__ import annotations
import sys
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "ApkGuard" / "app" / "src" / "main" / "res"

LAUNCHER_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

FOREGROUND_SIZES = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}


def make_launcher(src: Image.Image, size: int) -> Image.Image:
    return src.resize((size, size), Image.LANCZOS)


def make_foreground(src: Image.Image, size: int) -> Image.Image:
    """Adaptive icon foreground: source scaled to ~67% of canvas (safe zone), centered, transparent bg."""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = int(size * 0.72)
    scaled = src.resize((inner, inner), Image.LANCZOS)
    offset = (size - inner) // 2
    canvas.paste(scaled, (offset, offset), scaled if scaled.mode == "RGBA" else None)
    return canvas


def main() -> int:
    src_path = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "kiberqalqon_logo_src.png"
    if not src_path.exists():
        print(f"ERROR: source not found: {src_path}")
        print(f"Save the chosen PNG as: {src_path}")
        return 1

    print(f"Loading source: {src_path}")
    src = Image.open(src_path).convert("RGBA")
    print(f"Source size: {src.size}")

    for folder, size in LAUNCHER_SIZES.items():
        out = RES / folder / "ic_launcher.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        make_launcher(src, size).save(out, "PNG", optimize=True)
        print(f"  wrote {out.relative_to(ROOT)}  ({size}x{size})")

    for folder, size in FOREGROUND_SIZES.items():
        out = RES / folder / "ic_launcher_foreground.png"
        make_foreground(src, size).save(out, "PNG", optimize=True)
        print(f"  wrote {out.relative_to(ROOT)}  ({size}x{size})")

    playstore = ROOT / "playstore_512.png"
    make_launcher(src, 512).save(playstore, "PNG", optimize=True)
    print(f"  wrote {playstore.relative_to(ROOT)}  (512x512)")

    print("\nDone. Next: rebuild APK with `cd ApkGuard && ./gradlew assembleDebug`")
    return 0


if __name__ == "__main__":
    sys.exit(main())
