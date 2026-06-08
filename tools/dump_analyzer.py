#!/usr/bin/env python3
"""
NES CPU Memory Dump Analyzer

Parses 64KB CPU memory dumps from Nestlin (NES emulator) and provides
structured access to all NES memory regions per the NESdev CPU memory map.

Usage:
    python dump_analyzer.py [dump_file1.dmp] [dump_file2.dmp] ...

Each dump file is 64KB (65536 bytes) representing the full CPU address space.

For interactive exploration in future sessions, load the parsed data:
    from dump_analyzer import parse_dump, DumpAnalysis
    analysis = parse_dump("kirbyframe0-cpumem.dmp")
    print(analysis.registers)    # CPU register state
    print(analysis.io_registers)  # $2000-$401F I/O region
    print(analysis.ppu_regs)      # PPU registers ($2000-$2007)
    print(analysis.apu_regs)      # APU registers ($4000-$401F)
    print(analysis.prg_rom)       # PRG ROM contents
"""

import struct
import sys
from dataclasses import dataclass, field
from typing import List, Dict, Optional
from pathlib import Path

# =============================================================================
# NES Memory Map Constants (per nesdev.org/wiki/CPU_memory_map)
# =============================================================================

# $0000-$07FF: 2KB internal RAM (mirrored to $1FFF)
RAM_START = 0x0000
RAM_SIZE = 0x0800
ZERO_PAGE_START = 0x0000      # $0000-$00FF: Zero page (special 6502 addressing)
STACK_START = 0x0100           # $0100-$01FF: 6502 stack (8-bit SP + $0100 page)
WORK_RAM_START = 0x0200       # $0200-$07FF: General work RAM

# $2000-$2007: PPU registers (mirrored to $3FFF)
PPU_REG_START = 0x2000
PPU_REG_END = 0x2007
PPU_REG_SIZE = 8

# $4000-$401F: APU and I/O registers
APU_IO_START = 0x4000
APU_IO_END = 0x401F

# APU registers
APU_PULSE1_START = 0x4000     # $4000-$4003: Pulse 1
APU_PULSE2_START = 0x4004     # $4004-$4007: Pulse 2
APU_TRIANGLE_START = 0x4008   # $4008-$400B: Triangle
APU_NOISE_START = 0x400C      # $400C-$400F: Noise
APU_DMC_START = 0x4010        # $4010-$4013: Delta Modulation
APU_STATUS = 0x4015           # $4015: APU Status
APU_FRAMECTRL = 0x4017        # $4017: Frame Counter

# I/O registers
APU_SPRDMA = 0x4014           # $4014: Sprite DMA
JOYPAD1 = 0x4016              # $4016: Joypad 1
JOYPAD2 = 0x4017              # $4017: Joypad 2

# $4020-$FFFF: Cartridge space
CART_SPACE_START = 0x4020
SRAM_START = 0x6000           # $6000-$7FFF: SRAM (battery backed)
PRG_ROM_START = 0x8000       # $8000-$BFFF: PRG ROM lower bank
PRG_ROM_UPPER_START = 0xC000 # $C000-$FFFF: PRG ROM upper bank

# Vector addresses
VECTOR_NMI = 0xFFFA
VECTOR_RESET = 0xFFFC
VECTOR_IRQ = 0xFFFE

# PPU Register names
PPU_REG_NAMES = {
    0x2000: "PPUCTRL",
    0x2001: "PPUMASK",
    0x2002: "PPUSTATUS",
    0x2003: "OAMADDR",
    0x2004: "OAMDATA",
    0x2005: "PPUSCROLL",
    0x2006: "PPUADDR",
    0x2007: "PPUDATA",
}

