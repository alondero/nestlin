#!/usr/bin/env python3
"""Unit tests for tools/rom_info.py.

Run with: python tools/test_rom_info.py
or:        python -m unittest tools/test_rom_info.py

These tests exercise the iNES / NES 2.0 decoder against the byte equations
documented in src/main/kotlin/.../gamepak/GamePak.kt (Header class). When
those equations change, the Kotlin regression tests in GamePakTest.kt and
these tests must be updated together.

The fixture ROMs are built in memory to keep tests deterministic and free
of any dependency on the developer's NO-INTRO library. nestest.nes is the
one exception: it's a real in-repo ROM and is a useful cross-check.
"""

import os
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

# Make sure we can import the module under test whether invoked from the
# repo root or from inside tools/.
sys.path.insert(0, str(Path(__file__).resolve().parent))
import rom_info


# ---------------------------------------------------------------------------
# Fixture helpers
# ---------------------------------------------------------------------------

def make_rom(
    prg_banks: int = 1,
    chr_banks: int = 1,
    *,
    b6: int = 0x00,
    b7: int = 0x00,
    b8: int = 0x00,
    b9: int = 0x00,
    b10: int = 0x00,
    b11: int = 0x00,
    b12: int = 0x00,
    b13: int = 0x00,
    b14: int = 0x00,
    b15: int = 0x00,
    fill_prg: int = 0xEA,
    fill_chr: int = 0x00,
) -> bytes:
    """Build a minimal valid iNES / NES 2.0 ROM with custom header bytes.

    All bank contents are filled with a single byte so the test can verify
    "we read what we wrote" or distinguish banks if it needs to. The
    16-byte header is constructed with the supplied control bytes; the
    "NES\\x1A" magic and the bank counts (bytes 4 and 5) are pinned from
    the function arguments.
    """
    header = bytearray(16)
    header[0:4] = b"NES\x1A"
    header[4] = prg_banks & 0xFF
    header[5] = chr_banks & 0xFF
    header[6] = b6
    header[7] = b7
    header[8] = b8
    header[9] = b9
    header[10] = b10
    header[11] = b11
    header[12] = b12
    header[13] = b13
    header[14] = b14
    header[15] = b15
    prg = bytes([fill_prg]) * (prg_banks * 16 * 1024)
    chr_ = bytes([fill_chr]) * (chr_banks * 8 * 1024)
    return bytes(header) + prg + chr_


def write_temp_rom(data: bytes, suffix: str = ".nes") -> Path:
    """Write `data` to a temp file and return the Path."""
    fd, name = tempfile.mkstemp(suffix=suffix)
    os.close(fd)
    p = Path(name)
    p.write_bytes(data)
    return p


# ---------------------------------------------------------------------------
# Header decode — iNES 1.0
# ---------------------------------------------------------------------------

