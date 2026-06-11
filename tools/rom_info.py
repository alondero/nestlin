#!/usr/bin/env python3
"""
NES ROM Header Decoder + Library Utilities

Sibling of dump_analyzer.py. The single source of truth for iNES / NES 2.0
header decoding in Nestlin lives in src/main/kotlin/.../gamepak/GamePak.kt
(Header class). This tool mirrors that decoder byte-for-byte so an agent
working outside the emulator core can answer "what is this ROM" without
hand-decoding 16 bytes.

Subcommands:
    info         <rom.nes>            decode header: mapper, submapper, NES 2.0?,
                                       PRG/CHR sizes, battery, mirroring, region
                                       (header + filename), CRC32
    scan         <dir> [--mapper N]   walk a ROM library, list titles by mapper
    patch-namco108 <rom.nes>          emit a sibling COPY with the NO-INTRO
                                       Namco 108 header patch applied
                                       (mapper 4 -> mapper 206)
    vectors      <rom.nes>            NMI / RESET / IRQ vectors from the
                                       fixed last bank
    addr         <rom.nes> --cpu-addr 0xXXXX [--bank N|last]
                                       CPU addr <-> file offset math + hexdump

The library path defaults to the dev machine's NO-INTRO library
(S:\\Media\\Nintendo NES\\Games, override with --library or ROMS_PATH env var).

Usage:
    python rom_info.py info path/to/rom.nes
    python rom_info.py scan S:\\Media\\Nintendo\\NES\\Games --mapper 33
    python rom_info.py patch-namco108 game.nes
    python rom_info.py vectors game.nes
    python rom_info.py addr game.nes --cpu-addr 0xEA40 --bank last

Exit codes: 0 OK · 1 usage / argument error · 2 ROM file unreadable or
not a valid iNES file.
"""

import argparse
import os
import shutil
import struct
import sys
import zlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, List, Optional, Tuple

# =============================================================================
# iNES constants (mirror src/main/kotlin/.../gamepak/GamePak.kt)
# =============================================================================

INES_MAGIC = b"NES\x1A"            # bytes 0..3
INES_HEADER_SIZE = 16
PRG_BANK_BYTES = 16 * 1024         # 16 KB
CHR_BANK_BYTES = 8 * 1024          # 8 KB
VECTOR_NMI = 0xFFFA
VECTOR_RESET = 0xFFFC
VECTOR_IRQ = 0xFFFE

# Default library path on the dev machine. Can be overridden via the ROMS_PATH
# environment variable or the --library CLI flag. S:\\Media\\Nintendo NES\\Games
# is the canonical NO-INTRO archive per CLAUDE.local.md.
DEFAULT_LIBRARY = r"S:\Media\Nintendo NES\Games"

# NO-INTRO region markers copied verbatim from GamePak.kt (lines 138-149). The
# PAL list wins ties (a ROM tagged both, e.g. multi-region, is treated as PAL).
# Keeping this in lockstep with the Kotlin side means an agent running
# `rom_info.py info` will infer the same region Nestlin uses at boot.
NTSC_MARKERS = ("(usa", "(u)", "(japan", "(j)", "(jp", "(world", "(ntsc", "(brazil)")
PAL_MARKERS = (
    "(europe", "(e)", "(eur", "(pal", "(australia", "(france", "(germany",
    "(italy", "(spain", "(sweden", "(netherlands", "(uk", "(scandinavia",
)

# Mapper families that are *occasionally* mislabeled by NO-INTRO dumpers.
# The warning is a verification prompt, not a mislabel assertion: most ROMs
# with these iNES mapper numbers are correctly labeled. The user should
# cross-reference the title against the known-game list and only patch
# when the title is on it. (See memory file
# 'namco-108-ines-mapper-4-convention-2026-06-01.md' for the original
# investigation of the Namco 108 case.)
#
# Adding a new family: also add the patch recipe to `patch_namco108`-style
# helpers, or document the manual byte edits in the warning text.
KNOWN_MISLABEL_FAMILIES = {
    4: [
        {
            "actual_mapper": 206,
            "family": "Namco 108 / DxROM",
            "known_titles": "Gauntlet, Ring King, RBI Baseball, Mappy-Land",
            "patch_recipe": "byte6 = (b6 & 0x0F) | 0xE0; byte7 = (b7 & 0x0F) | 0xC0",
        },
    ],
}