# APU Register names (with channel info)
APU_REG_NAMES = {
    0x4000: "PULSE1_VOLUME",     # Channel 1 volume/envelope
    0x4001: "PULSE1_SWEEP",      # Sweep unit
    0x4002: "PULSE1_TIMER_LOW",  # Timer low 8 bits
    0x4003: "PULSE1_TIMER_HIGH", # Timer high + length counter
    0x4004: "PULSE2_VOLUME",
    0x4005: "PULSE2_SWEEP",
    0x4006: "PULSE2_TIMER_LOW",
    0x4007: "PULSE2_TIMER_HIGH",
    0x4008: "TRIANGLE_LINEAR",   # Triangle channel linear counter
    0x4009: "TRIANGLE_UNUSED",
    0x400A: "TRIANGLE_TIMER_LOW",
    0x400B: "TRIANGLE_TIMER_HIGH",
    0x400C: "NOISE_VOLUME",
    0x400D: "NOISE_UNUSED",
    0x400E: "NOISE_TIMER",       # Mode/period for noise
    0x400F: "NOISE_LENGTH",
    0x4010: "DMC_FREQ",          # Playback frequency/rate
    0x4011: "DMC_RAW_DATA",      # Delta counter load
    0x4012: "DMC_SAMPLE_ADDR",   # Sample address ($XX00 where XX is value)
    0x4013: "DMC_SAMPLE_LENGTH", # Sample length
    0x4014: "SPRDMA",             # Sprite DMA register (write triggers DMA)
    0x4015: "APUSTATUS",         # Channel enable/length counter status
    0x4016: "JOYPAD1",           # Joypad 1 (also used for strobe)
    0x4017: "JOYPAD2/FRAMECTRL", # Joypad 2 / Frame counter
}


# =============================================================================
# Data Classes for Structured Analysis
# =============================================================================

@dataclass
class CpuState:
    """CPU register state extracted from memory dump."""
    # Note: CPU registers (A, X, Y, SP, PC) are stored in RAM at specific locations
    # by convention in Nestlin's debug/test infrastructure:
    # - A: typically at $0000 (or zero page location)
    # - X, Y: processor registers
    # - SP: stack pointer
    # For actual register state, check the dump's internal RAM conventions

    pc: int = 0  # Program Counter
    sp: int = 0  # Stack Pointer
    a: int = 0   # Accumulator
    x: int = 0   # X Register
    y: int = 0   # Y Register
    p: int = 0   # Processor Status (flags)

    # Flag breakdown from P register
    flag_n: bool = False  # Negative
    flag_v: bool = False  # Overflow
    flag_b: bool = False  # Break
    flag_d: bool = False  # Decimal (ignored by NES CPU)
    flag_i: bool = False  # Interrupt Disable
    flag_z: bool = False  # Zero
    flag_c: bool = False  # Carry

    def __post_init__(self):
        """Decode flags from processor status byte."""
        self.flag_n = bool(self.p & 0x80)
        self.flag_v = bool(self.p & 0x40)
        self.flag_b = bool(self.p & 0x10)
        self.flag_d = bool(self.p & 0x08)
        self.flag_i = bool(self.p & 0x04)
        self.flag_z = bool(self.p & 0x02)
        self.flag_c = bool(self.p & 0x01)

    def as_dict(self) -> Dict:
        """Return as dictionary for easy inspection."""
        return {
            "PC": f"${self.pc:04X}",
            "SP": f"${self.sp:02X}",
            "A": f"${self.a:02X}",
            "X": f"${self.x:02X}",
            "Y": f"${self.y:02X}",
            "P": f"${self.p:02X}",
            "flags": {
                "N": self.flag_n, "V": self.flag_v, "B": self.flag_b,
                "D": self.flag_d, "I": self.flag_i, "Z": self.flag_z, "C": self.flag_c
            }
        }

    def summary(self) -> str:
        """One-line summary."""
        return f"PC=${self.pc:04X} SP=${self.sp:02X} A=${self.a:02X} X=${self.x:02X} Y=${self.y:02X} P=${self.p:02X}"


