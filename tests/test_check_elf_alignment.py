"""ANDROID-REL-03: the 16 KB page-size check fails on a misaligned library and passes on an aligned one."""

from __future__ import annotations

import io
import struct
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

from scripts import check_elf_alignment

PAGE_4K = 0x1000
PAGE_16K = 0x4000


def elf64(load_alignments: list[int]) -> bytes:
    """A minimal little-endian ELF64 image with one PT_LOAD per alignment."""
    header_size, program_header_size = 64, 56
    header = bytearray(header_size)
    header[0:4] = b"\x7fELF"
    header[4], header[5], header[6] = 2, 1, 1
    struct.pack_into("<Q", header, 0x20, header_size)
    struct.pack_into("<HH", header, 0x36, program_header_size, len(load_alignments))
    body = bytearray()
    for alignment in load_alignments:
        entry = bytearray(program_header_size)
        struct.pack_into("<I", entry, 0, 1)
        struct.pack_into("<Q", entry, 0x30, alignment)
        body += entry
    return bytes(header + body)


def elf32(load_alignments: list[int]) -> bytes:
    header_size, program_header_size = 52, 32
    header = bytearray(header_size)
    header[0:4] = b"\x7fELF"
    header[4], header[5], header[6] = 1, 1, 1
    struct.pack_into("<I", header, 0x1C, header_size)
    struct.pack_into("<HH", header, 0x2A, program_header_size, len(load_alignments))
    body = bytearray()
    for alignment in load_alignments:
        entry = bytearray(program_header_size)
        struct.pack_into("<I", entry, 0, 1)
        struct.pack_into("<I", entry, 0x1C, alignment)
        body += entry
    return bytes(header + body)


def write_apk(path: Path, libraries: dict[str, bytes], *, stored_at_16k: bool = True,
              compressed: bool = False) -> None:
    """Write an APK; stored libraries are padded to a 16 KB boundary when asked."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as apk:
        apk.writestr("AndroidManifest.xml", b"<manifest/>")
        for name, data in libraries.items():
            info = zipfile.ZipInfo(name)
            info.compress_type = zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED
            if not compressed and stored_at_16k:
                data_start = buffer.tell() + 30 + len(name.encode())
                pad = -data_start % PAGE_16K
                while 0 < pad < 4:
                    pad += PAGE_16K
                if pad:
                    info.extra = struct.pack("<HH", 0xD935, pad - 4) + bytes(pad - 4)
            apk.writestr(info, data)
    path.write_bytes(buffer.getvalue())


def run(apk: Path) -> tuple[int, str, str]:
    out, err = io.StringIO(), io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        status = check_elf_alignment.main(["check_elf_alignment.py", str(apk)])
    return status, out.getvalue(), err.getvalue()


class ElfAlignmentTests(unittest.TestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.apk = Path(directory.name) / "test.apk"

    def test_aligned_libraries_pass(self) -> None:
        write_apk(self.apk, {
            "lib/arm64-v8a/libimage_processing_util_jni.so": elf64([PAGE_16K, PAGE_16K]),
            "lib/x86_64/libsurface_util_jni.so": elf64([PAGE_16K]),
        })
        status, out, err = run(self.apk)
        self.assertEqual(0, status, err)
        self.assertIn("passed: 2 64-bit native libraries aligned", out)

    def test_a_deliberately_misaligned_library_fails(self) -> None:
        write_apk(self.apk, {
            "lib/arm64-v8a/libgood.so": elf64([PAGE_16K]),
            "lib/arm64-v8a/libbad.so": elf64([PAGE_16K, PAGE_4K]),
        })
        status, _, err = run(self.apk)
        self.assertEqual(1, status)
        self.assertIn("lib/arm64-v8a/libbad.so: LOAD segment alignment 0x1000", err)
        self.assertNotIn("libgood.so:", err)

    def test_a_stored_library_off_a_16k_zip_offset_fails(self) -> None:
        write_apk(self.apk, {"lib/x86_64/libshift.so": elf64([PAGE_16K])}, stored_at_16k=False)
        status, _, err = run(self.apk)
        self.assertEqual(1, status)
        self.assertIn("lib/x86_64/libshift.so: stored at APK offset", err)

    def test_a_compressed_library_needs_no_zip_alignment(self) -> None:
        write_apk(self.apk, {"lib/arm64-v8a/libpacked.so": elf64([PAGE_16K])}, compressed=True)
        status, out, err = run(self.apk)
        self.assertEqual(0, status, err)
        self.assertIn("compressed (extracted at install)", out)

    def test_32_bit_abis_are_not_enforced(self) -> None:
        write_apk(self.apk, {"lib/armeabi-v7a/libold.so": elf32([PAGE_4K])})
        status, out, err = run(self.apk)
        self.assertEqual(0, status, err)
        self.assertIn("skip   lib/armeabi-v7a/libold.so", out)

    def test_an_unreadable_library_fails(self) -> None:
        write_apk(self.apk, {"lib/arm64-v8a/libjunk.so": b"not an elf"})
        status, _, err = run(self.apk)
        self.assertEqual(1, status)
        self.assertIn("unreadable ELF", err)

    def test_an_apk_without_64_bit_libraries_says_so(self) -> None:
        write_apk(self.apk, {})
        status, out, _ = run(self.apk)
        self.assertEqual(0, status)
        self.assertIn("nothing to check", out)


if __name__ == "__main__":
    unittest.main()