class PlainInesDecodeTest(unittest.TestCase):
    """The plain-iNES paths of Header.kt mirrored in decode_header()."""

    def test_nestest_rom_decodes_to_mapper_zero(self):
        # The real in-repo ROM. If this fails, our decoder has drifted from
        # Header.kt's known-true behaviour.
        repo_root = Path(__file__).resolve().parent.parent
        rom = repo_root / "testroms" / "nestest.nes"
        if not rom.is_file():
            self.skipTest(f"nestest.nes not found at {rom}")
        h = rom_info.decode_header(str(rom))
        self.assertEqual(h.is_nes20, False)
        self.assertEqual(h.mapper, 0)
        self.assertEqual(h.submapper, 0)
        self.assertEqual(h.prg_banks, 1)
        self.assertEqual(h.chr_banks, 1)
        self.assertEqual(h.prg_bytes, 16 * 1024)
        self.assertEqual(h.chr_bytes, 8 * 1024)
        self.assertEqual(h.mirroring, "horizontal")
        self.assertEqual(h.has_battery, False)
        self.assertEqual(h.region_from_header, None)
        self.assertEqual(h.region_from_name, None)  # filename has no marker
        self.assertEqual(h.region_effective, "NTSC")
        self.assertFalse(h.mislabel_warnings)

    def test_plain_ines_byte7_bit4_is_mapper_bit(self):
        # Crayon Shin-chan case from GamePakTest.kt:plainInesByte7Bit4IsMapperBit.
        # byte6=0x00, byte7=0x10 -> mapper = 0 | 0x10 = 16.
        rom = make_rom(b6=0x00, b7=0x10)
        h = rom_info.decode_bytes(rom, full_crc=False)
        self.assertEqual(h.is_nes20, False)
        self.assertEqual(h.mapper, 16)
        self.assertEqual(h.submapper, 0)

    def test_plain_ines_byte8_is_prg_ram_not_submapper(self):
        # In plain iNES, byte 8 is the PRG-RAM size field and must NOT be
        # interpreted as a submapper. submapper stays 0.
        rom = make_rom(b6=0x00, b7=0x00, b8=0xFF)
        h = rom_info.decode_bytes(rom, full_crc=False)
        self.assertEqual(h.is_nes20, False)
        self.assertEqual(h.submapper, 0)

    def test_vertical_mirroring_when_byte6_bit0_set(self):
        rom = make_rom(b6=0x01)
        h = rom_info.decode_bytes(rom, full_crc=False)
        self.assertEqual(h.mirroring, "vertical")

    def test_battery_flag_when_byte6_bit1_set(self):
        rom = make_rom(b6=0x02)
        h = rom_info.decode_bytes(rom, full_crc=False)
        self.assertTrue(h.has_battery)

    def test_mapper_4_namco108_verification_prompt(self):
        # mapper 4 fires a verification prompt (most mapper-4 games are
        # real MMC3; only the named Namco 108 titles are mislabeled).
        # Real mapper-4 byte pattern: byte 6 high nibble = 4 -> b6 = 0x40.
        rom = make_rom(b6=0x40, b7=0x00)
        h = rom_info.decode_bytes(rom, full_crc=False)
        self.assertEqual(h.mapper, 4)
        self.assertTrue(h.mislabel_warnings, "mapper 4 should prompt Namco 108 verification")
        # The prompt must mention the actual mapper, the family, the known
        # titles, and the patch recipe — not assert "this IS a mislabel".
        w = h.mislabel_warnings[0]
        self.assertIn("206", w)
        self.assertIn("Namco 108", w)
        self.assertIn("Gauntlet", w)
        self.assertIn("patch recipe", w.lower())
        self.assertIn("if your rom", w.lower())


# ---------------------------------------------------------------------------
# Header decode — NES 2.0
# ---------------------------------------------------------------------------

class Nes20DecodeTest(unittest.TestCase):
    """The NES 2.0 paths of Header.kt mirrored in decode_header()."""

    def test_is_nes20_marker(self):
        # byte 7 bits 2-3 == 0b10 means NES 2.0.
        rom = make_rom(b7=0x08)
        h = rom_info.decode_bytes(rom, full_crc=False)
        self.assertTrue(h.is_nes20)

    def test_is_not_nes20_when_marker_absent(self):
        # byte 7 bits 2-3 == 0b00 -> iNES 1.0 even with high mapper bits.
        rom = make_rom(b6=0x20, b7=0x10)
        h = rom_info.decode_bytes(rom, full_crc=False)
        self.assertFalse(h.is_nes20)

    def test_nes20_byte7_bit4_is_mapper_bit(self):
        # Rokudenashi Blues from GamePakTest.kt: byte6=0x02, byte7=0x18,
        # byte8=0x50. mapper = 0 | (0x18 & 0xF0)=0x10 | 0 = 16.
        # submapper = 5 (byte 8 high nibble).
        rom = make_rom(b6=0x02, b7=0x18, b8=0x50)
        h = rom_info.decode_bytes(rom, full_crc=False)
        self.assertTrue(h.is_nes20)
        self.assertEqual(h.mapper, 16)
        self.assertEqual(h.submapper, 5)

    def test_nes20_mapper_256_uses_byte8_low_nibble(self):
        # NES 2.0 + mapper 256: byte 8 low nibble = 1 -> mapper = 256.
        rom = make_rom(b7=0x08, b8=0x01)
        h = rom_info.decode_bytes(rom, full_crc=False)
        self.assertTrue(h.is_nes20)
        self.assertEqual(h.mapper, 256)

    def test_nes20_submapper_does_not_leak_into_mapper(self):
        # byte 8 = 0x51: low nibble 1 (mapper bits 8-11) + high nibble 5 (submapper).
        # mapper must be 256, NOT 0x5100.
        rom = make_rom(b7=0x08, b8=0x51)
        h = rom_info.decode_bytes(rom, full_crc=False)
        self.assertEqual(h.mapper, 256)
        self.assertEqual(h.submapper, 5)

    def test_nes20_region_ntsc_when_byte12_low_bits_00(self):
        rom = make_rom(b7=0x08, b12=0x00)
        h = rom_info.decode_bytes(rom, full_crc=False)
        self.assertEqual(h.region_from_header, "NTSC")
        self.assertEqual(h.region_effective, "NTSC")

    def test_nes20_region_pal_when_byte12_low_bits_01(self):
        rom = make_rom(b7=0x08, b12=0x01)
        h = rom_info.decode_bytes(rom, full_crc=False)
        self.assertEqual(h.region_from_header, "PAL")
        self.assertEqual(h.region_effective, "PAL")

    def test_nes20_region_dendy_when_byte12_low_bits_11(self):
        rom = make_rom(b7=0x08, b12=0x03)
        h = rom_info.decode_bytes(rom, full_crc=False)
        self.assertEqual(h.region_from_header, "PAL")
        self.assertEqual(h.region_effective, "PAL")

    def test_nes20_region_undecided_when_byte12_low_bits_10(self):
        # "both" -> undecided. Region falls back to filename / NTSC.
        rom = make_rom(b7=0x08, b12=0x02)
        h = rom_info.decode_bytes(rom, full_crc=False)
        self.assertIsNone(h.region_from_header)

    def test_plain_ines_region_pal_flag_in_byte9(self):
        # iNES byte 9 bit 0 = PAL. Set the bit; absence is NOT NTSC evidence.
        rom = make_rom(b9=0x01)
        h = rom_info.decode_bytes(rom, full_crc=False)
        self.assertEqual(h.region_from_header, "PAL")
        self.assertEqual(h.region_effective, "PAL")

    def test_plain_ines_region_unset_byte9_means_no_header_evidence(self):
        rom = make_rom(b9=0x00)
        h = rom_info.decode_bytes(rom, full_crc=False)
        self.assertIsNone(h.region_from_header)


