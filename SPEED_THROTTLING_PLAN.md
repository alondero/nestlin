# Speed Throttling Implementation Plan

## Problem Statement
The emulator currently runs uncapped (as fast as the CPU can handle), making games run too fast. We need to add frame rate throttling to match the original NES speed (60 FPS for NTSC), with a toggle to enable/disable.

## Current Architecture Analysis

### Emulation Loop (Nestlin.kt:50-64)
```kotlin
fun start() {
    running = true
    while (running) {
        (1..3).forEach { ppu.tick() }  // 3 PPU cycles
        apu.tick()                      // 1 APU cycle
        cpu.tick()                      // 1 CPU cycle
    }
}
```
- Runs as fast as possible with no throttling
- Each iteration: 3 PPU ticks, 1 APU tick, 1 CPU tick (correct 3:1:1 ratio)

### Frame Completion (Ppu.kt:171-183)
```kotlin
private fun endFrame() {
    listener?.frameUpdated(frame)  // Called every 89,342 PPU cycles
    frameCount++
    scanline = 0
    vBlank = false
}
```
- Already has frame completion detection
- Notifies listener when frame is ready
- Each frame = 262 scanlines × 341 cycles = 89,342 PPU cycles

### UI Layer (Application.kt)
- Emulation runs in separate thread (line 73-95)
- JavaFX AnimationTimer handles display updates (59-68)
- AnimationTimer doesn't control emulation speed

## Target Frame Rate
- **NTSC (US/Japan)**: 60.0988 FPS (~16.639 ms per frame)
- **PAL (Europe)**: 50.007 FPS (~19.997 ms per frame)
- For simplicity, we'll target **60 FPS** initially (can add PAL detection later)

## Implementation Plan

### 1. Add Throttling Configuration Class
**Location**: Create `src/main/kotlin/com/github/alondero/nestlin/EmulatorConfig.kt`

**Purpose**: Central configuration for emulator settings

**Implementation**:
```kotlin
data class EmulatorConfig(
    var speedThrottlingEnabled: Boolean = true,  // Default: ON
    var targetFps: Double = 60.0                 // NTSC default
) {
    val targetFrameTimeNanos: Long
        get() = (1_000_000_000.0 / targetFps).toLong()
}
```

**Why this approach**:
- Clean separation of concerns
- Easy to extend with future settings (PAL mode, custom speeds, fast-forward multiplier)
- Immutable except for toggle changes

---

### 2. Add Throttling Logic to Nestlin
**Location**: Modify `src/main/kotlin/com/github/alondero/nestlin/Nestlin.kt`

**Changes**:

#### 2a. Add config field and frame timing tracking
```kotlin
class Nestlin {
    val config = EmulatorConfig()  // Public for UI access
    private var lastFrameTimeNanos: Long = 0
    // ... existing fields
}
```

#### 2b. Add frame boundary callback to PPU
```kotlin
fun addFrameCompletionListener(callback: () -> Unit) {
    ppu.addFrameCompletionListener(callback)
}
```

#### 2c. Modify start() method to implement throttling
```kotlin
fun start() {
    running = true
    lastFrameTimeNanos = System.nanoTime()

    try {
        while (running) {
            (1..3).forEach { ppu.tick() }
            apu.tick()
            cpu.tick()

            // Check if frame completed (PPU will signal this)
            if (ppu.frameJustCompleted()) {
                throttleIfEnabled()
            }
        }
    } finally {
        cpu.dumpUndocumentedOpcodes()
    }
}

private fun throttleIfEnabled() {
    if (!config.speedThrottlingEnabled) return

    val currentTime = System.nanoTime()
    val elapsedNanos = currentTime - lastFrameTimeNanos
    val targetNanos = config.targetFrameTimeNanos

    if (elapsedNanos < targetNanos) {
        val sleepNanos = targetNanos - elapsedNanos
        val sleepMillis = sleepNanos / 1_000_000
        val remainderNanos = (sleepNanos % 1_000_000).toInt()

        if (sleepMillis > 0 || remainderNanos > 0) {
            Thread.sleep(sleepMillis, remainderNanos)
        }
    }

    lastFrameTimeNanos = System.nanoTime()
}
```

