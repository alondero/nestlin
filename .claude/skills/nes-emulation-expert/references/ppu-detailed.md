# PPU Architecture and Advanced Implementation Details

## PPU Register Details

### PPUCTRL ($2000) - Control Register

- **Bits 0-1**: Base nametable address
  - 0 = $2000, 1 = $2400, 2 = $2800, 3 = $2C00
- **Bit 2**: VRAM address increment per $2007 read/write
  - 0 = +1 (horizontal scrolling), 1 = +32 (vertical scrolling)
- **Bit 3**: Sprite pattern table address
  - 0 = $0000, 1 = $1000
- **Bit 4**: Background pattern table address
  - 0 = $0000, 1 = $1000
- **Bit 5**: Sprite size
  - 0 = 8×8 pixels, 1 = 8×16 pixels
- **Bit 6**: Master/slave mode (always 0 on NES)
- **Bit 7**: NMI enable
  - 1 = generate NMI on vblank

**Special**: Writes ignored until first pre-render scanline after power-on (NES-001 startup delay).

### PPUMASK ($2001) - Rendering Control

- **Bit 0**: Grayscale mode (1 = monochrome)
- **Bit 1**: Show background in leftmost 8 pixels
- **Bit 2**: Show sprites in leftmost 8 pixels
- **Bit 3**: Show background (0 = disable rendering)
- **Bit 4**: Show sprites (0 = disable rendering)
- **Bits 5-7**: Color emphasis/tint (R, G, B emphasis)

**Critical**: Disabling rendering mid-screen can corrupt palette RAM!

### PPUSTATUS ($2002) - Status Register

- **Bit 5**: Sprite overflow (set if >8 sprites on scanline)
  - Hardware bug: Counts wrong number of sprites
- **Bit 6**: Sprite 0 hit (set when opaque sprite 0 overlaps background)
  - **Critical bug**: Fails when sprite at X=255
- **Bit 7**: Vblank flag (set during vblank period)
  - Cleared when read
  - Also clears internal write toggle (w) to first write state

**Special**: Reading clears vblank flag AND resets the PPUSCROLL/PPUADDR write toggle!

### PPUSCROLL ($2005) and PPUADDR ($2006)

Both use shared internal write toggle (w):

**PPUSCROLL** (requires two writes):
1. First write: Sets fine X scroll (bits 0-2) and coarse X scroll (bits 3-7)
2. Second write: Sets coarse Y (bits 0-4) and fine Y (bits 5-7)

**PPUADDR** (requires two writes):
1. First write: Sets upper 6 bits of 14-bit VRAM address
2. Second write: Sets lower 8 bits of address

**Critical desynchronization issue**: If write sequence broken or mismatched, toggle becomes out of sync. Reading $2002 resets it to first write state.

### PPUDATA ($2007) - VRAM Access

- Implements read buffer mechanism for safety
- **Reads**: Return previously buffered data (delayed by one read)
  - Palette reads are NOT delayed (exception)
- **Writes**: Go directly to VRAM
- **Auto-increment**: Address increments by 1 or 32 based on PPUCTRL bit 2
- **Critical**: After loading VRAM data via PPUDATA reads, must reload scroll position to prevent rendering errors

## VRAM Address Register Details

**Structure** (15-bit address):
```
yyy NN YYYYY XXXXX
||| || ||||| +++++-- coarse X (0-31)
||| || +++++-------- coarse Y (0-29)
||| |+-------------- horizontal nametable select
||| +--------------- vertical nametable select
+++----------------- fine Y (0-7)
```

**Components**:
- **Fine X**: 3 bits, pixel within tile (0-7) - NOT in v register, stored separately
- **Fine Y**: 3 bits (v register bits 12-14), scanline within 8×8 tile
- **Coarse X**: 5 bits, tile column (0-31)
- **Coarse Y**: 5 bits, tile row (0-29)
- **Nametable**: 2 bits, selects quadrant ($2000/$2400/$2800/$2C00)