# =============================================================================
# Header decode (mirror of Header.kt — see that file for the source equations
# and the regression tests in GamePakTest.kt that lock the rules in place).
# =============================================================================

@dataclass
class RomHeader:
    """Decoded iNES / NES 2.0 header. Field semantics match Header.kt."""
    path: str
    raw: bytes                       # first 16 bytes of the ROM file

    # Decoded fields
    is_nes20: bool = False
    mapper: int = 0
    submapper: int = 0
    prg_banks: int = 0
    chr_banks: int = 0
    prg_bytes: int = 0
    chr_bytes: int = 0
    mirroring: str = "?"              # "horizontal" or "vertical"
    has_battery: bool = False
    region_from_header: Optional[str] = None   # "NTSC" / "PAL" / None
    region_from_name: Optional[str] = None
    region_effective: str = "NTSC"             # header > name > NTSC
    crc32: int = 0
    file_size: int = 0
    mislabel_warnings: list = field(default_factory=list)

    @property
    def is_chr_ram(self) -> bool:
        """True when the game uses CHR-RAM (byte 5 = 0, no CHR ROM)."""
        return self.chr_banks == 0

    def as_dict(self) -> dict:
        return {
            "file": self.path,
            "size_bytes": self.file_size,
            "crc32": f"0x{self.crc32:08X}",
            "format": "NES 2.0" if self.is_nes20 else "iNES 1.0",
            "mapper": self.mapper,
            "submapper": self.submapper,
            "prg_banks": self.prg_banks,
            "prg_bytes": self.prg_bytes,
            "chr_banks": self.chr_banks,
            "chr_bytes": self.chr_bytes,
            "chr_rom_or_ram": "CHR-RAM" if self.is_chr_ram else "CHR-ROM",
            "mirroring": self.mirroring,
            "battery": self.has_battery,
            "region_header": self.region_from_header,
            "region_name": self.region_from_name,
            "region": self.region_effective,
            "warnings": self.mislabel_warnings,
        }


class BadRomFile(Exception):
    """The file is not a valid iNES / NES 2.0 ROM."""


def decode_header(path: str, *, full_crc: bool = True) -> RomHeader:
    """Decode the 16-byte iNES header of a ROM file.

    Args:
        path: Path to the .nes file.
        full_crc: If True (default), compute CRC32 over the whole file. Set
            False for the `scan` subcommand to keep large library walks fast
            (header-only mode reads 16 bytes per ROM).
    """
    p = Path(path)
    if not p.is_file():
        raise BadRomFile(f"Not a file: {path}")
    with open(p, "rb") as f:
        data = f.read()
    return decode_bytes(data, source=str(p), full_crc=full_crc)