# ---------------------------------------------------------------------------
# Region inference from NO-INTRO filename
# ---------------------------------------------------------------------------

class RegionFromNameTest(unittest.TestCase):

    def test_usa_filename_implies_ntsc(self):
        rom = make_rom()
        p = write_temp_rom(rom)
        try:
            # Rename to mimic a NO-INTRO USA dump.
            new_p = p.with_name("Gauntlet (USA).nes")
            p.rename(new_p)
            h = rom_info.decode_header(str(new_p), full_crc=False)
            self.assertEqual(h.region_from_name, "NTSC")
        finally:
            if new_p.exists():
                new_p.unlink()

    def test_europe_filename_implies_pal(self):
        rom = make_rom()
        p = write_temp_rom(rom)
        try:
            new_p = p.with_name("Some Game (Europe).nes")
            p.rename(new_p)
            h = rom_info.decode_header(str(new_p), full_crc=False)
            self.assertEqual(h.region_from_name, "PAL")
        finally:
            if new_p.exists():
                new_p.unlink()

    def test_header_wins_over_filename(self):
        # NES 2.0 header says NTSC; filename says Europe. Effective is NTSC.
        rom = make_rom(b7=0x08, b12=0x00)
        p = write_temp_rom(rom)
        try:
            new_p = p.with_name("Conflict (Europe).nes")
            p.rename(new_p)
            h = rom_info.decode_header(str(new_p), full_crc=False)
            self.assertEqual(h.region_from_name, "PAL")
            self.assertEqual(h.region_from_header, "NTSC")
            self.assertEqual(h.region_effective, "NTSC")
        finally:
            if new_p.exists():
                new_p.unlink()


# ---------------------------------------------------------------------------
# Vectors
# ---------------------------------------------------------------------------