**Wraparound Behavior**:
- **Coarse X wraparound**: 31 → 0 and toggles horizontal nametable (toggle bit 10)
- **Coarse Y wraparound**: 29 → 0 and toggles vertical nametable (toggle bit 11); values 30-31 produce special attribute behavior
- **Fine Y increment**: 7 → 0 and increments coarse Y

## Nametable Mirroring Modes

### Horizontal Mirroring
- $2000/$2800 share RAM (top row mirrors)
- $2400/$2C00 share RAM (bottom row mirrors)
- Best for vertical scrolling
- **Implementation**: PPU A11 → CIRAM A10

### Vertical Mirroring
- $2000/$2400 share RAM (left column mirrors)
- $2800/$2C00 share RAM (right column mirrors)
- Best for horizontal scrolling
- **Implementation**: PPU A10 → CIRAM A10

### Single-Screen
- All nametables reference same 2KB
- Software-controlled via mapper
- Enables status bars while maintaining playfield

### Four-Screen
- Requires additional 2KB cartridge RAM
- Each nametable has unique content
- Full 64×60 tilemap possible

### Diagonal (Rare)
- CIRAM A10 = PA11 XOR PA10
- Direction changes mid-scroll

### L-Shaped (Rare)
- CIRAM A10 = PA11 OR PA10
- Four-way scrolling at boundaries

## Attribute Table Organization

Located at $x3C0-$x3FF within each nametable (64 bytes):

- 8×8 array covering 32×30 tilemap
- Each byte controls 2×2 tile block (16×16 pixels)
- Each block divided into 4 quadrants:
  - Bits 1-0: Top-left 2×2 tiles
  - Bits 3-2: Top-right 2×2 tiles
  - Bits 5-4: Bottom-left 2×2 tiles
  - Bits 7-6: Bottom-right 2×2 tiles

**Calculation** (for coarse X, coarse Y):
- Attribute address: Base + ((coarse_y >> 2) × 8) + (coarse_x >> 2)
- Bit position: ((coarse_y & 2) << 1) | (coarse_x & 2)

## PPU Memory Access Pattern

### Tile Fetching Sequence (8 cycles per tile)

1. **Cycle N**: Nametable byte (which tile to display)
2. **Cycle N+2**: Attribute byte (palette/priority)
3. **Cycle N+4**: Pattern table low byte (pixel data bits 0)
4. **Cycle N+6**: Pattern table high byte (pixel data bits 1)

Each memory access takes 2 PPU cycles.

### Address Calculations

**Nametable Address**:
- Base: $2000 + (nametable << 10)
- Offset: (coarse_y << 5) + coarse_x
- Final: $2000 + (nametable << 10) + (coarse_y << 5) + coarse_x

**Attribute Address**:
- Base: $2000 + (nametable << 10) + $3C0
- Offset: (coarse_y >> 2) × 8 + (coarse_x >> 2)
- Bit position: ((coarse_y & 2) << 1) | (coarse_x & 2)

**Pattern Table Address**:
- Base: (pattern_table << 12) [0 or $1000]
- Offset: (tile_id << 4) [16 bytes per tile]
- Bitplane: +0 for low, +8 for high
- Scanline: + fine_y
- Final: pattern_table + (tile_id << 4) + fine_y + [0 or 8]

## Shift Register Operation

- Two 16-bit pattern data shift registers (background)
- 1-bit latches feed 8-bit attribute shift registers
- **Every 8th cycle** in fetch regions: Pattern and attribute data transfer into shift registers
- **Every cycle**: 4-bit pixel selected by fine X from low 8 bits, then data shifted

**Critical**: Shift registers shift every cycle, but reload every 8 cycles. Off-by-one timing errors here cause visual artifacts.

## Sprite System (OAM)

### OAM Structure

256 bytes storing 64 sprites (4 bytes each):

- **Byte 0**: Y position ($EF-$FF = offscreen)
- **Byte 1**: Tile index
- **Byte 2**: Attributes
- **Byte 3**: X position

### Sprite Attributes (Byte 2)

