package com.github.alondero.nestlin.apu

import kotlin.math.PI

/**
 * The NES post-DAC analog filter chain.
 *
 * Issue #229. After the 2A03's five channels mix in the digital domain, the
 * resulting waveform passes through three physical RC filter stages before
 * reaching the TV's audio input:
 *
 * 1. **~90 Hz high-pass** — the coupling capacitor between the 2A03 output
 *    and the rest of the chain. Blocks any DC offset the digital mixer
 *    accumulated.
 * 2. **~440 Hz high-pass** — the second coupling cap, the one that gives
 *    NES audio its characteristic lack of deep bass. (A digital-only model
 *    sounds "bassier" than hardware because nothing rolls off the low end.)
 * 3. **~14 kHz low-pass** — the de-emphasis filter that suppressed RF
 *    interference picked up by the AV cables on consumer TVs.
 *
 * Each stage is modelled as a first-order RC filter via the bilinear
 * transform, which is accurate across the full 0 → Nyquist range (unlike
 * the "exponential averaging" form `y[n] = (1-α)·y[n-1] + α·x[n]`, which
 * is only valid when the cutoff is well below the sample rate).
 *
 * For a low-pass at cutoff `fc`:
 *
 * ```
 *     c   =  Fs / (π · fc)
 *     y[n] = ( 1 / (1+c) ) · (x[n] + x[n-1])  +  ((c-1)/(1+c)) · y[n-1]
 * ```
 *
 * For the matching high-pass (same pole, complementary numerator):
 *
 * ```
 *     y[n] = ( c / (1+c) ) · (x[n] − x[n-1])  +  ((c-1)/(1+c)) · y[n-1]
 * ```
 *
 * The 90 Hz HP has `c ≈ 156` so `(c-1)/(1+c) ≈ 0.9873` — a strong low-pass
 * influence on the IIR. The 14 kHz LP has `c ≈ 1.003` so `(c-1)/(1+c) ≈ 0.0013`
 * — the pole is very close to z=0 because the cutoff is close to Nyquist.
 *
 * Cutoffs follow the values used by fogleman/nes, Quietust's NESdev APU
 * docs, and the Nestopia/FCEUX emulator cores. Small variations (440 vs
 * 460 Hz, 14 vs 16 kHz LP) are audible but not large enough to revisit the
 * spec — a calibration knob can be added later if a regression test
 * against recorded NES hardware output motivates it.
 *
 * @param sampleRate Output sample rate in Hz. The filter is designed against
 *   this rate; changing it after construction invalidates the coefficients
 *   (re-create the filter rather than mutating).
 */
class AnalogFilter(private val sampleRate: Double) {

    // c = Fs / (π · fc). Larger c → cutoff further below Nyquist. For fc = Fs/2
    // (Nyquist) c = 2; for fc ≪ Fs, c → ∞.
    private val cHp90 = sampleRate / (PI * 90.0)
    private val cHp440 = sampleRate / (PI * 440.0)
    private val cLp14k = sampleRate / (PI * 14000.0)

    // Pre-computed per-stage coefficients (the difference-equation scalar forms).
    // For the LPF: a0 = 1/(1+c), a1 = (c-1)/(1+c), so y = a0·(x[n]+x[n-1]) + a1·y[n-1]
    // For the HPF: a0 = c/(1+c), a1 = (c-1)/(1+c), so y = a0·(x[n]-x[n-1]) + a1·y[n-1]
    private val lpA0 = 1.0 / (1.0 + cLp14k)
    private val lpA1 = (cLp14k - 1.0) / (1.0 + cLp14k)
    private val hp90A0 = cHp90 / (1.0 + cHp90)
    private val hp90A1 = (cHp90 - 1.0) / (1.0 + cHp90)
    private val hp440A0 = cHp440 / (1.0 + cHp440)
    private val hp440A1 = (cHp440 - 1.0) / (1.0 + cHp440)

    // IIR state — the previous output of each stage plus the previous input to
    // each HP stage (the HPs need x[n] − x[n-1] and the LP needs x[n] + x[n-1]).
    // Kept private-mutable rather than `@Volatile`: the APU is single-threaded
    // on the emulation thread, and the audio thread reads only the final
    // 16-bit output after this class returns. See [Apu.mixAndBuffer].
    private var hp90PrevOut = 0.0
    private var hp90PrevIn = 0.0
    private var hp440PrevOut = 0.0
    private var hp440PrevIn = 0.0
    private var lp14kPrevOut = 0.0
    private var lp14kPrevIn = 0.0

    /**
     * Feed one pre-filter sample and return one post-filter sample. Input is
     * unitless amplitude matching the rest of the audio path — the APU's
     * `mixAndBuffer` produces a value in roughly `[−1, 1]` before scaling to
     * 16-bit signed, so pass that value in here and scale the result out.
     */
    fun process(input: Double): Double {
        // Stage 1: 90 Hz HP. y[n] = a0·(x[n] − x[n-1]) + a1·y[n-1]
        val hp90 = hp90A0 * (input - hp90PrevIn) + hp90A1 * hp90PrevOut
        hp90PrevIn = input
        hp90PrevOut = hp90

        // Stage 2: 440 Hz HP.
        val hp440 = hp440A0 * (hp90 - hp440PrevIn) + hp440A1 * hp440PrevOut
        hp440PrevIn = hp90
        hp440PrevOut = hp440

        // Stage 3: 14 kHz LP. y[n] = a0·(x[n] + x[n-1]) + a1·y[n-1]
        val lp14k = lpA0 * (hp440 + lp14kPrevIn) + lpA1 * lp14kPrevOut
        lp14kPrevIn = hp440
        lp14kPrevOut = lp14k

        return lp14k
    }

    /**
     * Zero the IIR state. Called by [com.github.alondero.nestlin.Apu]'s
     * power-on / ROM-swap paths where the filter's previous capacitor
     * charges would otherwise bleed into the new game's first sample
     * (the brief DC transient at power-on).
     *
     * Deliberately not wired into save-state load: the filter is purely a
     * reproduction-stage analog model, not machine state, and a one-time
     * settling transient on save-load is inaudible. See issue #229.
     */
    fun reset() {
        hp90PrevOut = 0.0
        hp90PrevIn = 0.0
        hp440PrevOut = 0.0
        hp440PrevIn = 0.0
        lp14kPrevOut = 0.0
        lp14kPrevIn = 0.0
    }
}