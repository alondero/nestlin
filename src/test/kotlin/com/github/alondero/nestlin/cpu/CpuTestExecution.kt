package com.github.alondero.nestlin.cpu

/** Execute the instruction or interrupt that begins on the next CPU cycle. */
internal fun Cpu.executeNext() {
    tick()
    while (executionInFlight) tick()
}

/** Finish work whose first cycle has already run (instruction, interrupt, or reset sequence). */
internal fun Cpu.finishExecution() {
    while (executionInFlight) tick()
}
