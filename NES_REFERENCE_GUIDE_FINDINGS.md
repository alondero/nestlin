# Comprehensive NES Reference Guide Findings

A comprehensive exploration of https://www.nesdev.org/wiki/NES_reference_guide documenting all available reference materials valuable for NES emulator development.

## 1. CPU AND INSTRUCTION EXECUTION

### 1.1 CPU Architecture Fundamentals
- **Processor Core**: Ricoh-manufactured 6502 variant (2A03 on NTSC, 2A07 on PAL)
- **Missing Decimal Mode**: Unlike original MOS6502, the 2A03/2A07 lacks functional decimal (BCD) mode
- **Clock Frequencies**:
  - NTSC: ~1.79 MHz (559 ns per cycle)
  - PAL: ~1.66 MHz (601 ns per cycle)
  - Dendy: ~1.77 MHz (564 ns per cycle)
- **Master Clock Dividers**: NTSC uses 21.48 MHz ÷ 12, PAL uses 26.60 MHz ÷ 16
- **Critical Fact**: Every cycle performs either a read or write operation (no idle cycles)

### 1.2 CPU Registers and Status Flags
**Register Set**: Accumulator (A), X, Y, Stack Pointer (SP), Program Counter (PC), Processor Status (P)

**Status Flags (NV-BDIZC)**:
- Bit 7 (N - Negative): Set when instruction result bit 7 = 1
- Bit 6 (V - Overflow): Set by ADC/SBC on signed overflow; BIT instruction loads memory bit 6 directly
- Bit 5: No CPU effect (always available)
- Bit 4 (B - Break): Exists only in stack-pushed status, not as CPU register
  - 0 for NMI/IRQ, 1 for BRK/PHP (distinguishes interrupt sources)
- Bit 3 (D - Decimal): Observable but non-functional on NES
- Bit 2 (I - Interrupt Disable): When set, IRQ inhibited; NMI, BRK, reset unaffected
- Bit 1 (Z - Zero): Set when instruction result = 0
- Bit 0 (C - Carry): ADC sets on addition carry; SBC/CMP indicate "no borrow"; shift instructions place shifted-out bit here

**Flag Behaviors**:
- Carry: Set by ADC, shift instructions (ASL, LSR, ROL, ROR); CMP/SBC show "no borrow"
- Zero: Set by most instructions that produce zero results
- Interrupt Disable: Automatically set during interrupts; affecting instruction timing
- Overflow: ADC/SBC set on signed overflow; BIT loads memory bit 6
- Negative: Contains bit 7 of results; BIT loads memory bit 7

### 1.3 Addressing Modes (13 total)
All modes with cycle counts and edge cases:

**Indexed Addressing** (6 modes):
1. Zero Page Indexed (d,x): `val = PEEK((arg + X) % 256)` - 4 cycles, wraps within zero page
2. Zero Page Indexed (d,y): Same formula with Y, 4 cycles, preferred for zero page address tables
3. Absolute Indexed (a,x): `val = PEEK(arg + X)` - 4+ cycles (5 if page crossing on reads)
4. Absolute Indexed (a,y): Same with Y - 4+ cycles
5. Indexed Indirect (d,x): `val = PEEK(PEEK((arg + X) % 256) + PEEK((arg + X + 1) % 256) * 256)` - 6 cycles
6. Indirect Indexed (d),y: `val = PEEK(PEEK(arg) + PEEK((arg + 1) % 256) * 256 + Y)` - 5+ cycles

**Other Modes** (7 modes):
- Implicit: No operand (RTS, CLC) - 2-6 cycles
- Accumulator: Operates on A register - 2 cycles
- Immediate: 8-bit operand used directly - 2 cycles
- Zero Page: 8-bit address - 3 cycles
- Absolute: 16-bit address - 4 cycles
- Relative: 8-bit signed offset for branches - 2-4 cycles (extra if taken/page crossing)
- Indirect: JMP special mode - 5 cycles

**The "Oops" Cycle**: Page boundary crossing on indexed operations causes extra cycle:
- Read instructions crossing page boundaries: +1 cycle
- Store instructions (all): +1 cycle on page boundaries
- Critical for accurate timing

