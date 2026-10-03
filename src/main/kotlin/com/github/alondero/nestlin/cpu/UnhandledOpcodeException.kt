package com.github.alondero.nestlin.cpu

/**
 * Thrown for an unmapped test-ROM opcode or an unsupported CPU trace formatter.
 * `Logger.cpuTick()` can throw even when the dispatcher implements the opcode.
 * `GoldenLogTest` currently catches that exception and compares only the emitted
 * prefix; see the nestest coverage gap in docs/PERFORMANCE.md.
 */
class UnhandledOpcodeException(opcodeVal: Int) :
    Throwable("Opcode ${"%02X".format(opcodeVal)} not implemented")