def decode_bytes(data: bytes, *, source: str = "<bytes>", full_crc: bool = True) -> RomHeader:
    """Decode the iNES header of an in-memory ROM buffer.

    Used by [decode_header] (which reads the file) and by the Namco 108
    patcher (which decodes the patched COPY from memory without writing
    it to disk first). Tests use this entry point too — building the
    bytes directly is the cleanest way to assert a specific header pattern.
    """
    if len(data) < INES_HEADER_SIZE:
        raise BadRomFile(
            f"Buffer is {len(data)} bytes; iNES header requires at least "
            f"{INES_HEADER_SIZE} bytes"
        )
    if data[0:4] != INES_MAGIC:
        raise BadRomFile(
            f"Missing iNES header magic (got bytes "
            f"{data[0]:02X} {data[1]:02X} {data[2]:02X} {data[3]:02X})"
        )

    h = RomHeader(path=source, raw=bytes(data[:INES_HEADER_SIZE]), file_size=len(data))

    # Byte 4 = PRG banks (16KB units), byte 5 = CHR banks (8KB units, 0 = CHR-RAM).
    # Header.kt reads these as raw bytes; we mirror with mask to stay unsigned.
    h.prg_banks = data[4] & 0xFF
    h.chr_banks = data[5] & 0xFF
    h.prg_bytes = h.prg_banks * PRG_BANK_BYTES
    h.chr_bytes = h.chr_banks * CHR_BANK_BYTES

    # Byte 6: bit 0 mirroring (0=H, 1=V), bit 1 battery. Mapper bits 0-3 = b6>>4.
    b6 = data[6]
    h.has_battery = bool(b6 & 0x02)
    h.mirroring = "vertical" if (b6 & 0x01) else "horizontal"

    # NES 2.0 marker: byte 7 bits 2-3 == 0b10.
    b7 = data[7]
    h.is_nes20 = (b7 & 0x0C) == 0x08

    # Mapper decode (must match Header.kt:170-172 EXACTLY).
    #   bits 0-3  = byte 6 bits 4-7
    #   bits 4-7  = byte 7 bits 4-7  (bit 4 IS mapper bit D4 in both iNES and NES 2.0)
    #   bits 8-11 = byte 8 bits 0-3  (NES 2.0 only; high nibble of b8 is submapper)
    # In plain iNES, byte 8 is the PRG-RAM size — NOT part of the mapper.
    b8 = data[8] if len(data) > 8 else 0
    h.mapper = (b6 >> 4) | (b7 & 0xF0) | ((b8 & 0x0F) << 8 if h.is_nes20 else 0)

    # Submapper: NES 2.0 byte 8 high nibble. For plain iNES, byte 8 is the
    # PRG-RAM size byte — semantically unrelated; Header.kt returns 0 in that case.
    h.submapper = ((b8 >> 4) & 0x0F) if h.is_nes20 else 0

    # Region detection: header first, then NO-INTRO filename marker, then NTSC.
    # Header.kt treats iNES byte 9 bit 0 = 1 as PAL evidence; absence is NOT
    # NTSC evidence (the bit is almost universally clear, including on PAL ROMs).
    if h.is_nes20 and len(data) > 12:
        region_bits = data[12] & 0x03
        if region_bits == 0:
            h.region_from_header = "NTSC"
        elif region_bits in (1, 3):
            h.region_from_header = "PAL"
        # 2 = "both" -> undecided
    else:
        if len(data) > 9 and (data[9] & 0x01):
            h.region_from_header = "PAL"

    h.region_from_name = region_from_name(Path(source).name)
    h.region_effective = h.region_from_header or h.region_from_name or "NTSC"

    # CRC32 over the whole file (or just the header in scan mode).
    if full_crc:
        h.crc32 = zlib.crc32(data) & 0xFFFFFFFF
    else:
        h.crc32 = zlib.crc32(data[:INES_HEADER_SIZE]) & 0xFFFFFFFF

    # Known-family verification prompts (e.g. "if this is one of the
    # ~5 Namco 108 titles, the header is mislabeled; most mapper-4 games
    # are real MMC3"). The warning is a heads-up, not a mislabel claim.
    for entry in KNOWN_MISLABEL_FAMILIES.get(h.mapper, []):
        h.mislabel_warnings.append(
            f"iNES mapper {h.mapper} is the NO-INTRO label for mapper "
            f"{entry['actual_mapper']} ({entry['family']}) for these "
            f"specific titles: {entry['known_titles']}. If your ROM is "
            f"one of them, the header is mislabeled; if not, ignore. "
            f"Patch recipe if mislabeled: {entry['patch_recipe']}."
        )

    return h


def region_from_name(name: str) -> Optional[str]:
    """Return "NTSC" / "PAL" implied by NO-INTRO filename markers, else None.

    Mirrors GamePak.regionFromName (GamePak.kt:145-150). PAL list wins ties
    only when the NTSC list is silent; in practice the lists are disjoint.
    """
    lower = name.lower()
    if any(m in lower for m in NTSC_MARKERS):
        return "NTSC"
    if any(m in lower for m in PAL_MARKERS):
        return "PAL"
    return None


