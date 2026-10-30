#!/usr/bin/env python3
"""Renames the FFmpeg libraries inside an FFmpegKit AAR so they can't clash with mpv's.

mpv-android-lib and FFmpegKit both ship libavutil.so, libavcodec.so and the rest, built from
different FFmpeg versions (mpv: 8.x, LIBAVUTIL_60; FFmpegKit 6.0: LIBAVUTIL_58). The APK can only
hold one file per name, so packaging's pickFirst gave one of them the other's libraries, and mpv
failed to load: `cannot locate symbol "av_default_item_name" referenced by libmpv.so`.

Each library gets a same-length name (libavutil.so -> libavutfk.so): the entries are renamed and
every reference (SONAME, NEEDED) is rewritten in place in all FFmpegKit .so files. Same length means
no section moves, so nothing a loader checks changes. FFmpegKit's Java side only ever loads
libffmpegkit.so on API 21+, which then finds its own libraries through NEEDED.

Idempotent. Usage: rename-ffmpegkit-libs.py path/to/lib-ffmpeg-kit-*.aar
"""
import os
import shutil
import sys
import tempfile
import zipfile

RENAMES = {
    "libavutil.so": "libavutfk.so",
    "libavcodec.so": "libavcodfk.so",
    "libavdevice.so": "libavdevifk.so",
    "libavfilter.so": "libavfiltfk.so",
    "libavformat.so": "libavformfk.so",
    "libswresample.so": "libswresampfk.so",
    "libswscale.so": "libswscafk.so",
}


def patch_bytes(data: bytes) -> bytes:
    for old, new in RENAMES.items():
        assert len(old) == len(new)
        # NUL-terminated, so only whole names in the string tables match.
        data = data.replace(old.encode() + b"\0", new.encode() + b"\0")
    return data


def main(aar: str) -> None:
    changed = 0
    fd, tmp = tempfile.mkstemp(suffix=".aar", dir=os.path.dirname(os.path.abspath(aar)))
    os.close(fd)
    with zipfile.ZipFile(aar) as src, zipfile.ZipFile(tmp, "w") as dst:
        for info in src.infolist():
            data = src.read(info.filename)
            name = info.filename
            if name.startswith("jni/") and name.endswith(".so"):
                base = os.path.basename(name)
                if base in RENAMES:
                    name = name[: -len(base)] + RENAMES[base]
                patched = patch_bytes(data)
                if patched != data or name != info.filename:
                    changed += 1
                data = patched
            out = zipfile.ZipInfo(name, date_time=info.date_time)
            out.compress_type = info.compress_type
            out.external_attr = info.external_attr
            dst.writestr(out, data)
    # A partial rename (an upstream layout change) would bring the clash back silently.
    with zipfile.ZipFile(tmp) as out:
        names = out.namelist()
    for abi in sorted({n.split("/")[1] for n in names if n.startswith("jni/") and n.count("/") == 2}):
        missing = [new for new in RENAMES.values() if f"jni/{abi}/{new}" not in names]
        leftover = [old for old in RENAMES if f"jni/{abi}/{old}" in names]
        if missing or leftover:
            os.remove(tmp)
            sys.exit(f"{aar}: {abi} is not fully renamed (missing {missing}, leftover {leftover})")
    shutil.move(tmp, aar)
    print(f"{aar}: {changed} native libraries renamed or patched")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