**Why this approach**:
- Throttles at frame boundaries (not every tick) for minimal overhead
- Uses high-precision `System.nanoTime()` for accurate timing
- Only sleeps when ahead of schedule (no busy-waiting)
- Separate method for testability

---

### 3. Add Frame Completion Tracking to PPU
**Location**: Modify `src/main/kotlin/com/github/alondero/nestlin/ppu/Ppu.kt`

**Changes**:

#### 3a. Add frame completion flag and listener
```kotlin
class Ppu(var memory: Memory) {
    private var frameCompletedThisTick = false
    private var frameCompletionListener: (() -> Unit)? = null
    // ... existing fields

    fun frameJustCompleted(): Boolean {
        val result = frameCompletedThisTick
        frameCompletedThisTick = false
        return result
    }

    fun addFrameCompletionListener(listener: () -> Unit) {
        frameCompletionListener = listener
    }
}
```

#### 3b. Modify endFrame() to set flag
```kotlin
private fun endFrame() {
    listener?.frameUpdated(frame)
    frameCount++
    memory.ppuAddressedMemory.currentFrameCount = frameCount
    frameCompletedThisTick = true  // ADD THIS
    frameCompletionListener?.invoke()  // ADD THIS

    if (diagnosticLogging && frameCount in diagnosticStartFrame until diagnosticEndFrame) {
        logDiagnostic("\n=== FRAME $frameCount COMPLETE ===\n")
    }

    scanline = 0
    vBlank = false
}
```

**Why this approach**:
- Frame completion already tracked; just need to expose it
- One-shot flag pattern (read-and-clear) prevents double-processing
- Optional listener for future use (statistics, debugging)

---

### 4. Add UI Toggle Control
**Location**: Modify `src/main/kotlin/com/github/alondero/nestlin/ui/Application.kt`

**Changes**:

#### 4a. Add menu bar with throttling toggle
```kotlin
override fun start(stage: Stage) {
    this.stage = stage.apply {
        title = "Nestlin - ${DISPLAY_SCALE}x Magnification"

        val menuBar = javafx.scene.control.MenuBar()
        val settingsMenu = javafx.scene.control.Menu("Settings")

        val throttleMenuItem = javafx.scene.control.CheckMenuItem("Speed Throttling (60 FPS)")
        throttleMenuItem.isSelected = nestlin.config.speedThrottlingEnabled
        throttleMenuItem.setOnAction {
            nestlin.config.speedThrottlingEnabled = throttleMenuItem.isSelected
            println("[APP] Speed throttling ${if (throttleMenuItem.isSelected) "enabled" else "disabled"}")
        }

        settingsMenu.items.add(throttleMenuItem)
        menuBar.menus.add(settingsMenu)

        val root = javafx.scene.layout.VBox()
        root.children.addAll(menuBar, canvas)

        scene = Scene(root)
        // ... rest of existing code
    }
}
```

#### 4b. Add keyboard shortcut (optional but recommended)
```kotlin
scene.setOnKeyPressed { event ->
    when (event.code) {
        javafx.scene.input.KeyCode.T -> {
            if (event.isControlDown) {
                nestlin.config.speedThrottlingEnabled = !nestlin.config.speedThrottlingEnabled
                println("[APP] Speed throttling ${if (nestlin.config.speedThrottlingEnabled) "enabled" else "disabled"}")
                event.consume()
            } else {
                handleInput(event.code, true)
            }
        }
        else -> handleInput(event.code, true)
    }
}
```

**Why this approach**:
- Native JavaFX menu (standard UI pattern)
- CheckMenuItem provides visual feedback
- Keyboard shortcut (Ctrl+T) for power users
- No need to restart emulation

---

### 5. Add Command-Line Argument Support (Optional)
**Location**: Modify `src/main/kotlin/com/github/alondero/nestlin/ui/Application.kt`

**Changes** (in start() method, around line 76-82):
```kotlin
if (!parameters.named["no-throttle"].isNullOrEmpty()) {
    nestlin.config.speedThrottlingEnabled = false
    println("[APP] Speed throttling disabled via command line")
}
```

**Usage**: `./gradlew run --args="rom.nes --no-throttle"`

---

## Testing Strategy