# =============================================================================
# Vectors (NMI / RESET / IRQ at $FFFA-$FFFF of the fixed last bank)
# =============================================================================

@dataclass
class RomVectors:
    nmi: int
    reset: int
    irq: int

    def as_dict(self) -> dict:
        return {
            "NMI": f"0x{self.nmi:04X}",
            "RESET": f"0x{self.reset:04X}",
            "IRQ": f"0x{self.irq:04X}",
        }


def read_vectors(path: str) -> RomVectors:
    """Read NMI / RESET / IRQ vectors from $FFFA-$FFFF of the last 16KB bank.

    For NROM (mapper 0) with one PRG bank the bank is mirrored to both $8000
    and $C000, so this still resolves to the right place. Multi-bank games
    (mapper 0 with 2 PRG banks, and most mappers) follow the convention that
    the LAST 16KB bank is fixed at $C000 and holds the vectors.
    """
    p = Path(path)
    with open(p, "rb") as f:
        data = f.read()
    if len(data) < INES_HEADER_SIZE + PRG_BANK_BYTES:
        raise BadRomFile("ROM too small to contain a PRG bank")
    h = decode_header(p, full_crc=False)
    if h.prg_banks == 0:
        raise BadRomFile("ROM declares zero PRG banks")

    # File offset of the start of the last PRG bank:
    #   16 (header) + (prg_banks - 1) * 16384
    last_bank_offset = INES_HEADER_SIZE + (h.prg_banks - 1) * PRG_BANK_BYTES
    # Vectors live at the END of that bank, at the file offset of $FFFA.
    # Within the last (fixed) bank, $FFFA is at byte 0x3FFA (0xFFFA - 0xC000).
    # The 6 vector bytes span [vec_offset .. vec_offset + 6).
    vec_offset = last_bank_offset + (VECTOR_NMI - 0xC000)
    if vec_offset + 6 > len(data):
        raise BadRomFile(
            f"ROM truncated before vectors (need {vec_offset + 6} bytes, "
            f"have {len(data)})"
        )
    nmi, reset, irq = struct.unpack_from("<HHH", data, vec_offset)
    return RomVectors(nmi=nmi, reset=reset, irq=irq)


# =============================================================================
# CPU addr <-> file offset math
# =============================================================================

@dataclass
class AddrResult:
    cpu_addr: int
    bank: int                # 0-indexed PRG bank number
    file_offset: int
    hexdump: str

    def as_dict(self) -> dict:
        return {
            "cpu_addr": f"0x{self.cpu_addr:04X}",
            "bank": self.bank,
            "file_offset": f"0x{self.file_offset:06X}",
            "hexdump": self.hexdump,
        }


def cpu_to_file_offset(prg_banks: int, cpu_addr: int, bank: int) -> int:
    """Translate a CPU address to a file offset.

    Layout: [16-byte header][16KB PRG bank 0][16KB PRG bank 1]...
    The PRG-ROM window is split in two:
      - $8000-$BFFF: a swappable bank (bank index 0..N-2)
      - $C000-$FFFF: the FIXED last bank (bank index N-1, where the
        NMI / RESET / IRQ vectors live)

    This split holds for every standard NES mapper: NROM, MMC3, VRC, etc.
    NROM-128 (1 bank) is a degenerate case: bank 0 mirrors to BOTH halves,
    so the "fixed last" and "swappable" collapse to the same bank.

    Args:
        prg_banks: Total PRG bank count (must be > 0).
        cpu_addr: CPU address in $8000-$FFFF.
        bank: PRG bank index to read from (0-indexed).

    Raises:
        ValueError: When cpu_addr / bank are out of range, or when the
            cpu_addr / bank pair is inconsistent with the fixed/swappable
            split above.
    """
    if prg_banks <= 0:
        raise ValueError("ROM has zero PRG banks")
    if not 0x8000 <= cpu_addr <= 0xFFFF:
        raise ValueError(f"CPU address 0x{cpu_addr:04X} outside PRG ROM window $8000-$FFFF")
    if not 0 <= bank < prg_banks:
        raise ValueError(f"bank {bank} out of range 0..{prg_banks - 1}")
    last = prg_banks - 1
    if cpu_addr >= 0xC000:
        # $C000-$FFFF is served ONLY by the fixed last bank.
        if bank != last:
            raise ValueError(
                f"CPU 0x{cpu_addr:04X} is in the fixed last bank (bank {last}); "
                f"got bank {bank}"
            )
        offset_in_bank = cpu_addr - 0xC000
    else:
        # $8000-$BFFF is a swappable bank. The 1-bank case (NROM-128)
        # mirrors the only bank to both halves, so bank 0 == last is OK.
        if bank == last and prg_banks > 1:
            raise ValueError(
                f"bank {bank} is the fixed last bank (mapped at $C000-$FFFF); "
                f"CPU 0x{cpu_addr:04X} is in the swappable range $8000-$BFFF"
            )
        offset_in_bank = cpu_addr - 0x8000
    return INES_HEADER_SIZE + bank * PRG_BANK_BYTES + offset_in_bank