class VectorsTest(unittest.TestCase):

    def test_vectors_read_from_last_bank(self):
        # Build a 2-PRG-bank ROM, place known vectors at the end of bank 1.
        nmi = 0xC123
        reset = 0xC456
        irq = 0xC789
        rom = bytearray(make_rom(prg_banks=2, chr_banks=0))
        # Vector offsets within the file:
        #   last_bank_start = 16 (header) + 1 * 16384 = 16400
        #   $FFFA within the last bank is at byte 0x3FFA (0xFFFA - 0xC000).
        #   So the NMI vector lives at file offset 16400 + 0x3FFA = 32778.
        vec_offset = 16 + 1 * 16384 + 0x3FFA
        struct.pack_into("<H", rom, vec_offset + 0, nmi)
        struct.pack_into("<H", rom, vec_offset + 2, reset)
        struct.pack_into("<H", rom, vec_offset + 4, irq)
        p = write_temp_rom(bytes(rom))
        try:
            v = rom_info.read_vectors(str(p))
            self.assertEqual(v.nmi, nmi)
            self.assertEqual(v.reset, reset)
            self.assertEqual(v.irq, irq)
        finally:
            p.unlink()

    def test_vectors_for_one_bank_rom(self):
        # NROM-128: 1 PRG bank mirrored to $8000 and $C000. Vectors at end
        # of the (only) bank, file offset 16 + 0x3FFA.
        nmi = 0x8000
        reset = 0xC000
        irq = 0xFFFA
        rom = bytearray(make_rom(prg_banks=1, chr_banks=0))
        vec_offset = 16 + 0x3FFA
        struct.pack_into("<H", rom, vec_offset + 0, nmi)
        struct.pack_into("<H", rom, vec_offset + 2, reset)
        struct.pack_into("<H", rom, vec_offset + 4, irq)
        p = write_temp_rom(bytes(rom))
        try:
            v = rom_info.read_vectors(str(p))
            self.assertEqual(v.nmi, nmi)
            self.assertEqual(v.reset, reset)
            self.assertEqual(v.irq, irq)
        finally:
            p.unlink()


# ---------------------------------------------------------------------------
# CPU address <-> file offset math
# ---------------------------------------------------------------------------

class AddrMathTest(unittest.TestCase):

    def test_cpu_to_file_offset_first_bank(self):
        # $8000 in bank 0 of a 2-bank ROM = 16 + 0 = 16.
        off = rom_info.cpu_to_file_offset(prg_banks=2, cpu_addr=0x8000, bank=0)
        self.assertEqual(off, 16)

    def test_cpu_to_file_offset_second_bank(self):
        # $C000 in bank 1 of a 2-bank ROM. Bank 1 is the fixed last bank,
        # so $C000 is the first byte of that bank (offset 0 within bank 1).
        off = rom_info.cpu_to_file_offset(prg_banks=2, cpu_addr=0xC000, bank=1)
        self.assertEqual(off, 16 + 1 * 16384 + 0)

    def test_cpu_to_file_offset_within_bank(self):
        # $EA40 in bank 1 of a 2-bank ROM. Bank 1 is fixed at $C000, so
        # offset_in_bank = $EA40 - $C000 = $2A40, NOT $EA40 - $8000.
        off = rom_info.cpu_to_file_offset(prg_banks=2, cpu_addr=0xEA40, bank=1)
        self.assertEqual(off, 16 + 16384 + (0xEA40 - 0xC000))

    def test_cpu_to_file_offset_rejects_swappable_addr_in_last_bank(self):
        # $8000 in bank N-1 of a multi-bank ROM is inconsistent: the last
        # bank is mapped at $C000-$FFFF only. The fixed/swappable split
        # must be enforced.
        with self.assertRaises(ValueError):
            rom_info.cpu_to_file_offset(prg_banks=2, cpu_addr=0x8000, bank=1)

    def test_cpu_to_file_offset_rejects_fixed_addr_in_swappable_bank(self):
        # $C000 in bank 0 of a 2-bank ROM is inconsistent: bank 0 is
        # mapped at $8000-$BFFF only.
        with self.assertRaises(ValueError):
            rom_info.cpu_to_file_offset(prg_banks=2, cpu_addr=0xC000, bank=0)

    def test_cpu_to_file_offset_one_bank_mirrors_both_halves(self):
        # NROM-128 (1 PRG bank): the single bank is mirrored to BOTH
        # $8000-$BFFF and $C000-$FFFF. $8000 and $C000 point at the same
        # file offset (16).
        self.assertEqual(
            rom_info.cpu_to_file_offset(prg_banks=1, cpu_addr=0x8000, bank=0),
            16,
        )
        self.assertEqual(
            rom_info.cpu_to_file_offset(prg_banks=1, cpu_addr=0xC000, bank=0),
            16,
        )

    def test_cpu_to_file_offset_rejects_out_of_range(self):
        with self.assertRaises(ValueError):
            rom_info.cpu_to_file_offset(prg_banks=1, cpu_addr=0x7FFF, bank=0)
        with self.assertRaises(ValueError):
            rom_info.cpu_to_file_offset(prg_banks=1, cpu_addr=0x8000, bank=1)

    def test_file_to_cpu_addr_roundtrip(self):
        # 4-bank ROM; pick a deep address and check the round-trip.
        # Each case: (file_offset, expected_cpu, expected_bank).
        # The vector region ($FFFA-$FFFF) of bank 3 lives at file offsets
        # 16 + 3*16384 + 0x3FFA .. 16 + 3*16384 + 0x4000 (exclusive).
        # Bank 3 is the LAST bank, so file offsets inside it map to $C000+
        # (not $8000+). Banks 0..2 are swappable at $8000+.
        prg_banks = 4
        cases = [
            (16 + 0 * 16384 + 0x0000, 0x8000, 0),         # bank 0, first byte
            (16 + 2 * 16384 + 0x1234, 0x9234, 2),         # bank 2, mid-PRG
            (16 + 3 * 16384 + 0x0000, 0xC000, 3),         # bank 3, $C000
            (16 + 3 * 16384 + 0x3FFA, 0xFFFA, 3),         # bank 3, NMI vector
            (16 + 3 * 16384 + 0x3FFF, 0xFFFF, 3),         # bank 3, last byte
        ]
        for file_off, expected_cpu, expected_bank in cases:
            cpu, bank = rom_info.file_to_cpu_addr(file_off, prg_banks)
            self.assertEqual(cpu, expected_cpu, f"file 0x{file_off:X}")
            self.assertEqual(bank, expected_bank, f"file 0x{file_off:X}")
            # And the inverse should match. cpu_to_file_offset needs the
            # CPU addr to be in the right half ($8000 for swappable banks,
            # $C000 for the fixed last bank); we just confirm the function
            # round-trips back to the same file offset.
            self.assertEqual(
                rom_info.cpu_to_file_offset(prg_banks=prg_banks, cpu_addr=cpu, bank=bank),
                file_off,
            )

    def test_file_to_cpu_addr_one_bank_rom(self):
        # NROM-128: 1 PRG bank mirrored to BOTH $8000 and $C000. A file
        # offset anywhere in the bank should report the $C000-region
        # address (since it's the only bank, it IS the last bank).
        cpu, bank = rom_info.file_to_cpu_addr(16 + 0x3FFA, prg_banks=1)
        self.assertEqual(cpu, 0xFFFA)
        self.assertEqual(bank, 0)

    def test_file_to_cpu_addr_rejects_header_offset(self):
        with self.assertRaises(ValueError):
            rom_info.file_to_cpu_addr(8, prg_banks=1)   # inside the 16-byte header

    def test_resolve_bank_keyword(self):
        self.assertEqual(rom_info.resolve_bank("last", 4), 3)
        self.assertEqual(rom_info.resolve_bank("fixed", 4), 3)
        self.assertEqual(rom_info.resolve_bank("2", 4), 2)
        self.assertEqual(rom_info.resolve_bank("0x2", 4), 2)


