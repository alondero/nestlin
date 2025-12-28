# CPU Architecture and Implementation Details

## 6502 Processor Fundamentals

The NES CPU is a Ricoh-manufactured 6502 variant (2A03 on NTSC, 2A07 on PAL):

**Critical Differences from Original 6502**:
- **No decimal (BCD) mode**: The D flag is observable but non-functional
- **NTSC Clock**: ~1.79 MHz (559 ns per cycle)
- **PAL Clock**: ~1.66 MHz (601 ns per cycle)
- **Every cycle is a read or write**: No idle cycles (unlike some 6502 variants)

## Addressing Modes (13 Total)

### Indexed Addressing (6 modes)

1. **Zero Page Indexed (d,x)**: `val = PEEK((arg + X) % 256)`
   - Takes 4 cycles
   - Wraps within zero page (doesn't cross to next page)

2. **Zero Page Indexed (d,y)**: Same as above with Y register
   - Preferred for zero page address tables

3. **Absolute Indexed (a,x)**: `val = PEEK(arg + X)`
   - Takes 4 cycles normally
   - Takes 5 cycles on reads if page boundary crossed
   - Takes 5 cycles on store instructions (always)

4. **Absolute Indexed (a,y)**: Same as above with Y
   - Takes 4+ cycles

5. **Indexed Indirect (d,x)**: `val = PEEK(PEEK((arg + X) % 256) + PEEK((arg + X + 1) % 256) * 256)`
   - Takes 6 cycles
   - Address calculation wraps within zero page

6. **Indirect Indexed (d),y**: `val = PEEK(PEEK(arg) + PEEK((arg + 1) % 256) * 256 + Y)`
   - Takes 5 cycles normally
   - Takes 6 cycles if page boundary crossed

### Other Modes (7 modes)

- **Implicit**: No operand (RTS, CLC) - 2-6 cycles
- **Accumulator**: Operates on A register - 2 cycles
- **Immediate**: 8-bit operand (#$00) - 2 cycles
- **Zero Page**: 8-bit address ($00) - 3 cycles
- **Absolute**: 16-bit address ($0000) - 4 cycles
- **Relative**: PC-relative branch - 2-4 cycles (extra if taken, extra if page crossing)
- **Indirect**: JMP only - 5 cycles (HAS PAGE BOUNDARY BUG)

### The "Oops" Cycle - Page Boundary Crossing

Page boundary crossing causes extra cycles:
- **Read instructions**: +1 cycle if address crosses page boundary
- **Store instructions**: +1 cycle if address crosses page boundary (always happens for absolute indexed)

This is critical for cycle-accurate timing. Many games depend on exact cycle counts.

## Status Flags (NV-BDIZC)

**Bit 7 (N - Negative)**: Set when instruction result has bit 7 = 1

**Bit 6 (V - Overflow)**:
- Set by ADC/SBC on signed overflow (two's complement)
- BIT instruction loads memory bit 6 directly to V flag

**Bit 5**: No CPU effect (always available for use)

**Bit 4 (B - Break)**:
- Exists only in stack-pushed status byte, NOT as CPU register
- Value 0 for NMI/IRQ interrupts
- Value 1 for BRK instruction or PHP instruction
- Distinguishes interrupt sources in handler

**Bit 3 (D - Decimal)**:
- Observable but non-functional on NES
- Cannot be used for BCD arithmetic
- Some games use it as general flag storage

**Bit 2 (I - Interrupt Disable)**:
- When set (1): IRQ inhibited
- Does NOT affect NMI, BRK, or reset
- RTI sets/clears immediately
- CLI/SEI set/clear AFTER instruction polling (can delay IRQ response)

**Bit 1 (Z - Zero)**:
- Set when instruction result equals 0
- Set by most ALU instructions and comparisons

**Bit 0 (C - Carry)**:
- ADC: Set on addition carry
- SBC/CMP: Set when no borrow (carry logic inverted for subtraction)
- Shift instructions: Contains shifted-out bit

## Interrupts - Critical Details

### NMI (Non-Maskable Interrupt)

- **Edge-sensitive**: Responds to high-to-low transition on NMI line
- **Edge detector sampling**: During φ2 clock of each cycle
- **Cannot be disabled**: I flag has no effect on NMI
- **Timing**: Fires after post-render blanking line (scanline 241, cycle 1)
- **Vector**: $FFFA-$FFFB

### IRQ (Interrupt Request)

- **Level-sensitive**: Responds to continuous low signal
- **Duration**: Creates internal signal lasting one cycle
- **Can be disabled**: I flag disables IRQ (but not NMI)
- **Vector**: $FFFE-$FFFF

### BRK (Software Interrupt)

- **Shares IRQ vector**: But distinguished by B flag in pushed status
- **B flag**: Set when BRK executes (not for IRQ/NMI)

### Interrupt Timing and Polling

**CRITICAL**: Interrupts polled at END of instruction, and what matters is interrupt line status at END OF SECOND-TO-LAST CYCLE (not checked before final cycle).

**Interrupt Hijacking Bug**:
- When NMI asserts during first four ticks of BRK instruction, CPU executes BRK's initial operations then branches to NMI vector
- Effectively hijacks the BRK instruction mid-execution

**CLI/SEI Delayed Response**:
- CLI and SEI change interrupt flags AFTER polling
- Can delay interrupt service until after next instruction
- RTI affects IRQ inhibition immediately (not delayed)

**Branch Instruction Polling**:
- Interrupts polled BEFORE operand fetch cycle
- NOT polled before third cycle on taken branches
- NOT polled before page-boundary fixup cycles

## Unofficial (Illegal) Opcodes

All 256 opcodes execute on the 2A03:

- All illegal 6502 opcodes execute identically on 2A03 as on original 6502
- Result from compressed 6502 microcode architecture
- Most NMOS 6502 cores interpret them identically
- Some "less stable" instructions may have minor timing differences between batches

**Notable examples exploited in games**:
- **SLO**: Shift left then OR with accumulator
- **LAX**: Load A then X with same value
- **XAA**: Complex AND/OR operation
- **DCP**: Decrement then compare

Example: **Shinsenden** exploits MMC1 behavior via an illegal instruction to trigger mapper reset.

## Memory Access Patterns

**Every CPU cycle performs either a read or write** - there are no idle cycles on the 2A03.

This is important for:
- Accurate bus behavior simulation
- Open bus decay tracking
- DMC DMA interference
- Cycle counting precision

## Frame Timing for Interrupts

- **NTSC**: ~2,273 CPU cycles available in vblank (~20 blanking scanlines)
- **PAL**: ~7,459 CPU cycles available in vblank (~70 blanking scanlines)
- **Dendy**: ~51 blanking scanlines (PAL-compatible timing)

Games must complete NMI handlers within vblank to avoid visual artifacts.