def file_to_cpu_addr(file_offset: int, prg_banks: int) -> Tuple[int, int]:
    """Translate a file offset to (cpu_addr, bank).

    Inverse of [cpu_to_file_offset]. The CPU mapping is asymmetric:
    banks 0..N-2 are swappable at $8000-$BFFF, the LAST bank (index N-1)
    is fixed at $C000-$FFFF and holds the reset/IRQ/NMI vectors. This
    convention holds for every standard NES mapper (NROM, MMC3, VRC, etc.).

    Args:
        file_offset: Byte offset inside the ROM file (>= 16, NOT inside header).
        prg_banks: Total PRG bank count from the iNES header.

    Returns:
        (cpu_addr, bank) where cpu_addr is the address a CPU would use to
        read the same byte.
    """
    if prg_banks <= 0:
        raise ValueError("prg_banks must be positive")
    if file_offset < INES_HEADER_SIZE:
        raise ValueError(
            f"file offset 0x{file_offset:X} is inside the iNES header"
        )
    prg_offset = file_offset - INES_HEADER_SIZE
    bank, offset_in_bank = divmod(prg_offset, PRG_BANK_BYTES)
    if bank >= prg_banks:
        raise ValueError(
            f"file offset 0x{file_offset:X} is past the last PRG bank "
            f"(prg_banks={prg_banks}, total PRG bytes={prg_banks * PRG_BANK_BYTES})"
        )
    cpu_base = 0xC000 if bank == prg_banks - 1 else 0x8000
    return cpu_base + offset_in_bank, bank


def resolve_bank(token: str, prg_banks: int) -> int:
    """Parse a --bank argument. Accepts an integer or the literal 'last'/'fixed'."""
    t = token.strip().lower()
    if t in ("last", "fixed", "-1"):
        return prg_banks - 1
    n = int(t, 0)
    if not 0 <= n < prg_banks:
        raise argparse.ArgumentTypeError(
            f"bank {n} out of range 0..{prg_banks - 1}"
        )
    return n


def hexdump(data: bytes, base: int, width: int = 16) -> str:
    """Format a hexdump with ASCII gutter, addresses relative to `base`."""
    if not data:
        return ""
    lines = []
    for i in range(0, len(data), width):
        chunk = data[i:i + width]
        hex_part = " ".join(f"{b:02X}" for b in chunk)
        ascii_part = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
        lines.append(f"{base + i:06X}: {hex_part:<{width * 3}}  {ascii_part}")
    return "\n".join(lines)


def cpu_addr_resolve(path: str, cpu_addr: int, bank: int, length: int = 32) -> AddrResult:
    """Resolve a CPU address in a ROM and return file offset + hexdump."""
    h = decode_header(path, full_crc=False)
    if h.prg_banks == 0:
        raise BadRomFile("ROM declares zero PRG banks; cannot resolve CPU address")
    bank = max(0, min(bank, h.prg_banks - 1))
    off = cpu_to_file_offset(h.prg_banks, cpu_addr, bank)
    with open(path, "rb") as f:
        f.seek(off)
        data = f.read(length)
    if not data:
        raise BadRomFile(f"file offset 0x{off:X} past EOF ({h.file_size} bytes)")
    return AddrResult(cpu_addr=cpu_addr, bank=bank, file_offset=off, hexdump=hexdump(data, off))


