# MMC3 IRQ Behavior - Important Finding

## Key Discovery

The MMC3 scanline IRQ is triggered **solely by A12 rising edges**, NOT by a fixed-rate scanline clock.

This means:
1. Every time PPU address bit 12 (A12) transitions from low to high, the MMC3 IRQ counter decrements
2. A12 naturally toggles when the PPU fetches pattern data crossing the $1000 boundary
3. There is NO separate "scanline clock" mechanism

## Previous Implementation Bug

The emulator had BOTH:
- `clockScanline()` being called at cycle 260 of every visible scanline
- `notifyA12Edge()` being called on actual A12 rising edges during pattern fetches

Both mechanisms called `clockMmc3Counter()`, causing **double-counting** - the IRQ counter decremented twice per scanline instead of once.

## Correct Implementation

The `clockScanline()` mechanism was removed entirely. The `notifyA12Edge()` mechanism via A12 edge detection is the sole IRQ trigger. This matches real MMC3 hardware behavior as documented on NESdev wiki:

> "The MMC3 scanline counter is based entirely on PPU A12, triggered on a rising edge after the line has remained low for three falling edges of M2."

## Files Changed

- `Mapper4.kt`: Removed `clockScanline()` override (uses default no-op from Mapper interface)
- `Ppu.kt`: Removed `clockScanline()` call at cycle 260
- `Mapper4IrqTest.kt`: Removed invalid test that verified clockScanline behavior

## Impact

This fix affects any game that relies on MMC3 scanline IRQs for timing:
- Mega Man 4-6
- Kirby's Adventure
- Contra
- Many others

The double-counting was causing IRQs to fire at roughly double the expected rate during rendering.