@dataclass
class PpuRegisters:
    """PPU I/O registers ($2000-$2007 mirrored to $3FFF)."""
    ppuctrl: int = 0    # $2000: PPU Control Register
    ppumask: int = 0    # $2001: PPU Mask Register
    ppustatus: int = 0  # $2002: PPU Status (read-only, write resets latch)
    oamaddr: int = 0    # $2003: OAM Address
    oamdata: int = 0    # $2004: OAM Data (read/write)
    ppuscroll_x: int = 0 # $2005: PPU Scroll (written twice for X,Y)
    ppuscroll_y: int = 0
    ppuaddr_low: int = 0 # $2006: PPU Address (written twice)
    ppuaddr_high: int = 0
    ppudata: int = 0     # $2007: PPU Data (read/write)

    def __post_init__(self):
        # Decode useful bits from PPUCTRL ($2000)
        self.nmi_enabled = bool(self.ppuctrl & 0x80)
        self.sprite_size = bool(self.ppuctrl & 0x20)
        self.background_pattern = (self.ppuctrl & 0x10) >> 4
        self.sprite_pattern = (self.ppuctrl & 0x08) >> 3
        self.increment_mode = bool(self.ppuctrl & 0x04)

        # Decode PPUMASK ($2001)
        self.show_background = bool(self.ppumask & 0x08)
        self.show_sprites = bool(self.ppumask & 0x10)

        # Decode PPUSTATUS ($2002)
        self.vblank_flag = bool(self.ppustatus & 0x80)
        self.sprite0_hit = bool(self.ppustatus & 0x40)
        self.sprite_overflow = bool(self.ppustatus & 0x20)

    def as_dict(self) -> Dict:
        return {
            "PPUCTRL": f"${self.ppuctrl:02X}",
            "PPUMASK": f"${self.ppumask:02X}",
            "PPUSTATUS": f"${self.ppustatus:02X} (VBlank={self.vblank_flag}, Sprite0={self.sprite0_hit})",
            "OAMADDR": f"${self.oamaddr:02X}",
            "OAMDATA": f"${self.oamdata:02X}",
            "PPUSCROLL": f"X=${self.ppuscroll_x:02X} Y=${self.ppuscroll_y:02X}",
            "PPUADDR": f"${self.ppuaddr_high:02X}{self.ppuaddr_low:02X}",
            "PPUDATA": f"${self.ppudata:02X}",
        }

    def summary(self) -> str:
        return (f"PPU: CTRL=${self.ppuctrl:02X} MASK=${self.ppumask:02X} "
                f"STATUS=${self.ppustatus:02X} VBlank={self.vblank_flag}")


@dataclass
class ApuRegisters:
    """APU and I/O registers ($4000-$401F)."""
    # Pulse 1 ($4000-$4003)
    pulse1_vol: int = 0
    pulse1_sweep: int = 0
    pulse1_timer_low: int = 0
    pulse1_timer_high: int = 0

    # Pulse 2 ($4004-$4007)
    pulse2_vol: int = 0
    pulse2_sweep: int = 0
    pulse2_timer_low: int = 0
    pulse2_timer_high: int = 0

    # Triangle ($4008-$400B)
    triangle_linear: int = 0
    triangle_timer_low: int = 0
    triangle_timer_high: int = 0

    # Noise ($400C-$400F)
    noise_vol: int = 0
    noise_timer: int = 0
    noise_length: int = 0

    # DMC ($4010-$4013)
    dmc_freq: int = 0
    dmc_raw: int = 0
    dmc_addr: int = 0
    dmc_length: int = 0

    # I/O
    sprdma: int = 0    # $4014: Sprite DMA address
    apustatus: int = 0 # $4015: Channel enable flags
    joypad1: int = 0   # $4016
    joypad2: int = 0   # $4017

    def channel_enabled(self, channel: int) -> bool:
        """Check if APU channel is enabled (bits in $4015)."""
        return bool(self.apustatus & (1 << channel))

    def summary(self) -> str:
        enabled = [i for i in range(5) if self.channel_enabled(i)]
        return (f"APU: STATUS=${self.apustatus:02X} "
                f"Channels enabled: {enabled} "
                f"Joy1=${self.joypad1:02X} Joy2=${self.joypad2:02X}")

    def as_dict(self) -> Dict:
        return {
            "pulse1": [self.pulse1_vol, self.pulse1_sweep, self.pulse1_timer_low, self.pulse1_timer_high],
            "pulse2": [self.pulse2_vol, self.pulse2_sweep, self.pulse2_timer_low, self.pulse2_timer_high],
            "triangle": [self.triangle_linear, self.triangle_timer_low, self.triangle_timer_high],
            "noise": [self.noise_vol, self.noise_timer, self.noise_length],
            "dmc": [self.dmc_freq, self.dmc_raw, self.dmc_addr, self.dmc_length],
            "sprdma": self.sprdma,
            "apustatus": self.apustatus,
            "joypad1": self.joypad1,
            "joypad2": self.joypad2,
        }