# =============================================================================
# Library scan
# =============================================================================

@dataclass
class ScanRow:
    path: str
    filename: str
    mapper: int
    submapper: int
    prg_kb: int
    chr_kb: int
    region: str
    battery: bool
    mislabel: bool

    def as_dict(self) -> dict:
        return {
            "file": self.filename,
            "mapper": self.mapper,
            "submapper": self.submapper,
            "prg_kb": self.prg_kb,
            "chr_kb": self.chr_kb,
            "region": self.region,
            "battery": self.battery,
            "mislabel_warning": self.mislabel,
        }


def iter_roms(root: Path) -> Iterable[Path]:
    """Yield every *.nes and *.7z under `root` (recursive).

    On Windows, `rglob("*.nes")` and `rglob("*.NES")` can each yield the
    same file (case-insensitive filesystem). We dedupe by case-folded
    absolute path so the scan subcommand never double-counts a ROM.
    """
    seen: set = set()
    for ext in ("*.nes", "*.NES", "*.7z"):
        for p in root.rglob(ext):
            key = str(p.resolve()).lower() if hasattr(p, "resolve") else str(p).lower()
            if key in seen:
                continue
            seen.add(key)
            yield p


def scan_library(root: Path, mapper: Optional[int] = None) -> List[ScanRow]:
    """Walk `root`, decode headers, return rows filtered by mapper if given.

    Header-only: reads 16 bytes per ROM. For a 1000-ROM library this is
    dominated by filesystem traversal; the per-ROM decode is microseconds.
    """
    rows: List[ScanRow] = []
    for p in iter_roms(root):
        try:
            h = decode_header(str(p), full_crc=False)
        except (BadRomFile, OSError):
            # Skip silently: a `.nes` suffix is not a guarantee of validity.
            # Users running scan on a mixed-content directory get a clean
            # listing of what decoded, not a flood of decode errors.
            continue
        if mapper is not None and h.mapper != mapper:
            continue
        rows.append(ScanRow(
            path=str(p),
            filename=p.name,
            mapper=h.mapper,
            submapper=h.submapper,
            prg_kb=h.prg_bytes // 1024,
            chr_kb=h.chr_bytes // 1024,
            region=h.region_effective,
            battery=h.has_battery,
            mislabel=bool(h.mislabel_warnings),
        ))
    rows.sort(key=lambda r: (r.mapper, r.filename.lower()))
    return rows


# =============================================================================
# Namco 108 patch (NO-INTRO label fix-up; see memory file
# namco-108-ines-mapper-4-convention-2026-06-01.md)
# =============================================================================