- **Bits 0-1**: Palette (0-3, but internally 4-7 for sprites)
- **Bit 4**: Flip horizontally
- **Bit 5**: Flip vertically
- **Bit 6**: Priority (0 = in front, 1 = behind background)
- **Bit 7**: Flip V (duplicate of bit 5?)

### Sprite Priority Rules

- Lower OAM indices display in front of higher indices
- When frontmost opaque sprite has back-priority (bit 6=1), background draws in front
- Allows lower-indexed back-priority sprite to obscure higher-indexed front-priority sprite

### Sprite 0 Hit

- Set when opaque sprite 0 pixel overlaps non-transparent background pixel
- Used by games for precise timing (scroll updates at exact scanlines)
- **Critical bug**: Fails when sprite at X=255

### OAM DMA

- Write to $4014 triggers DMA from CPU RAM to OAM
- Copies 256 bytes in ~513 CPU cycles
- Additional cycle if write occurs on odd CPU cycle
- Halts CPU during transfer (cannot execute instructions)

## Frame Structure (262 scanlines, NTSC)

### Scanline Types

- **Pre-render (261)**: Fills shift registers without display output
- **Visible (0-239)**: Display scanlines (240 total)
- **Post-render (240)**: One idle scanline
- **Vblank (241-260)**: 20 blanking scanlines

### Visible Scanline Phases (341 cycles)

1. **Cycles 1-256**: Visible rendering
   - Background tile fetching and rendering
   - 32 complete tiles + 1 partial = 33 fetches
   - One fetch every 8 cycles
   - 4 memory accesses per tile

2. **Cycles 257-320**: Sprite evaluation
   - Y position evaluation (determine visible sprites)
   - Fetch sprite tile data for next scanline
   - Reuse background fetch circuitry

3. **Cycles 321-336**: Prefetch first two tiles
   - Loads initial tile data for next scanline

4. **Cycles 337-340**: Unused nametable fetches

5. **Cycle 341**: Extra cycle on alternate frames (odd frames shorter)

## PPU Timing Synchronization

- **NTSC**: Exactly 3 PPU cycles per 1 CPU cycle (341 ÷ 113⅓)
- **PAL**: Exactly 3.2 PPU cycles per 1 CPU cycle
- **Frame duration**: 89,342 PPU cycles (NTSC)
- **Vblank duration**: 20 scanlines × 341 cycles = 6,820 PPU cycles
- **CPU time in vblank**: ~2,273 CPU cycles (NTSC) available for NMI handler

## Hardware Quirks and Bugs

### Sprite 0 Hit Failure at X=255
- Sprite 0 hit detection fails when sprite positioned at X=255
- Games must avoid this position or accept missing hit detection

### Sprite Overflow Bug
- Overflow detection mechanism fundamentally broken
- Counts wrong number of sprites (hardware glitch in evaluation)

### Palette Corruption
- Toggling rendering mid-screen corrupts palette RAM
- Occurs during palette RAM access while rendering enabled/disabled
- Affects games that use mid-screen palette changes

### Scroll Glitches
- Writing PPUSCROLL at precise timing windows causes artifacts
- Related to shift register operation timing

### OAM Decay
- Sprite data corrupts if rendering disabled too long
- OAM uses dynamic RAM requiring refresh
- PAL systems forced refresh to prevent corruption
- Critical for accurate PAL emulation

### NES-001 Startup Quirk
- After power-on/reset, PPU refuses register writes for ~1 frame
- Affects PPUCTRL, PPUMASK, PPUSCROLL, PPUADDR
- Games must wait for frame before writing registers

## Implementation Priorities

**For basic rendering**:
1. Correct tile fetch sequencing
2. Shift register reload timing (every 8 cycles)
3. Proper V-register wraparound behavior
4. Basic nametable mirroring

**For compatibility**:
1. Accurate sprite 0 hit detection (except X=255 edge case)
2. Correct attribute table addressing
3. Sprite evaluation and OAM DMA
4. Vblank flag timing

**For accuracy**:
1. Cycle-perfect tile fetching
2. 2-pixel pipeline delay between fetch and output
3. Palette corruption edge cases
4. OAM decay on PAL systems
5. Startup quirks (NES-001 delay)
