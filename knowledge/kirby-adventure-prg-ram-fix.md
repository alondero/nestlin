# Knowledge: Kirby's Adventure PRG-RAM Fix

## Symptoms
*   Game would show a black screen after boot.
*   Trace analysis showed the CPU getting stuck in a VBlank poll loop at `$C02D`.
*   After some time, the CPU would jump to `$0000` and eventually settle at `$0088`.

## Root Cause
*   **Missing PRG-RAM:** *Kirby's Adventure* (MMC3) uses PRG-RAM at `$6000-$7FFF` to store jump tables and other dynamic data.
*   The emulator's `Mapper4` (MMC3) implementation did not have any PRG-RAM, so it returned `0` for all reads in that range.
*   A `JMP ($6038)` instruction was executed. Since `$6038` and `$6039` returned `0`, the CPU jumped to `$0000`.
*   At `$0000`, it started executing garbage or empty memory until it crashed.

## Fix
*   Implemented 8KB PRG-RAM in `Mapper4.kt`.
*   Implemented the PRG-RAM protect register at `$A001` to properly enable/disable RAM and write protection.
*   Also proactively added 8KB PRG-RAM to `Mapper1.kt` (MMC1), as many MMC1 games also require it.

## Lessons Learned
1.  **JMP Indirect is a red flag:** If you see `JMP ($XXXX)` jumping to `0000`, check if the address being read is in a memory region that should have RAM or banked ROM.
2.  **Mapper support must be complete:** Mappers are more than just PRG/CHR banking; they often include PRG-RAM, IRQs, and other peripherals that games rely on even for basic boot.
3.  **Trace context is key:** Seeing the instructions *immediately before* the jump to low memory was what finally pinpointed the `JMP ($6038)` as the culprit.
