# mesen-trace — reusable Mesen2 Lua instruments

Checked-in versions of the four instruments that have historically cracked Nestlin's
hardest bugs, so they never have to be rebuilt from scratch mid-debugging-session.
All are verified against the installed **Mesen2 v2.1.1** (`X:\src\nestlin\tools\Mesen2\Mesen.exe`),
whose Lua API differs from the upstream docs — see "API reality" below.

## Invocation (headless, no display needed)

```
X:\src\nestlin\tools\Mesen2\Mesen.exe --testRunner --doNotSaveSettings <script.lua> <absolute-rom-path.nes>
```

- ROM path **must be absolute** (Mesen's cwd is its install dir; relative paths silently fail to load — symptom: wall time > 10s).
- Output lands in `<MesenDir>\LuaScriptData\<script-base-name>\` — e.g. `write-watch.lua` → `LuaScriptData\write-watch\writes.txt`.
- Exit code 0 = clean run (`emu.stop(0)`), 2 = the script's own failure path.
- Edit the `==== CONFIG ====` block at the top of each script (target frame, watch range) before running; copy to a temp dir first if you don't want to dirty the checkout.

## The instruments

| Script | Question it answers | Output |
|---|---|---|
| `write-watch.lua` | Which mapper registers does this game actually write, and when? | `writes.txt`: `frame=N addr=$XXXX val=$YY` |
| `interrupt-counter.lua` | How many NMIs/IRQs fire per frame? (Compare vs Nestlin's `Cpu.nmiCount`/`irqCount`.) | `interrupts.txt`: `frame=N nmi=X irq=Y` |
| `ppuctrl-transitions.lua` | Did the game ever enable rendering/NMI? When did PPUCTRL/PPUMASK change? | `ppuctrl.txt`: `frame=N PPUCTRL=$XX` |
| `chr-dump.lua` | Which CHR data is mapped at frame N? (Byte-diff vs Nestlin's CHR window.) | `chr.txt`: `OOOO:VV` per byte |

Typical divergence workflow: run `./gradlew diverge -Prom=<rom> -Pframe=<n>` first (it
compares full state automatically); reach for these scripts when you need the
*time-series* view (when did X first happen?) rather than the frame-N snapshot.

## API reality on the installed v2.1.1 (differs from mesen.ca docs)

- Memory callbacks: **`emu.callbackType`** with keys `exec`/`read`/`write`.
  `emu.memCallbackType` is **nil**; there is no `cpuWrite`/`ppuRead` variant.
- `emu.getState()` **inside** an event or memory callback has **no `.ppu` and no `.cart`**
  (and at top level it returns a FLAT dotted-key table: `s["cpu.pc"]`, not `s.cpu.pc`).
  An unguarded nil index silently aborts the rest of the callback — wrap in `pcall`
  or, better, don't call `getState()` in callbacks at all (these scripts don't).
- Frame attribution in callbacks: count `emu.eventType.endFrame` yourself.
- `emu.eventType.irq` fires for mapper IRQs — reliable for counting.
- `--testRunner` headless works, including `emu.read`; `emu.stop(code)` exits cleanly.
- `emu.getScriptDataFolder()` has no trailing separator — append one.
- I/O access must have been enabled once in the GUI (Script → Settings → Restrictions →
  Allow access to I/O and OS functions); it's persisted in settings.json and
  `--doNotSaveSettings` won't unset it.

When embedding any of this in Kotlin `"""..."""` strings: `$` interpolates — use `${'$'}`
for literal `$`, **including inside Lua comments**.