# ---------------------------------------------------------------------------
# Namco 108 patch
# ---------------------------------------------------------------------------

class PatchNamco108Test(unittest.TestCase):

    def test_patch_changes_byte6_and_byte7(self):
        # NO-INTRO Namco 108 dump pattern: mapper 4 in bytes 6/7.
        # b6 = 0x40 (mapper low = 4), b7 = 0x00.
        rom = make_rom(b6=0x40, b7=0x00)
        p = write_temp_rom(rom)
        try:
            out, diff = rom_info.patch_namco108(str(p))
            try:
                self.assertEqual(diff["byte6_before"], "0x40")
                self.assertEqual(diff["byte6_after"], "0xE0")
                self.assertEqual(diff["byte7_before"], "0x00")
                self.assertEqual(diff["byte7_after"], "0xC0")
                self.assertEqual(diff["mapper_after"], 206)
                # Output must decode as mapper 206.
                h = rom_info.decode_header(str(out), full_crc=False)
                self.assertEqual(h.mapper, 206)
            finally:
                if out.exists():
                    out.unlink()
        finally:
            p.unlink()

    def test_patch_preserves_other_bytes(self):
        rom = make_rom(b6=0x40, b7=0x00, fill_prg=0xCD)
        p = write_temp_rom(rom)
        try:
            out, _ = rom_info.patch_namco108(str(p))
            try:
                with open(out, "rb") as f:
                    patched = f.read()
                # Header bytes 0..5 (magic + bank counts) must be unchanged.
                self.assertEqual(patched[0:6], rom[0:6])
                # Header bytes 8..15 must be unchanged.
                self.assertEqual(patched[8:16], rom[8:16])
                # PRG body must be unchanged.
                self.assertEqual(patched[16:], rom[16:])
            finally:
                if out.exists():
                    out.unlink()
        finally:
            p.unlink()

    def test_patch_does_not_modify_source(self):
        # The "never touches the source" guarantee is the whole point of
        # using a sibling copy; lock it in with a hash check.
        rom = make_rom(b6=0x40, b7=0x00)
        p = write_temp_rom(rom)
        try:
            source_hash_before = zlib.crc32(rom) & 0xFFFFFFFF
            out, _ = rom_info.patch_namco108(str(p))
            try:
                with open(p, "rb") as f:
                    source_bytes_after = f.read()
                source_hash_after = zlib.crc32(source_bytes_after) & 0xFFFFFFFF
                self.assertEqual(source_hash_after, source_hash_before)
                # And the file on disk is byte-identical to the original.
                self.assertEqual(source_bytes_after, rom)
            finally:
                if out.exists():
                    out.unlink()
        finally:
            p.unlink()

    def test_patch_rejects_non_ines(self):
        with tempfile.NamedTemporaryFile(suffix=".nes", delete=False) as f:
            f.write(b"NOPE\x00" + b"\x00" * 100)
            bad = f.name
        try:
            with self.assertRaises(rom_info.BadRomFile):
                rom_info.patch_namco108(bad)
        finally:
            os.unlink(bad)


