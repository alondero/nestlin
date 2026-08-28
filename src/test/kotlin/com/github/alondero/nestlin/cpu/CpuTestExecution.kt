package com.github.alondero.nestlin.cpu

/**
 * Execute the work that begins on the *next* CPU cycle — the opcode or
 * interrupt dispatch has not yet been started. Equivalent to `tick()` once
 * followed by [finishExecution]; use this when you want to advance the
 * machine through one complete dispatch with a single call.
 */
internal fun Cpu.executeNext() {
    tick()
    while (executionInFlight) tick()
}

/**
 * Drain work whose *first* cycle has already run. Use after an explicit
 * [Cpu.tick] when you want to assert state mid-instruction (e.g. "after the
 * opcode fetch, workCyclesLeft is N-1") without accidentally starting the
 * next dispatch. Also valid immediately after [Cpu.reset] / [Cpu.softReset]
 * to walk the seven-cycle reset sequence to completion.
 */
internal fun Cpu.finishExecution() {
    while (executionInFlight) tick()
}