def patch_namco108(src: str, dst: Optional[str] = None) -> Tuple[Path, dict]:
    """Emit a patched COPY of a Namco 108 NO-INTRO dump with mapper 206 header.

    Returns (output_path, diff_dict) where diff_dict summarises what changed.
    NEVER modifies the source file. If `dst` is None the output is written
    next to the source with the suffix `.mapper206.nes`.

    The patch recipe (issue #152, byte-accurate to Mesen2's expected header):
        byte6 = (b6 & 0x0F) | 0xE0
        byte7 = (b7 & 0x0F) | 0xC0
    The 0xC0 also clears any "title" / format / mapper-high bits in byte 7 that
    NO-INTRO may have set. We treat every other byte as untouched.
    """
    p = Path(src)
    if not p.is_file():
        raise BadRomFile(f"Not a file: {src}")
    with open(p, "rb") as f:
        data = bytearray(f.read())
    if len(data) < INES_HEADER_SIZE or data[0:4] != INES_MAGIC:
        raise BadRomFile(f"{src} is not a valid iNES file")
    if dst is None:
        dst = str(p.with_name(p.stem + ".mapper206" + p.suffix))
    out = Path(dst)

    old_b6, old_b7 = data[6], data[7]
    new_b6 = (old_b6 & 0x0F) | 0xE0
    new_b7 = (old_b7 & 0x0F) | 0xC0
    data[6] = new_b6
    data[7] = new_b7

    with open(out, "wb") as f:
        f.write(data)

    # Decode both before/after for the diff summary. Snapshot the original
    # bytes BEFORE mutation so `before` reflects the source header, not the
    # patched one. decode_bytes is the in-memory entry point — no need to
    # re-read the patched buffer from disk just to get its header.
    original = bytearray(data)
    original[6] = old_b6
    original[7] = old_b7
    before = decode_bytes(bytes(original), source=str(p), full_crc=True)
    after = decode_bytes(bytes(data), source=str(out), full_crc=True)
    diff = {
        "source": str(p),
        "output": str(out),
        "byte6_before": f"0x{old_b6:02X}",
        "byte6_after": f"0x{new_b6:02X}",
        "byte7_before": f"0x{old_b7:02X}",
        "byte7_after": f"0x{new_b7:02X}",
        "mapper_before": before.mapper,
        "mapper_after": after.mapper,
        "crc32_before": f"0x{before.crc32:08X}",
        "crc32_after": f"0x{after.crc32:08X}",
    }
    return out, diff


# =============================================================================
# CLI (argparse subcommands)
# =============================================================================

def _bank_arg(s: str) -> str:
    """argparse type: accept 'last' / 'fixed' / integer."""
    t = s.strip().lower()
    if t in ("last", "fixed"):
        return t
    try:
        int(t, 0)
    except ValueError:
        raise argparse.ArgumentTypeError(f"bank must be integer or 'last', got {s!r}")
    return s


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="rom_info.py",
        description="NES ROM header decoder + library utilities.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "Examples:\n"
            "  rom_info.py info game.nes\n"
            "  rom_info.py scan S:\\\\Media\\\\Nintendo\\\\NES\\\\Games --mapper 33\n"
            "  rom_info.py patch-namco108 game.nes --out game.mapper206.nes\n"
            "  rom_info.py vectors game.nes\n"
            "  rom_info.py addr game.nes --cpu-addr 0xEA40 --bank last\n"
        ),
    )
    sub = p.add_subparsers(dest="cmd", required=True)

    # info
    pi = sub.add_parser("info", help="Decode a single ROM header.")
    pi.add_argument("rom", help="Path to a .nes file.")

    # scan
    ps = sub.add_parser("scan", help="Walk a ROM library, list by mapper.")
    ps.add_argument("dir", nargs="?", default=os.environ.get("ROMS_PATH", DEFAULT_LIBRARY),
                    help=f"Library root (default: {DEFAULT_LIBRARY}, override via ROMS_PATH env var).")
    ps.add_argument("--mapper", type=lambda s: int(s, 0), default=None,
                    help="Filter to a single mapper number (e.g. 33, 206).")
    ps.add_argument("--library", default=None,
                    help="Override the library path (same as positional `dir`).")

    # patch-namco108
    pp = sub.add_parser("patch-namco108",
                        help="Emit a sibling COPY with the NO-INTRO Namco 108 header patch (mapper 4 -> 206).")
    pp.add_argument("rom", help="Source .nes file (will NOT be modified).")
    pp.add_argument("--out", default=None, help="Output path (default: <stem>.mapper206.nes).")

    # vectors
    pv = sub.add_parser("vectors", help="Read NMI/RESET/IRQ vectors from the fixed last bank.")
    pv.add_argument("rom", help="Path to a .nes file.")

    # addr
    pa = sub.add_parser("addr", help="Resolve a CPU address to a file offset (and hexdump).")
    pa.add_argument("rom", help="Path to a .nes file.")
    pa.add_argument("--cpu-addr", required=True,
                    help="CPU address (e.g. 0xEA40 or 0xEA40).")
    pa.add_argument("--bank", default="last", type=_bank_arg,
                    help="PRG bank index, or 'last' (default).")
    pa.add_argument("--length", type=lambda s: int(s, 0), default=32,
                    help="Number of bytes to hexdump (default 32).")
    pa.add_argument("--file-offset", default=None,
                    help="Reverse direction: file offset -> CPU addr + bank.")

    return p


