#!/usr/bin/env python3
"""Check that an APK's 64-bit native libraries are compatible with 16 KB pages.

ANDROID-REL-03. On a device that runs Android 15+ with a 16 KB page size, a
native library whose ELF LOAD segments are only 4 KB aligned may fail to load.
For libraries stored uncompressed in the APK (the AGP default for minSdk 23+),
the library is memory-mapped straight out of the zip, so its data must also
start on a 16 KB boundary within the APK.

Only the 64-bit ABIs (arm64-v8a, x86_64) can run on a 16 KB device, so only
they are enforced; other ABIs are listed and ignored.

Usage: check_elf_alignment.py APK
Exit status: 0 aligned, 1 misaligned or unreadable, 2 usage error.
"""

from __future__ import annotations

import struct
import sys
import zipfile

PAGE_SIZE = 16 * 1024
ENFORCED_ABIS = ("arm64-v8a", "x86_64")

_PT_LOAD = 1
_ELF64_HEADER_SIZE = 0x40
_ELF_MAGIC = b"\x7fELF"
_LOCAL_HEADER = struct.Struct("<4s5H3I2H")


def elf_load_alignments(data: bytes) -> list[int]:
    """Return p_align of every PT_LOAD segment of a little-endian ELF64 image.

    Only the 64-bit ABIs are enforced, so ELF32 is rejected rather than parsed.
    """
    if data[:4] != _ELF_MAGIC:
        raise ValueError("not an ELF file")
    if len(data) < _ELF64_HEADER_SIZE:
        raise ValueError("truncated ELF header")
    elf_class, elf_data = data[4], data[5]
    if elf_data != 1:
        raise ValueError("big-endian ELF is not supported")
    if elf_class != 2:
        raise ValueError(f"not a 64-bit ELF (class {elf_class})")
    e_phoff, = struct.unpack_from("<Q", data, 0x20)
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)

    alignments = []
    for index in range(e_phnum):
        header = e_phoff + index * e_phentsize
        p_type, = struct.unpack_from("<I", data, header)
        if p_type == _PT_LOAD:
            alignment, = struct.unpack_from("<Q", data, header + 0x30)
            alignments.append(alignment)
    if not alignments:
        raise ValueError("ELF has no PT_LOAD segment, so it is not a loadable library")
    return alignments


def stored_data_offset(apk_path: str, info: zipfile.ZipInfo) -> int:
    """Absolute offset of an entry's data, from its local header."""
    with open(apk_path, "rb") as handle:
        handle.seek(info.header_offset)
        header = _LOCAL_HEADER.unpack(handle.read(_LOCAL_HEADER.size))
    name_length, extra_length = header[-2], header[-1]
    return info.header_offset + _LOCAL_HEADER.size + name_length + extra_length


def check(apk_path: str) -> tuple[list[str], list[str]]:
    """Return (problems, report lines)."""
    problems: list[str] = []
    report: list[str] = []
    with zipfile.ZipFile(apk_path) as apk:
        for info in apk.infolist():
            parts = info.filename.split("/")
            if len(parts) != 3 or parts[0] != "lib" or not parts[2].endswith(".so"):
                continue
            abi = parts[1]
            if abi not in ENFORCED_ABIS:
                report.append(f"skip   {info.filename} (32-bit or unlisted ABI)")
                continue

            try:
                alignments = elf_load_alignments(apk.read(info))
            except (ValueError, struct.error) as error:
                problems.append(f"{info.filename}: invalid ELF ({error})")
                continue

            weakest = min(alignments)
            if weakest < PAGE_SIZE:
                problems.append(
                    f"{info.filename}: LOAD segment alignment 0x{weakest:x}, "
                    f"needs 0x{PAGE_SIZE:x} (2**14)"
                )

            if info.compress_type == zipfile.ZIP_STORED:
                offset = stored_data_offset(apk_path, info)
                if offset % PAGE_SIZE:
                    problems.append(
                        f"{info.filename}: stored at APK offset {offset}, "
                        f"not a multiple of {PAGE_SIZE}"
                    )
                zip_note = f"zip offset {offset}"
            else:
                zip_note = "compressed (extracted at install)"
            report.append(f"checked {info.filename} (LOAD align 0x{weakest:x}, {zip_note})")
    return problems, report


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    try:
        problems, report = check(argv[1])
    except (OSError, zipfile.BadZipFile) as error:
        print(f"Cannot read {argv[1]}: {error}", file=sys.stderr)
        return 1

    for line in report:
        print(line)
    checked = sum(1 for line in report if line.startswith("checked"))
    if problems:
        for problem in problems:
            print(f"MISALIGNED {problem}", file=sys.stderr)
        print(
            f"16 KB page-size check failed: {len(problems)} problem(s) in "
            f"{checked} 64-bit native librar{'y' if checked == 1 else 'ies'}.",
            file=sys.stderr,
        )
        return 1
    if checked == 0:
        print("16 KB page-size check: no 64-bit native libraries in the APK; nothing to check.")
    else:
        print(f"16 KB page-size check passed: {checked} 64-bit native librar"
              f"{'y' if checked == 1 else 'ies'} aligned.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