### Manual Testing
1. **Default behavior**: Run emulator → should feel like original NES speed
2. **Toggle off**: Uncheck menu → should run very fast
3. **Toggle on**: Check menu → should slow back down to 60 FPS
4. **Keyboard shortcut**: Press Ctrl+T → should toggle smoothly
5. **Audio sync**: Ensure audio doesn't desync when throttling

### Performance Testing
1. Monitor actual FPS (could add counter to UI later)
2. Check CPU usage (should be minimal when throttled)
3. Test on different ROMs (nestest, actual games)

### Edge Cases
1. Very slow host CPU (can't reach 60 FPS) → throttle should have no effect
2. Fast-forward mode (future feature) → should disable throttling temporarily
3. Background window → consider pausing emulation

---

## Implementation Order

1. ✅ **Create EmulatorConfig.kt** (5 min)
   - Simple data class, no dependencies

2. ✅ **Modify Ppu.kt for frame completion tracking** (10 min)
   - Add flag and listener
   - Modify endFrame()

3. ✅ **Modify Nestlin.kt for throttling logic** (15 min)
   - Add config field
   - Implement throttleIfEnabled()
   - Update start() method

4. ✅ **Add UI toggle in Application.kt** (15 min)
   - Create menu bar
   - Add CheckMenuItem
   - Wire up event handlers

5. ✅ **Test and refine** (15 min)
   - Run with nestest.nes
   - Verify toggle works
   - Check for timing accuracy

6. ✅ **Add keyboard shortcut** (5 min)
   - Optional but recommended

**Total estimated time: ~1 hour**

---

## Future Enhancements (Out of Scope)

- **PAL region support**: Detect ROM region, set 50 FPS for PAL
- **Fast-forward mode**: Multiplier (2x, 4x, unlimited)
- **FPS counter overlay**: Show actual FPS on screen
- **Frame skip**: If host CPU too slow, skip rendering but maintain game logic timing
- **Rewind feature**: Save state history (requires disabling throttling during rewind)
- **Turbo buttons**: Hold key for auto-fire

---

## Potential Issues and Mitigations

### Issue 1: Sleep Accuracy
**Problem**: `Thread.sleep()` not perfectly accurate (OS scheduling)
**Impact**: Minor FPS fluctuations (59-61 FPS instead of exactly 60)
**Mitigation**: Acceptable for now; could implement spin-wait for last few microseconds if needed

### Issue 2: Audio Desync
**Problem**: Audio thread runs independently; throttling might cause buffer underrun/overrun
**Impact**: Audio crackling or gaps
**Mitigation**: Audio buffer should handle minor variations; may need to adjust buffer size

### Issue 3: VSync Conflict
**Problem**: JavaFX AnimationTimer might be synced to display refresh rate
**Impact**: Interaction between our throttling and AnimationTimer's timing
**Mitigation**: Our throttling is on emulation thread, AnimationTimer is on render thread; should be independent

### Issue 4: Accumulating Drift
**Problem**: Small timing errors accumulate over time
**Impact**: Emulation speed drifts away from 60 FPS over minutes
**Mitigation**: Reset timing baseline periodically (e.g., every 60 frames)

---

## Validation Criteria

✅ **Success metrics**:
- Emulator runs at ~60 FPS by default (visual test: smooth animation)
- Toggle works immediately without restart
- CPU usage drops significantly when throttled
- Audio stays in sync
- No frame drops during normal operation

❌ **Failure indicators**:
- Stuttering or inconsistent frame pacing
- Audio glitches when toggling throttle
- High CPU usage even when throttled
- Toggle doesn't take effect immediately

---

## Rollback Plan

If throttling causes issues:
1. Keep toggle default to `false` (disabled)
2. Add warning message when enabling
3. Investigate timing on specific platforms (Linux/Mac/Windows)
4. Consider alternative timing methods (busy-wait, hybrid approach)

---

## Notes

- **No changes to CPU/PPU/APU cycle timing**: Throttling is purely about wall-clock time, not emulation accuracy
- **Thread-safe**: Config changes take effect immediately (no synchronization needed for boolean flag)
- **Backward compatible**: Existing tests and automation mode unaffected
- **Zero impact when disabled**: No performance overhead if throttling is off