# ---------------------------------------------------------------------------
# Library scan
# ---------------------------------------------------------------------------

class ScanLibraryTest(unittest.TestCase):

    def test_scan_finds_all_roms(self):
        with tempfile.TemporaryDirectory() as td:
            tdp = Path(td)
            # Write three ROMs with known mappers.
            (tdp / "a.nes").write_bytes(make_rom(b6=0x00))           # mapper 0
            (tdp / "b.nes").write_bytes(make_rom(b6=0x20, b7=0x10))  # mapper 0x12 = 18
            (tdp / "c.nes").write_bytes(make_rom(b6=0x40, b7=0x00))  # mapper 4 (Namco 108)
            # One non-ROM file that must be skipped, not crash the scan.
            (tdp / "readme.txt").write_text("not a ROM")

            rows = rom_info.scan_library(tdp)
            mappers = sorted(r.mapper for r in rows)
            self.assertEqual(mappers, [0, 4, 18])
            # Mislabel flag for the Namco 108 dump.
            namco = next(r for r in rows if r.mapper == 4)
            self.assertTrue(namco.mislabel)

    def test_scan_filters_by_mapper(self):
        with tempfile.TemporaryDirectory() as td:
            tdp = Path(td)
            (tdp / "a.nes").write_bytes(make_rom(b6=0x00))           # mapper 0
            (tdp / "b.nes").write_bytes(make_rom(b6=0x20, b7=0x10))  # mapper 18
            (tdp / "c.nes").write_bytes(make_rom(b6=0x40, b7=0x00))  # mapper 4
            rows = rom_info.scan_library(tdp, mapper=0)
            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0].mapper, 0)

    def test_scan_silently_skips_invalid_roms(self):
        with tempfile.TemporaryDirectory() as td:
            tdp = Path(td)
            (tdp / "good.nes").write_bytes(make_rom(b6=0x00))
            (tdp / "bad.nes").write_bytes(b"NOT A ROM AT ALL")
            rows = rom_info.scan_library(tdp)
            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0].filename, "good.nes")


# ---------------------------------------------------------------------------
# Bad input handling
# ---------------------------------------------------------------------------

class BadInputTest(unittest.TestCase):

    def test_missing_file(self):
        with self.assertRaises(rom_info.BadRomFile):
            rom_info.decode_header("/nonexistent/path.nes")

    def test_truncated_file(self):
        with tempfile.NamedTemporaryFile(suffix=".nes", delete=False) as f:
            f.write(b"NES\x1A")  # only 4 bytes
            truncated = f.name
        try:
            with self.assertRaises(rom_info.BadRomFile):
                rom_info.decode_header(truncated)
        finally:
            os.unlink(truncated)

    def test_wrong_magic(self):
        with tempfile.NamedTemporaryFile(suffix=".nes", delete=False) as f:
            f.write(b"FOO\x00" + b"\x00" * 100)
            wrong = f.name
        try:
            with self.assertRaises(rom_info.BadRomFile):
                rom_info.decode_header(wrong)
        finally:
            os.unlink(wrong)


if __name__ == "__main__":
    unittest.main()
