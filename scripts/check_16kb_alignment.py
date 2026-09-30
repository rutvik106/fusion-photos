#!/usr/bin/env python3
"""Check 16 KB page-size compatibility of the native libraries in an APK or AAR.

Android 15+ devices can use a 16 KB page size. On such a device the platform requires every
shared library to be mappable with 16 KB pages, which means:

  1. every PT_LOAD segment must have p_align >= 16384          (ELF level)
  2. PT_GNU_RELRO must begin on a 16384-byte boundary          (ELF level)
  3. in an APK, an uncompressed .so must start at a 16 KB-aligned offset in the zip, because
     Android mmaps the library straight out of the archive                (packaging level)

If (1) fails the library cannot be mapped at all; the platform then runs the app in "page
size compatible mode" and shows an "Android App Compatibility" dialog. (2) and (3) produce
the same warning. Getting this wrong is easy to miss because it only shows up on 16 KB
emulator images (for example the `ps16k` system images) and on 16 KB hardware.

Usage:
    python3 scripts/check_16kb_alignment.py android/app/build/outputs/apk/debug/app-debug.apk
    python3 scripts/check_16kb_alignment.py some-library.aar

Exit status is non-zero when an arm64-v8a library fails any check, so this can gate CI.
"""

import io
import struct
import sys
import zipfile

MIN_ALIGN = 16384
PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552
ABI_PREFIX = "lib/arm64-v8a/"
FLAGGED = (
    "libsodium.so",
    "libjnidispatch.so",
    "libandroidx.graphics.path.so",
)


def program_headers(blob):
    """(type, vaddr, memsz, align) for each program header, or None if not 64-bit ELF."""
    if blob[:4] != b"\x7fELF" or blob[4] != 2:
        return None
    phoff = struct.unpack_from("<Q", blob, 0x20)[0]
    entsize = struct.unpack_from("<H", blob, 0x36)[0]
    count = struct.unpack_from("<H", blob, 0x38)[0]
    headers = []
    for i in range(count):
        off = phoff + i * entsize
        headers.append((
            struct.unpack_from("<I", blob, off)[0],
            struct.unpack_from("<Q", blob, off + 0x10)[0],
            struct.unpack_from("<Q", blob, off + 0x28)[0],
            struct.unpack_from("<Q", blob, off + 0x30)[0],
        ))
    return headers


def elf_report(blob):
    """(load_ok, relro_ok) for one shared library, or None if it is not 64-bit ELF."""
    headers = program_headers(blob)
    if headers is None:
        return None
    loads = [h for h in headers if h[0] == PT_LOAD]
    relro = [h for h in headers if h[0] == PT_GNU_RELRO]
    load_ok = bool(loads) and all(h[3] >= MIN_ALIGN for h in loads)
    relro_ok = all(h[1] % MIN_ALIGN == 0 for h in relro)
    return load_ok, relro_ok


def zip_data_offset(archive_path, info):
    """Where an entry's bytes actually start (zipalign pads via the extra field)."""
    with open(archive_path, "rb") as handle:
        handle.seek(info.header_offset)
        header = handle.read(30)
    name_len, extra_len = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_len + extra_len


def check_archive(path):
    failures = 0
    seen = 0
    try:
        archive = zipfile.ZipFile(path)
    except zipfile.BadZipFile:
        print(f"{path}: not a zip/APK/AAR")
        return 1

    with archive:
        entries = [n for n in archive.namelist() if n.endswith(".so")]
        for name in sorted(entries):
            if ABI_PREFIX not in name and "arm64-v8a/" not in name:
                continue
            seen += 1
            short = name.split("/")[-1]
            report = elf_report(archive.read(name))
            if report is None:
                print(f"  {short:32} skipped (not 64-bit ELF)")
                continue
            load_ok, relro_ok = report
            notes = []
            if not load_ok:
                notes.append("LOAD not 16 KB aligned -> cannot be mapped")
            elif not relro_ok:
                notes.append("RELRO not 16 KB aligned -> platform warns, compat mode")
            if notes:
                failures += 1
            state = "FAIL" if notes else "ok  "
            detail = "; ".join(notes) if notes else "LOAD + RELRO aligned"
            print(f"  {state} {short:32} {detail}")

        # Packaging: only meaningful for APKs, where libs are mmapped from the archive.
        if path.endswith(".apk"):
            for info in archive.infolist():
                if not info.filename.startswith("lib/") or not info.filename.endswith(".so"):
                    continue
                if "arm64-v8a" not in info.filename:
                    continue
                offset = zip_data_offset(path, info)
                aligned = offset % MIN_ALIGN == 0
                if not aligned:
                    failures += 1
                print(
                    f"  {'ok  ' if aligned else 'FAIL'} {info.filename.split('/')[-1]:32} "
                    f"zip offset {offset} ({'16 KB aligned' if aligned else 'NOT aligned'})"
                )

    if seen == 0:
        print(f"{path}: no arm64-v8a shared libraries found")
    print(f"{path}: {'FAIL' if failures else 'PASS'} ({failures} failing check(s))")
    return 1 if failures else 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    status = 0
    for target in sys.argv[1:]:
        print(target)
        status |= check_archive(target)
    sys.exit(status)