@dataclass
class MemRegion:
    """A contiguous memory region with metadata."""
    name: str
    start: int
    end: int
    data: bytes
    description: str = ""

    @property
    def size(self) -> int:
        return len(self.data)

    def __repr__(self):
        return f"MemRegion({self.name}, ${self.start:04X}-${self.end:04X}, {self.size} bytes)"


@dataclass
class DumpAnalysis:
    """Complete analysis of a NES CPU memory dump."""

    filename: str
    raw_data: bytes = field(default_factory=bytes)

    # Programmer-friendly regions
    zero_page: MemRegion = None
    stack: MemRegion = None
    work_ram: MemRegion = None
    ppu_registers: MemRegion = None
    apu_io_registers: MemRegion = None
    cartridge_ram: MemRegion = None
    prg_rom_lower: MemRegion = None
    prg_rom_upper: MemRegion = None

    # Parsed structures
    cpu: CpuState = None
    ppu: PpuRegisters = None
    apu: ApuRegisters = None

    # Memory-mapped vectors
    nmi_vector: int = 0
    reset_vector: int = 0
    irq_vector: int = 0

    @classmethod
    def from_bytes(cls, filename: str, data: bytes) -> 'DumpAnalysis':
        """Parse raw dump bytes into structured analysis."""
        if len(data) != 65536:
            raise ValueError(f"Expected 65536 bytes, got {len(data)}")

        analysis = cls(filename=filename, raw_data=data)

        # Parse memory regions
        analysis.zero_page = MemRegion(
            name="Zero Page",
            start=0x0000, end=0x00FF,
            data=data[0x0000:0x0100],
            description="$0000-$00FF: Zero page (direct addressing)"
        )
        analysis.stack = MemRegion(
            name="Stack",
            start=0x0100, end=0x01FF,
            data=data[0x0100:0x0200],
            description="$0100-$01FF: 6502 stack pointer + $0100 page"
        )
        analysis.work_ram = MemRegion(
            name="Work RAM",
            start=0x0200, end=0x07FF,
            data=data[0x0200:0x0800],
            description="$0200-$07FF: General work RAM"
        )

        # PPU registers (mirrored at $2008-$3FFF, but we only need $2000-$2007)
        ppu_data = bytes(data[0x2000:0x2008])
        analysis.ppu_registers = MemRegion(
            name="PPU Registers",
            start=0x2000, end=0x2007,
            data=ppu_data,
            description="$2000-$2007: PPU control/status registers (mirrored to $3FFF)"
        )

        # APU and I/O registers ($4000-$401F)
        apu_data = bytes(data[0x4000:0x4020])
        analysis.apu_io_registers = MemRegion(
            name="APU/I/O Registers",
            start=0x4000, end=0x401F,
            data=apu_data,
            description="$4000-$401F: APU and I/O registers"
        )

        # Cartridge space
        analysis.cartridge_ram = MemRegion(
            name="Cartridge SRAM",
            start=0x6000, end=0x7FFF,
            data=data[0x6000:0x8000],
            description="$6000-$7FFF: Cartridge SRAM (battery backed)"
        )
        analysis.prg_rom_lower = MemRegion(
            name="PRG ROM Lower",
            start=0x8000, end=0xBFFF,
            data=data[0x8000:0xC000],
            description="$8000-$BFFF: PRG ROM bank (lower)"
        )
        analysis.prg_rom_upper = MemRegion(
            name="PRG ROM Upper",
            start=0xC000, end=0xFFFF,
            data=data[0xC000:0xFFFF],
            description="$C000-$FFFF: PRG ROM bank (upper, contains vectors)"
        )

        # Parse vectors
        analysis.nmi_vector = struct.unpack_from('<H', data, VECTOR_NMI)[0]
        analysis.reset_vector = struct.unpack_from('<H', data, VECTOR_RESET)[0]
        analysis.irq_vector = struct.unpack_from('<H', data, VECTOR_IRQ)[0]

        # Parse CPU state
        analysis.cpu = CpuState(
            pc=analysis.reset_vector,  # Reset vector = starting PC
            sp=data[0x0100] if data[0x0100] else 0xFF,  # Stack starts at $01XX
            a=data[0x0001],  # Convention: A at $0001 in Nestlin dumps
            x=data[0x0002],  # Convention: X at $0002
            y=data[0x0003],  # Convention: Y at $0003
            p=data[0x0004],  # Convention: P at $0004
        )

        # Parse PPU registers
        analysis.ppu = PpuRegisters(
            ppuctrl=data[0x2000],
            ppumask=data[0x2001],
            ppustatus=data[0x2002],
            oamaddr=data[0x2003],
            oamdata=data[0x2004],
            ppuscroll_x=data[0x2005],
            ppuscroll_y=data[0x2005 + 256] if len(data) > 0x2005 + 256 else 0,
            ppuaddr_low=data[0x2006],
            ppuaddr_high=data[0x2006 + 256] if len(data) > 0x2006 + 256 else 0,
            ppudata=data[0x2007],
        )

        # Parse APU registers
        analysis.apu = ApuRegisters(
            pulse1_vol=data[0x4000],
            pulse1_sweep=data[0x4001],
            pulse1_timer_low=data[0x4002],
            pulse1_timer_high=data[0x4003],
            pulse2_vol=data[0x4004],
            pulse2_sweep=data[0x4005],
            pulse2_timer_low=data[0x4006],
            pulse2_timer_high=data[0x4007],
            triangle_linear=data[0x4008],
            triangle_timer_low=data[0x400A],
            triangle_timer_high=data[0x400B],
            noise_vol=data[0x400C],
            noise_timer=data[0x400E],
            noise_length=data[0x400F],
            dmc_freq=data[0x4010],
            dmc_raw=data[0x4011],
            dmc_addr=data[0x4012],
            dmc_length=data[0x4013],
            sprdma=data[0x4014],
            apustatus=data[0x4015],
            joypad1=data[0x4016],
            joypad2=data[0x4017],
        )

        return analysis

    def dump_summary(self) -> str:
        """Return a comprehensive summary string."""
        lines = [
            f"=== NES Memory Dump Analysis: {self.filename} ===",
            f"",
            "--- CPU State ---",
            self.cpu.summary(),
            f"  NMI vector: ${self.nmi_vector:04X}",
            f"  Reset vector: ${self.reset_vector:04X}",
            f"  IRQ vector: ${self.irq_vector:04X}",
            "",
            "--- PPU Registers ---",
            self.ppu.summary(),
            "",
            "--- APU/I/O Registers ---",
            self.apu.summary(),
            "",
            "--- Memory Regions ---",
            f"  Zero Page: ${self.zero_page.start:04X}-${self.zero_page.end:04X} ({self.zero_page.size} bytes)",
            f"  Stack: ${self.stack.start:04X}-${self.stack.end:04X} ({self.stack.size} bytes)",
            f"  Work RAM: ${self.work_ram.start:04X}-${self.work_ram.end:04X} ({self.work_ram.size} bytes)",
            f"  PPU Regs: ${self.ppu_registers.start:04X}-${self.ppu_registers.end:04X}",
            f"  APU/I/O: ${self.apu_io_registers.start:04X}-${self.apu_io_registers.end:04X}",
            f"  SRAM: ${self.cartridge_ram.start:04X}-${self.cartridge_ram.end:04X}",
            f"  PRG ROM: ${self.prg_rom_lower.start:04X}-${self.prg_rom_upper.end:04X}",
        ]
        return "\n".join(lines)

    def hexdump_region(self, start: int, end: int, bytes_per_line: int = 16) -> str:
        """Return hexdump-style output for a memory region."""
        data = self.raw_data[start:end]
        lines = []
        for i in range(0, len(data), bytes_per_line):
            chunk = data[i:i+bytes_per_line]
            hex_str = ' '.join(f'{b:02X}' for b in chunk)
            ascii_str = ''.join(chr(b) if 32 <= b < 127 else '.' for b in chunk)
            lines.append(f"${i+start:04X}: {hex_str:<{bytes_per_line*3}}  {ascii_str}")
        return "\n".join(lines)