### 1.4 Unofficial Opcodes
- All illegal 6502 opcodes execute identically on 2A03/2A07 chips as on original 6502
- Result from compressed 6502 microcode architecture
- Most NMOS 6502 cores interpret them identically, though some "less stable" instructions vary
- Examples referenced in real games: SLO, LAX, XAA, DCP
- Mentioned as exploited in specific games (e.g., Shinsenden's MMC1 reset trigger)

### 1.5 CPU Interrupts - Critical Details

**Interrupt Types**:
1. **NMI (Non-Maskable Interrupt)**:
   - Edge-sensitive (high-to-low transition on NMI line)
   - Uses edge detector sampling during φ2 of each cycle
   - Cannot be disabled by interrupt disable flag
   - Fires after post-render blanking line

2. **IRQ (Interrupt Request)**:
   - Level-sensitive (responds to continuous low signal)
   - Uses level detector responding during φ2
   - Creates internal signal lasting one cycle
   - Can be disabled by interrupt disable flag

3. **BRK (Software Interrupt)**:
   - Shares IRQ vector but distinguished by B flag in pushed status
   - Software-triggered version of IRQ

**Interrupt Timing and Polling**:
- Interrupts polled at END of instruction's final cycle
- **CRITICAL**: What matters is interrupt line status at END OF SECOND-TO-LAST CYCLE
- Not polled before final cycle itself

**Interrupt Hijacking Bug**:
- When NMI asserts during first four ticks of BRK instruction, CPU executes BRK's initial operations then branches to NMI vector
- Effectively hijacks the BRK instruction

**Delayed IRQ Response**:
- CLI, SEI, PLP change interrupt flags AFTER polling
- Can delay interrupt service until after next instruction
- RTI affects IRQ inhibition immediately (not delayed)

**Branch Instruction Polling**:
- Interrupts polled before operand fetch cycle
- NOT polled before third cycle on taken branches
- NOT polled before page-boundary fixup cycles

**Frame Timing for Interrupts**:
- NTSC: ~2273 CPU cycles (~20 blanking scanlines) for NMI handler execution
- PAL: ~7459 CPU cycles (~70 blanking scanlines)
- Dendy: ~51 blanking scanlines (PAL-compatible timing)

### 1.6 CPU Memory Addresses

**CPU Address Space Organization** ($0000-$FFFF):
- **$0000-$07FF**: 2KB internal RAM (zero page at $0000-$00FF, stack at $0100-$01FF)
- **$0800-$1FFF**: Mirrors of $0000-$07FF (three additional copies)
- **$2000-$2007**: PPU registers (8 bytes)
- **$2008-$3FFF**: Mirrors of $2000-$2007 (every 8 bytes)
- **$4000-$4017**: APU and I/O registers (24 bytes)
- **$4018-$401F**: Disabled in normal operation (CPU Test Mode only)
- **$4020-$5FFF**: Cartridge space (typically open bus or mapper registers)
- **$6000-$7FFF**: Battery-backed save/work RAM (PRG-RAM/WRAM) if present
- **$8000-$FFFF**: ROM and mapper registers

**Interrupt Vectors** (supplied by cartridge):
- **$FFFA-$FFFB**: NMI vector
- **$FFFC-$FFFD**: Reset vector
- **$FFFE-$FFFF**: IRQ/BRK vector

**DPCM Special Behavior**:
- Samples reside in $C000-$FFF1
- Wraparound from $FFFF to $8000 accommodates banking complexity
- Critical for DMC audio channel operation

**Bus Access Rules**:
- Cartridges observe all reads/writes except $4015 reads (APU status)
- Should only place readable memory in $4020-$FFFF to prevent DMA bus conflicts
- Avoid $2000-$401F range for cartridge memory placement

---

## 2. PPU AND GRAPHICS RENDERING

### 2.1 PPU Architecture Overview
- **Resolution**: 256×240 pixels (NTSC)
- **Total Scanlines**: 262 per frame (NTSC)
- **Pixels per Scanline**: 341 dots (NTSC)
- **Frame Rate**: 60.10 Hz (NTSC)
- **Tile-Based Graphics**: All graphics built from 8×8 pixel tiles
- **Color Palette**: 56 colors (16 of which are unique)
- **Sprite System**: 64 sprites maximum, 8 per scanline visible

### 2.2 PPU Memory Map ($0000-$3FFF)

**Pattern Tables** ($0000-$1FFF):
- Two 4KB regions containing tile graphics (CHR-ROM or CHR-RAM)
- First table typically background tiles, second typically sprite tiles
- Each tile: 16 bytes (two 8-byte bitplanes)

**Nametables & Attributes** ($2000-$2FFF):
- Four logical nametables in 2×2 arrangement
- Each 1024 bytes: 960 bytes tile indices + 64 bytes attributes
- Physical RAM on NES: only 2KB total VRAM
- Cartridge controls mirroring to map physical to logical addresses

**Palette Memory** ($3F00-$3F1F):
- 32 bytes, not configurable, always internal RAM
- Remaining $3F20-$3FFF mirrors palette data
- Background: 4 palettes × 4 colors = 16 entries
- Sprites: 4 palettes × 4 colors = 16 entries
- Entry 0 of each palette is transparent
- Backdrop color shared at $3F00 and $3F10

### 2.3 PPU Registers (Memory-Mapped at $2000-$2007, mirrored through $3FFF)

| Register | Address | Access | Purpose |
|----------|---------|--------|---------|
| PPUCTRL | $2000 | Write | Control rendering, NMI, sprite size, pattern table selection |
| PPUMASK | $2001 | Write | Enable/disable sprites and backgrounds, color emphasis, grayscale |
| PPUSTATUS | $2002 | Read | Vblank flag, sprite 0 hit, sprite overflow flags |
| OAMADDR | $2003 | Write | Set sprite memory address (secondary OAM) |
| OAMDATA | $2004 | R/W | Read/write sprite data |
| PPUSCROLL | $2005 | Write | Set X/Y scroll (requires two writes, uses toggle) |
| PPUADDR | $2006 | Write | Set VRAM address (requires two writes, uses toggle) |
| PPUDATA | $2007 | R/W | Read/write VRAM data (auto-increments address) |

**PPUCTRL ($2000) Details**:
- Bit 0-1: Nametable address ($2000/$2400/$2800/$2C00)
- Bit 2: VRAM address increment (0=+1, 1=+32)
- Bit 3: Sprite pattern table address ($0000 or $1000)
- Bit 4: Background pattern table address ($0000 or $1000)
- Bit 5: Sprite size (0=8×8, 1=8×16)
- Bit 6: Master/slave (always 0 on NES)
- Bit 7: NMI enable (1=generate NMI on vblank)
- **Special**: Writes ignored until first pre-render scanline after power-on

**PPUMASK ($2001) Details**:
- Bit 0: Grayscale mode (1=monochrome)
- Bit 1: Show background in leftmost 8 pixels
- Bit 2: Show sprites in leftmost 8 pixels
- Bit 3: Show background
- Bit 4: Show sprites
- Bit 5-7: Color emphasis (tint)

**PPUSTATUS ($2002) Details**:
- Bit 5: Sprite overflow (set if >8 sprites on scanline)
- Bit 6: Sprite 0 hit (set when opaque sprite 0 pixel overlaps background)
- Bit 7: Vblank flag (set during vblank, cleared on read)
- **Special**: Reading clears vblank flag and resets write toggle

**PPUSCROLL/PPUADDR Toggle Behavior**:
- Both use shared internal write toggle (w)
- PPUSCROLL: Two writes set fine X and coarse/fine Y
- PPUADDR: Two writes set 14-bit VRAM address
- Reading $2002 resets toggle to first write state

**PPUDATA ($2007) Special Behavior**:
- Implements read buffer mechanism
- Reads return previously buffered data (delayed by one read)
- Writes go directly to VRAM
- Address auto-increments by 1 or 32 based on PPUCTRL bit 2
- **Critical**: After loading VRAM data, must reload scroll position to prevent rendering errors

### 2.4 VRAM Address Register (v and t registers)

**Bit Layout** (15-bit address):
```
yyy NN YYYYY XXXXX
||| || ||||| +++++-- coarse X (0-31)
||| || +++++-------- coarse Y (0-29)
||| |+-------------- horizontal nametable select (0 or 1)
||| +--------------- vertical nametable select (0 or 1)
+++----------------- fine Y (0-7)
```

**Scrolling Components**:
- **Fine X**: 3 bits, determines pixel within tile (0-7)
- **Fine Y**: 3 bits (in v register bits 12-14), determines scanline within tile
- **Coarse X**: 5 bits, tile column (0-31)
- **Coarse Y**: 5 bits, tile row (0-29)
- **Nametable**: 2 bits, selects quadrant

**Wraparound Behavior**:
- Coarse X: 0→31 toggles horizontal nametable, wraps to 0
- Coarse Y: 29→0 toggles vertical nametable; 30-31 produce special attribute behavior
- Fine Y: 7→0 increments coarse Y

### 2.5 Nametables and Mirroring

**Nametable Structure**:
- Each nametable: 1024 bytes (960 tile indices + 64 attribute bytes)
- 30 rows × 32 tiles = 256×240 pixel playfield
- Tile indices reference pattern tables

**Physical vs Logical Layout**:
- Logical: Four nametables ($2000, $2400, $2800, $2C00) in 2×2 grid
- Physical: Only 2KB VRAM on NES (split or combined by mirroring)
- Cartridge controls address line 10 (A10) to create mirroring

**Mirroring Modes**:
1. **Horizontal**: $2000/$2800 share RAM, $2400/$2C00 share RAM (best for vertical scrolling)
   - PPU A11 → CIRAM A10

2. **Vertical**: $2000/$2400 share RAM, $2800/$2C00 share RAM (best for horizontal scrolling)
   - PPU A10 → CIRAM A10

3. **Single-Screen**: All nametables reference same 2KB (software-controlled via mapper)
   - Enables status bars while maintaining full-screen playfield

4. **Four-Screen**: Requires additional 2KB cartridge RAM
   - Each nametable has unique content
   - Full 64×60 tilemap

5. **Diagonal**: CIRAM A10 = PA11 XOR PA10 (direction changes mid-scroll)

6. **L-Shaped**: CIRAM A10 = PA11 OR PA10 (four-way scrolling at boundaries)

**Attribute Table Organization**:
- Located at $x3C0-$x3FF within each nametable
- 64 bytes (8×8 array) for 32×30 tilemap
- Each byte controls 2×2 tile block (16×16 pixels)
- Palette encoding per 2-bit section (top-left, top-right, bottom-left, bottom-right)

### 2.6 PPU Rendering Pipeline

**Frame Structure** (262 scanlines total, NTSC):

**Scanline Types**:
- **Pre-render (261)**: Fills shift registers without display output; equivalent to visible scanline
- **Visible (0-239)**: Display scanlines where pixels render
- **Post-render (240)**: One idle scanline
- **Vblank (241-260)**: 20 blanking scanlines; PPU makes no memory accesses, CPU can access VRAM freely

**Visible Scanline Phases** (341 cycles):
1. **Cycles 1-256**: Visible rendering
   - Background tile fetching and rendering
   - 33 complete tile fetches per scanline
   - One fetch every 8 cycles
   - 4 memory accesses per tile (2 cycles each)

2. **Cycles 257-320**: Sprite evaluation and fetch
   - Y position evaluation (determine visible sprites)
   - Fetch sprite tile data for next scanline
   - Reuse background fetch circuitry (garbage nametable fetches)

3. **Cycles 321-336**: Prefetch first two tiles for next scanline
   - Loads initial tile data into shift registers

4. **Cycles 337-340**: Unused nametable fetches

5. **Cycle 341**: Extra cycle on alternate frames (odd frames shorter)

**Tile Fetch Sequence** (repeats every 8 cycles):
1. **Cycle N**: Nametable byte fetch (which tile to display)
2. **Cycle N+2**: Attribute byte fetch (color palette)
3. **Cycle N+4**: Pattern table low byte (pixel data bits 0)
4. **Cycle N+6**: Pattern table high byte (pixel data bits 1)

Each fetch takes 2 PPU cycles to complete.

**Shift Register Operation**:
- Two 16-bit pattern data shift registers (background tiles)
- 1-bit latches for attribute data feed 8-bit shift registers
- On every 8th dot in fetch regions: pattern and attribute data transfer into shift registers
- On every dot: 4-bit pixel selected by fine X from low 8 bits, then shifted

**Pixel Output**:
- Priority multiplexer selects between background and sprite pixels
- Transparent pixels (both low bits = 0) show backdrop color
- Color selection: 5-bit index = background/sprite (1 bit) + palette (2 bits) + color (2 bits)

### 2.7 Sprite System and OAM

**OAM (Object Attribute Memory)**:
- 256 bytes storing 64 sprites
- Each sprite: 4 bytes
  - Byte 0: Y position ($EF-$FF = offscreen)
  - Byte 1: Tile index
  - Byte 2: Attributes (palette, priority, flip)
  - Byte 3: X position

**Sprite Attributes (Byte 2)**:
- Bits 0-1: Palette (0-3, backgrounds use palettes 0-3, sprites use 4-7 internally)
- Bit 4: Flip horizontally
- Bit 5: Flip vertically
- Bit 5: Priority (0=in front of background, 1=behind background)
- Bit 6: Flip H
- Bit 7: Flip V

**Sprite Priority Rules**:
- Lower OAM indices display in front of higher indices
- Sprite 0 (OAM index 0) has special significance
- When frontmost opaque sprite has back-priority (bit 5=1), background pixels draw in front
- Allows lower-indexed back-priority sprite to obscure higher-indexed front-priority sprite

**Sprite Evaluation**:
- During cycles 257-320 of visible scanlines
- Identifies up to 8 frontmost visible sprites for current scanline
- Hardware bug: sprite overflow detection is broken (counts wrong number of sprites)

**Sprite 0 Hit**:
- Set when opaque sprite 0 pixel overlaps non-transparent background pixel
- Used by games for precise timing (scroll updates at exact scanlines)
- **Critical**: Occurs at exact pixel position where sprites and background both opaque
- Flag set during rendering phase, readable via $2002

**OAM DMA**:
- Write to $4014 triggers DMA from CPU RAM to OAM
- Copies 256 bytes in ~513 CPU cycles
- Additional cycle if write occurs during specific CPU timing window
- Halts CPU during transfer

### 2.8 PPU Timing Details

**PPU-to-CPU Clock Ratio**:
- NTSC: Exactly 3 PPU ticks per CPU cycle (341 PPU cycles ÷ 113.33 CPU cycles per scanline)
- PAL: Exactly 3.2 PPU ticks per CPU cycle

**Scanline Duration** (NTSC):
- 341 PPU cycles = 113⅓ CPU cycles
- 262 scanlines = 29,780⅔ CPU cycles per frame
- Frame time: ~16.63 ms at 1.79 MHz

**Vblank Duration** (NTSC):
- 20 scanlines (241-260)
- ~2,273 CPU cycles available for NMI handler

**Pre-render Scanline Timing**:
- Identical to visible scanlines in terms of memory access patterns
- Fills shift registers to prepare for first visible scanline
- Special handling: PPU A12 behavior affects MMC3 IRQ counting

### 2.9 PPU Memory Access Pattern

**Pattern in fetch cycle during rendering**:
- Every memory access takes 2 PPU cycles
- Tile ID fetched from nametable
- Attribute byte fetched from attribute table
- Low bitplane fetched from pattern table
- High bitplane fetched from pattern table
- 4 fetches × 2 cycles = 8 cycles per tile

**Nametable Address Calculation**:
- Base: $2000 + (nametable << 10)
- Offset: coarse_y × 32 + coarse_x
- Final: $2000 + (nametable << 10) + (coarse_y << 5) + coarse_x

**Attribute Table Address Calculation**:
- Base: $2000 + (nametable << 10) + $3C0
- Offset: (coarse_y >> 2) × 8 + (coarse_x >> 2)
- Bit position: ((coarse_y & 2) << 1) | (coarse_x & 2)

**Pattern Table Address Calculation**:
- Base: (pattern_table << 12) [0 or $1000]
- Offset: (tile_id << 4) [16 bytes per tile]
- Bitplane: +0 for low, +8 for high
- Scanline: + fine_y
- Final: pattern_table + (tile_id << 4) + fine_y

### 2.10 Critical PPU Implementation Details

**Scrolling Common Mistakes**:
1. If NMI handler writes >64 bytes, scroll not applied that frame
2. After PPUADDR write, MUST also write PPUCTRL and PPUSCROLL (shared register)
3. Toggle state (w flag) desynchronizes if write sequence broken; reset by reading $2002
4. Coarse Y wraps at 30 (not 32)

**Hardware Bugs and Quirks**:
- Sprite 0 hit fails at X=255
- Sprite overflow detection broken (counts wrong number of sprites)
- NES-001 frontloader: PPU refuses register writes for ~1 frame after power-on
- Palette corruption: Toggling rendering mid-screen corrupts palette RAM
- OAM decay: Sprite data corrupts if rendering off too long (especially on PAL)
- Scroll glitches: Writing scroll at precise timing causes artifacts

---

## 3. APU AND AUDIO

### 3.1 APU Overview
- **Channels**: 5 total (2 pulse, 1 triangle, 1 noise, 1 DMC)
- **Integration**: Integrated into 2A03 (NTSC) / 2A07 (PAL) chip
- **Register Range**: $4000-$4017 (24 bytes)
- **Clock**: Frame counter drives envelope, sweep, and length counter updates

### 3.2 Sound Channels

**Pulse Channels 1 & 2** ($4000-$4007):
- Variable-width pulse waves
- Frequency formula: f = fCPU / (16 × (t + 1))
- Silenced if period t < 8
- Features:
  - Volume and envelope control
  - Sweep unit for pitch modulation
  - Length counter for note duration
- Registers:
  - $4000/$4004: Volume/envelope
  - $4001/$4005: Sweep control
  - $4002/$4006: Frequency low byte
  - $4003/$4007: Frequency high byte + length counter load

**Triangle Channel** ($4008-$400B):
- Quantized triangle wave (32 steps)
- No volume control; uses linear counter (7-bit) for duration
- One octave lower than pulse with equivalent timer values
- Cannot reset phase; continues outputting last value when silenced
- Registers:
  - $4008: Linear counter
  - $400A: Frequency low byte
  - $400B: Frequency high byte + length counter load

**Noise Channel** ($400C-$400F):
- Pseudo-random bit generator with 1-bit linear feedback shift register (LFSR)
- Envelope and length counter control
- 4-bit period value selects from predefined lookup table
- Bit 7 of $400E enables periodic mode for buzzing tones
- Registers:
  - $400C: Volume/envelope
  - $400E: Period and LFSR mode
  - $400F: Length counter load

**DMC Channel (Delta Modulation)** ($4010-$4013):
- 7-bit PCM signal via delta modulation
- Processes 1-bit deltas: 1 increments counter, 0 decrements
- Sample addresses: $C000-$FFFF range
- Playback rate: 4-bit frequency index selects from lookup table
- Features:
  - Loop support
  - IRQ generation capability
- Can interfere with controller reads via DMA cycles
- Registers:
  - $4010: Frequency and loop/IRQ flags
  - $4011: Direct load value
  - $4012: Sample address (page register)
  - $4013: Sample length (16-byte blocks)

### 3.3 Envelope and Length Control

**Length Counter**:
- 4-bit value determines note duration
- Clocked by frame counter at ~240 Hz
- Channel silenced when counter reaches 0
- Lookup table provides duration mapping

**Linear Counter** (Triangle only):
- 7-bit counter
- Clocked 4x per frame counter sequence
- Separate from length counter
- Enables smooth fadeout

**Envelope Generator**:
- Automatically decreases volume over time
- Start/stop controlled by flags
- Can loop for sustain effect
- Applied to all channels except triangle

### 3.4 Frame Counter ($4017)

**Operation**:
- Drives all timing for envelope, sweep, and length counter updates
- Clocked at ~240 Hz (every ~7,457 NTSC cycles)

**Mode 0 (4-Step Sequence)**:
- Step 0: Clock length counter and envelope
- Step 1: Clock length counter, envelope, and sweep
- Step 2: Clock length counter and envelope
- Step 3: Clock length counter, envelope, and sweep; then optional IRQ
- Generates IRQ unless disabled via $4017 bit 6
- Step 4 never occurs (back to step 0)

**Mode 1 (5-Step Sequence)**:
- Adds one additional step in sequence
- No IRQ generation
- Last step precedes frame counter reload

**Writing $4017**:
- Bit 7: Sequence mode (0=4-step, 1=5-step)
- Bit 6: IRQ inhibit (1=disable IRQ)
- Write timing affects immediate behavior

### 3.5 APU Mixer and Output

**Channel Outputs**:
- Pulse channels: Output on one pin
- Triangle/noise/DMC: Output on separate pin
- Uses separate nonlinear DACs for each group

**Mixing Formula**:
- Pulse and triangle use different mixing curves
- 5-bit index per channel (0-31)
- Nonlinear lookup tables compress dynamic range

**Audio Rate** (NTSC):
- Master clock / (262 scanlines × 341 PPU cycles) = ~32 kHz nominal
- Actually slightly lower due to alternate frame odd cycle

---

## 4. INPUT AND I/O

### 4.1 Standard Controller Input

**Registers**:
- **$4016 (write)**: Strobe signal ($4016 bits 0-2 = OUT0)
- **$4016/$4017 (read)**: Input data (bits 0-4, with open bus on bits 5-7)

**Strobe Mechanism**:
- Writing to $4016 latches button states in controller
- OUT0 signal initiates shift sequence
- Latching enables sequential readout

**Button Encoding**:
- Buttons transmitted serially on D0 line (primarily)
- Some controllers use additional lines (D3, D4)
- Reading causes controller to shift next button state onto D0

**Port Differences**:
- $4016 read: Accesses joypad 1 (via /OE1)
- $4017 read: Accesses joypad 2 (via /OE2)
- Only D0, D3, D4 connected to both ports on NES
- Famicom/AV Famicom differ in line routing

**Button Data** (8 bytes per read cycle):
1. A button
2. B button
3. SELECT
4. START
5. Up
6. Down
7. Left
8. Right

### 4.2 Open Bus Behavior

**Core Concept**:
- Reading unmapped CPU addresses returns previously-read bus data (decay state)
- Behavior varies by addressing mode

**Address Mode Effects**:
- Absolute: Returns high byte of address operand
- Indexed: Uses high byte of base address (pre-index)
- Indirect: Returns high byte of pointer value

**Controller Port Open Bus** ($4016-$4017):
- Affects bits 4-0 when reading non-existent bits
- Upper bits repeat from prior reads
- Notable game: Mindscape titles expect exactly $41

**DMC DMA and Open Bus**:
- DMC samples can interfere with controller reads
- May force certain bits high (weak pullup effects)
- Games sometimes read multiple times to compensate

---

## 5. MEMORY MANAGEMENT AND MAPPERS

### 5.1 iNES File Format

**Header Structure** (16 bytes):
- **Bytes 0-3**: Constant "NES" followed by EOF ($1A)
- **Byte 4**: PRG ROM size in 16 KB units
- **Byte 5**: CHR ROM size in 8 KB units (0 = CHR RAM)
- **Byte 6**: Configuration flags
  - Bits 0-3: Mapper number (low nibble)
  - Bit 1: CIRAM A10 (0=vertical, 1=horizontal mirroring)
  - Bit 2: Battery-backed RAM present
  - Bit 3: Trainer present (512 bytes)
- **Byte 7**: Configuration flags
  - Bits 4-7: Mapper number (high nibble)
  - Bits 0-3: Console type / system
- **Bytes 8-15**: Padding (should be $00)

**File Organization**:
1. Trainer (optional, 512 bytes)
2. PRG ROM data (size from header × 16 KB)
3. CHR ROM data (size from header × 8 KB)
4. PlayChoice data (optional)

**Mapper Number Encoding**:
- Mapper = (header[7] & 0xF0) << 4 | (header[6] & 0xF0) >> 4

**Variants**:
- Archaic iNES: Old format with issues
- iNES 0.7: Standard iNES format (255 mappers)
- NES 2.0: Modern format (4095 mappers)

### 5.2 Mapper Overview

**Purpose**:
- Enables bank switching to exceed native 40 KB limit
- Controls memory organization, mirroring, and features

**Categories**:
1. **Discrete Logic Mappers**: Individual components, sometimes bus conflict issues
2. **ASIC Mappers**: Single-chip (Nintendo MMC, Konami VRC, etc.)

**Common Capabilities**:
- Bank switching (PRG-ROM, CHR-ROM, PRG-RAM)
- Nametable mirroring control
- IRQ generation (scanline and timer-based)
- Expansion audio (primarily Famicom)

**Common Mapper Types**:
- **Mapper 0 (NROM)**: No bank switching, 40 KB max
- **Mapper 1 (MMC1)**: Versatile early standard, shift register interface
- **Mapper 3 (CNROM)**: Simple CHR switching
- **Mapper 4 (MMC3)**: Industry standard, scanline IRQ generation
- **Mapper 5 (MMC5)**: Advanced features, expansion audio
- **VRC Chips**: Konami mappers with sophisticated IRQ

### 5.3 MMC1 Mapper (Nintendo's Mapper 1)

**Interface**: Serial shift register protocol (reduces pin count vs parallel)

**Shift Register Mechanism**:
- Writes to $8000-$FFFF send data bit-by-bit
- Writing $80+ ($80-$FF) with bit 7 set clears shift register
- Five sequential writes with bit 7 clear load 5-bit value
- Only lowest bit of each write matters

**Register Organization**:
- $8000-$9FFF (register 0): Control register
- $A000-$BFFF (register 1): CHR bank 0
- $C000-$DFFF (register 2): CHR bank 1
- $E000-$FFFF (register 3): PRG bank and RAM control

**Control Register** ($8000-$9FFF):
- Bits 0-1: Mirroring (0=one-screen lower, 1=one-screen upper, 2=vertical, 3=horizontal)
- Bit 2: PRG bank mode (0=32 KB, 1=16 KB)
- Bit 3: CHR bank mode (0=8 KB, 1=dual 4 KB)
- Bit 4: PRG-RAM enable (varies by chip revision)

**Default Layout**:
- PRG: $8000-$BFFF (16 KB switchable), $C000-$FFFF (16 KB fixed to last bank)
- CHR: Two 4 KB banks or one 8 KB bank
- PRG-RAM: Optional 8 KB at $6000-$7FFF

**Notable Quirks**:
1. **Consecutive-cycle ignoring**: Mapper ignores writes on consecutive CPU cycles
   - Occurs during read-modify-write (INC, DEC, ROR, etc.)
   - Bill & Ted's Excellent Adventure exploits this for reset behavior

2. **Multiple revisions**: At least 7 versions (MMC1A-B3) with differences
   - Bit 4 register $E000 behavior varies
   - Affects PRG-RAM and PRG-ROM addressing

3. **Board variants**: Physical boards repurpose address lines
   - SNROM, SOROM, SXROM support extended ROM sizes
   - Can support 512 KB PRG-ROM or multiple RAM banks

### 5.4 MMC3 Mapper

**Register Organization** (paired interface):
- $8000-$9FFF (even/odd): Bank select and data pairs
- $A000-$BFFF (even/odd): Mirroring
- $C000-$DFFF (even/odd): IRQ latch and counter reload
- $E000-$FFFF (even/odd): IRQ disable/enable

**Bank Select Register** ($8000):
- Bits 0-2: Select which register to configure (R0-R7)
- Bit 6: CHR/PRG addressing mode inversion
- Bit 7: PRG banking mode

**Banking**:
- R0-R1: Two 2 KB CHR banks (mode 0) or four 1 KB CHR banks (mode 1)
- R2-R5: Four 1 KB CHR banks (mode 1) or two 2 KB CHR banks (mode 0)
- R6-R7: Two 8 KB PRG banks (swappable or fixed via bit 6)
- Top 2 bits ignored (6 PRG ROM address lines only)

**PRG Memory Layout**:
- $8000-$9FFF: 8 KB swappable bank (R6/R7)
- $A000-$BFFF: 8 KB swappable bank (opposite of $8000)
- $C000-$DFFF: 8 KB swappable bank (R6/R7)
- $E000-$FFFF: Fixed to last bank

**IRQ Mechanism** (Scanline Counter):
- Based on PPU A12 rising edge detection
- Counter logic: "triggers on rising edge after line low for 3 M2 falling edges"
- When PPU A12 rises: counter either reloads or decrements
- IRQ fires when counter reaches zero (if enabled)

**Chip Revisions**:
- Sharp MMC3: Generates IRQ every scanline when latch ($C000) = $00
- NEC MMC3: Generates single IRQ when latch = $00

---

## 6. HARDWARE QUIRKS AND EDGE CASES

### 6.1 PPU Errata

**Sprite 0 Hit Limitations**:
- Fails at X=255 position
- Requires opaque pixels in both background and sprite 0

**Sprite Overflow Bug**:
- Detection mechanism fundamentally broken
- Counts wrong number of sprites
- Hardware glitch in evaluation circuitry

**Palette Corruption**:
- Toggling rendering mid-screen corrupts palette RAM
- Occurs during palette RAM access while rendering enabled/disabled

**Scroll Glitches**:
- Writing PPUSCROLL at precise timing windows causes artifacts
- Related to shift register operation timing

**OAM Decay** (especially PAL):
- Sprite data corrupts if rendering disabled too long
- OAM uses dynamic RAM requiring refresh cycles
- PAL systems forced refresh to prevent data loss

**Startup Quirks** (NES-001 Frontloader):
- After power-on/reset, PPU refuses register writes for ~1 frame
- Affects PPUCTRL, PPUMASK, PPUSCROLL, PPUADDR
- Games must wait for frame before writing registers

### 6.2 CPU Edge Cases

**JMP Indirect Bug**:
- 6502 inherited quirk (also in NES 2A03)
- When target address page boundary, low byte wraps to beginning of page
- Example: JMP ($1FFF) reads low from $1FFF, high from $1F00 (not $2000)

**Zero Page Address Wrapping**:
- Indexed addressing modes wrap within zero page
- Example: LDA ($FF,X) with X=1 reads address from ($00,$01), not ($FF,$00)

**Unofficial Opcodes**:
- All execute identically on 2A03 as NMOS 6502
- Less stable ones may have minor timing differences
- Some games rely on specific behavior (e.g., SLO, LAX)

**Decimal Mode**:
- D flag observable but non-functional
- NES CPU lacks BCD support (unlike original 6502)

**DMC DMA Interference**:
- DMC samples cause CPU bus stalls
- Can interfere with controller reads (double-clocking)
- Workaround: Read controllers multiple times or avoid DMC during input

### 6.3 Audio Quirks

**Sweep Behavior**:
- Differs between pulse channels
- Specific sequences cause inaudible clicks
- Period value writes cause audible artifacts

**DPCM Padding**:
- Samples require specific alignment
- Wraparound from $FFFF to $8000 critical for long samples

---

## 7. CRITICAL IMPLEMENTATION DETAILS

### 7.1 Timing Synchronization

**CPU-PPU Synchronization**:
- Master clock divided by 12 (NTSC) for CPU
- Master clock divided by 4 (NTSC) for PPU
- Result: 3 PPU cycles per 1 CPU cycle

**Frame Structure** (NTSC):
- 262 scanlines × 341 PPU cycles = 89,342 PPU cycles per frame
- 262 scanlines × (113⅓) CPU cycles = 29,780⅔ CPU cycles per frame
- Frame time: 16.63 ms at 1.79 MHz

**OAM DMA Timing**:
- ~513 CPU cycles to transfer 256 bytes
- Additional cycle if write begins on odd CPU cycle (specific timing window)
- Halts all CPU operation during transfer

### 7.2 Test ROM Availability

**CPU Tests**:
- **nestest** (primary): Validates CPU operation, includes golden log
- Branch timing tests
- Instruction execution tests
- Interrupt behavior tests

**PPU Tests**:
- Sprite handling validation
- Palette management tests
- NMI timing tests (cycle-accurate)
- OAM functionality tests

**APU Tests**:
- Sound channel mixing
- Envelope behavior
- Sweep unit operation
- DMC DMA interaction

**Mapper Tests**:
- Individual mapper implementation validation
- Bank switching verification
- Mirroring correctness
- IRQ generation timing

**Source**: https://github.com/christopherpow/nes-test-roms

### 7.3 Games Requiring Accurate Emulation

**Battletoads**:
- Infamous for demanding precise CPU-PPU timing
- Cycle penalties critical
- Robust sprite 0 detection required
- Hangs on first stage without proper timing

**StarTropics**:
- Requires exact MMC3 IRQ timing
- Even 1-2 cycle delays cause sprite flickering during palette changes

**Super Mario Bros.**:
- Correct palette mirroring required
- Sprite 0 detection essential
- 1-byte PPUDATA read delay critical
- Title screen freezes without proper implementation

**Marble Madness / Pirates**:
- Mid-scanline CHR bank switching
- Requires fairly precise timing

**Crystalis**:
- MMC3 scanline IRQ for vertical splits
- Incorrect timing creates visible seam during map wandering

**Shinsenden**:
- Exploits MMC1 reset bit behavior
- Illegal instruction accidentally triggers mapper reset

**Battletoads & Double Dragon**:
- Reads non-existent WRAM relying on open bus values
- Pre-loaded values can cause crashes at specific points

---

## 8. ARCHITECTURAL PATTERNS AND BEST PRACTICES

### 8.1 CPU Implementation

**Instruction Execution Pattern**:
1. Fetch opcode
2. Decode addressing mode
3. Calculate effective address (may require dummy reads)
4. Perform operation
5. Set/clear status flags based on result
6. Increment PC or branch
7. Poll interrupts at end of instruction

**Cycle Counting**:
- Critical for accurate timing
- Account for page crossing penalties
- Track workCyclesLeft for remaining instruction cycles
- Coordinate with PPU every cycle

**Bus Behavior**:
- Every cycle reads or writes (no idle cycles)
- Track data bus state for open bus behavior
- DMC DMA can halt CPU at arbitrary times

### 8.2 PPU Implementation

**Rendering Loop**:
1. Calculate current scanline and cycle
2. Fetch memory based on position
3. Update shift registers every 8 cycles
4. Select pixel from shift register based on fine X
5. Fetch sprite data during evaluation phase
6. Apply priority multiplexer (background vs sprite)
7. Output pixel to framebuffer

**Memory Access Coordination**:
- Track PPU A12 for mapper IRQ signals
- OAM DMA steals cycles from CPU
- VRAM accessible only during vblank

**Flag Timing**:
- Vblank flag: Set at exact cycle (241, cycle 1)
- Sprite 0 hit: Set during pixel rendering when conditions met
- Sprite overflow: Set during evaluation (broken on hardware)

### 8.3 Mapper Synchronization

**Bank Switching on CPU Writes**:
- Detect writes to mapper address ranges
- Update internal bank selection
- Potentially change active ROM/RAM

**IRQ Generation**:
- Track PPU A12 for scanline counting (MMC3)
- Generate IRQ on specific conditions
- Coordinate with CPU interrupt system

---

## 9. COMPREHENSIVE REFERENCE LINKS

### 9.1 Core NES Documentation
- **Main Reference Guide**: https://www.nesdev.org/wiki/NES_reference_guide
- **Cycle Reference Chart**: CPU-PPU timing coordination and synchronization
- **CPU Architecture**: 6502 variant specifications
- **PPU Architecture**: Rendering pipeline and memory organization

### 9.2 Specialized Technical Topics
- **CPU Interrupts**: NMI, IRQ, BRK timing and edge cases
- **PPU Rendering**: Tile fetching and shift register operation
- **PPU Registers**: All 8 memory-mapped registers with bit details
- **CPU Memory Map**: Address space organization and mirroring
- **APU Specifications**: All 5 sound channels and control mechanisms
- **Registers**: CPU status flags and control
- **CPU Addressing Modes**: All 13 modes with cycle details
- **PPU Memory Map**: Pattern tables, nametables, palettes, OAM
- **OAM (Sprite Memory)**: Structure, DMA mechanism, evaluation
- **Sprite Priority**: Rendering order and background interaction
- **Scrolling**: Fine/coarse coordinates and wraparound behavior
- **Nametables**: Structure and mirroring modes
- **Palette**: Color selection and transparency
- **Attribute Tables**: 2×2 tile block palette assignment

### 9.3 Mappers and Storage
- **Mappers Overview**: Categories and common types
- **MMC1**: Shift register-based banking (most common)
- **MMC3**: Scanline IRQ generation and bank selection
- **iNES Format**: File format specification

### 9.4 Input and I/O
- **Input Devices**: Standard controller operation
- **Open Bus**: Unmapped memory behavior
- **Ports**: Controller and APU register details

### 9.5 Testing and Compatibility
- **Emulator Tests**: Comprehensive test ROM collection
- **Tricky-to-Emulate Games**: Games exposing bugs and edge cases

### 9.6 Hardware Quirks
- **Errata**: Known bugs and implementation quirks
- **PPU Edge Cases**: Palette corruption, scroll glitches, OAM decay
- **CPU Edge Cases**: JMP indirect bug, zero page wrapping

---

## 10. IMPLEMENTATION PRIORITIES

### For Basic Emulation (MVP):
1. CPU core with accurate instruction timing
2. PPU memory and basic rendering
3. Mapper 0 (NROM) support
4. Standard controller input
5. VBlank timing for synchronization

### For Compatibility:
1. Accurate sprite 0 hit detection
2. Correct tile fetch and shift register operation
3. Proper interrupt handling (NMI/IRQ edge cases)
4. Mapper 1 (MMC1) support
5. Palette and attribute table handling

### For Accuracy:
1. Cycle-accurate CPU-PPU coordination
2. DMC DMA interrupt handling
3. Open bus behavior
4. PPU startup quirks (NES-001 delay)
4. MMC3 scanline IRQ counter (A12 edge detection)
5. Sound channel implementation
6. APU frame counter sequencing

### For Advanced Features:
1. Multiple mapper support (MMC5, VRC chips)
2. Full APU implementation with all envelope behaviors
3. Palette corruption edge cases
4. OAM decay on PAL systems
5. Movie recording for reproducible testing