def _print_info(h: RomHeader) -> None:
    d = h.as_dict()
    print(f"File:        {d['file']}")
    print(f"Size:        {d['size_bytes']} bytes")
    print(f"CRC32:       {d['crc32']}")
    print(f"Format:      {d['format']}")
    print(f"Mapper:      {d['mapper']}" + (f" (submapper {d['submapper']})" if h.is_nes20 and d['submapper'] else ""))
    print(f"PRG:         {d['prg_banks']} x 16KB = {d['prg_bytes']} bytes")
    print(f"CHR:         {d['chr_banks']} x 8KB = {d['chr_bytes']} bytes ({d['chr_rom_or_ram']})")
    print(f"Mirroring:   {d['mirroring']}")
    print(f"Battery:     {d['battery']}")
    print(f"Region:      {d['region']}  (header={d['region_header']}, name={d['region_name']})")
    if d["warnings"]:
        print()
        print("Warnings:")
        for w in d["warnings"]:
            print(f"  - {w}")


def _print_scan(rows: List[ScanRow], mapper: Optional[int]) -> None:
    if not rows:
        print(f"No ROMs found{' for mapper ' + str(mapper) if mapper is not None else ''}.")
        return
    header = f"{'mapper':>6}  {'sub':>3}  {'prg_kb':>6}  {'chr_kb':>6}  {'reg':<5}  {'bat':<3}  {'warn':<4}  filename"
    print(header)
    print("-" * len(header))
    for r in rows:
        sub = f"{r.submapper}" if r.submapper else "-"
        warn = "!!" if r.mislabel else ""
        bat = "yes" if r.battery else "-"
        print(f"{r.mapper:>6}  {sub:>3}  {r.prg_kb:>6}  {r.chr_kb:>6}  {r.region:<5}  {bat:<3}  {warn:<4}  {r.filename}")


def _print_vectors(v: RomVectors) -> None:
    print(f"NMI:   0x{v.nmi:04X}")
    print(f"RESET: 0x{v.reset:04X}")
    print(f"IRQ:   0x{v.irq:04X}")


def _print_addr_result(rom: str, cpu_addr: int, bank: int, length: int) -> None:
    r = cpu_addr_resolve(rom, cpu_addr, bank, length=length)
    print(f"ROM:         {rom}")
    print(f"CPU addr:    0x{r.cpu_addr:04X}")
    print(f"PRG bank:    {r.bank}")
    print(f"File offset: 0x{r.file_offset:06X}")
    print()
    print(r.hexdump)


def _print_addr_reverse(rom: str, file_offset: int) -> None:
    h = decode_header(rom, full_crc=False)
    cpu, bank = file_to_cpu_addr(file_offset, h.prg_banks)
    print(f"ROM:         {rom}")
    print(f"File offset: 0x{file_offset:06X}")
    print(f"CPU addr:    0x{cpu:04X}")
    print(f"PRG bank:    {bank}")


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.cmd == "info":
            _print_info(decode_header(args.rom, full_crc=True))
        elif args.cmd == "scan":
            root = Path(args.library or args.dir)
            if not root.is_dir():
                print(f"error: not a directory: {root}", file=sys.stderr)
                return 2
            _print_scan(scan_library(root, mapper=args.mapper), args.mapper)
        elif args.cmd == "patch-namco108":
            out, diff = patch_namco108(args.rom, args.out)
            for k, v in diff.items():
                print(f"{k}: {v}")
        elif args.cmd == "vectors":
            _print_vectors(read_vectors(args.rom))
        elif args.cmd == "addr":
            if args.file_offset is not None:
                _print_addr_reverse(args.rom, int(args.file_offset, 0))
            else:
                h = decode_header(args.rom, full_crc=False)
                bank = resolve_bank(args.bank, h.prg_banks)
                _print_addr_result(args.rom, int(args.cpu_addr, 0), bank, args.length)
    except BadRomFile as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    except (OSError, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