# =============================================================================
# Main Functions
# =============================================================================

def parse_dump(filepath: str) -> DumpAnalysis:
    """
    Parse a NES CPU memory dump file.

    Args:
        filepath: Path to .dmp file (64KB)

    Returns:
        DumpAnalysis object with structured memory regions
    """
    with open(filepath, 'rb') as f:
        data = f.read()
    return DumpAnalysis.from_bytes(Path(filepath).name, data)


def analyze_multiple_dumps(filepaths: List[str]) -> Dict[str, DumpAnalysis]:
    """
    Parse multiple dump files and return a dictionary of analyses.

    Args:
        filepaths: List of paths to .dmp files

    Returns:
        Dict mapping filename -> DumpAnalysis
    """
    results = {}
    for fp in filepaths:
        try:
            results[fp] = parse_dump(fp)
            print(f"Parsed: {fp}")
        except Exception as e:
            print(f"Error parsing {fp}: {e}")
    return results


def compare_dumps(analysis1: DumpAnalysis, analysis2: DumpAnalysis) -> List[Dict]:
    """
    Compare two dump analyses and return list of differences.

    Returns:
        List of dicts with 'address', 'value1', 'value2' for each differing byte
    """
    differences = []
    for i in range(65536):
        if analysis1.raw_data[i] != analysis2.raw_data[i]:
            differences.append({
                'address': i,
                'frame1_value': analysis1.raw_data[i],
                'frame2_value': analysis2.raw_data[i],
                'region': _address_to_region(i)
            })
    return differences


def _address_to_region(addr: int) -> str:
    """Map address to region name."""
    if addr < 0x0100:
        return "Zero Page"
    elif addr < 0x0200:
        return "Stack"
    elif addr < 0x0800:
        return "Work RAM"
    elif addr < 0x2000:
        return "Extended RAM"
    elif addr < 0x4000:
        return "PPU Registers (mirrored)"
    elif addr < 0x4020:
        return "APU/I/O"
    elif addr < 0x6000:
        return "Cartridge Space"
    elif addr < 0x8000:
        return "SRAM"
    else:
        return "PRG ROM"


def main():
    """CLI interface for dump analysis."""
    if len(sys.argv) < 2:
        print(__doc__)
        print("\n--- Quick Demo (parsing all kirbyframe*.dmp files) ---")

        # Find all kirby frame dumps
        import glob
        dumps = sorted(glob.glob("kirbyframe*-cpumem.dmp"))
        if dumps:
            print(f"Found {len(dumps)} dump files\n")
            analyses = analyze_multiple_dumps(dumps)

            # Print summary for each
            for name, analysis in analyses.items():
                print(f"\n{analysis.dump_summary()}")

                # Show interesting PRG ROM at current PC area
                pc = analysis.cpu.pc
                if 0x8000 <= pc <= 0xFFFF:
                    offset = pc - 0x8000
                    region_data = analysis.prg_rom_lower.data if pc < 0xC000 else analysis.prg_rom_upper.data
                    # Show instruction around PC (simple raw bytes, not decoded)
                    context = region_data[offset:offset+8]
                    print(f"  Code at PC ({pc:04X}): {' '.join(f'{b:02X}' for b in context)}")
        else:
            print("No dump files found in current directory.")
            print("Usage: python dump_analyzer.py <dump1.dmp> [dump2.dmp] ...")

    else:
        # Analyze specified files
        analyses = analyze_multiple_dumps(sys.argv[1:])
        for name, analysis in analyses.items():
            print(f"\n{analysis.dump_summary()}")

            # Detailed hexdump of interesting regions
            print("\n--- Zero Page (first 32 bytes) ---")
            print(analysis.hexdump_region(0x0000, 0x0020))

            print("\n--- PPU Registers ---")
            print(analysis.hexdump_region(0x2000, 0x2008))

            print("\n--- APU/I/O Registers ---")
            print(analysis.hexdump_region(0x4000, 0x4020))


if __name__ == "__main__":
    main()