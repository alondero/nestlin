package com.github.alondero.nestlin.cpu

/**
 * Exception thrown when an undocumented opcode is encountered while running
 * a test ROM (issue #192 cleanup).
 *
 * Previously this file also contained the `Opcodes` class (a `HashMap<Int,
 * Opcode>` dispatcher wrapping inline lambdas) and the `Opcode` class
 * (the lambda wrapper itself). Both were deleted in issue #192's
 * sealed-class refactor and replaced by `cpu/opcode/OpcodesRefactor.kt`
 * and the `cpu/opcode/Opcode` sealed hierarchy.
 *
 * `UnhandledOpcodeException` stays here because:
 *  - `Cpu.tick()` throws it when an unmapped opcode runs on a test ROM.
 *  - `GoldenLogTest` catches it as the expected exit of the nestest run.
 *  - `Logger.cpuTick()` throws it as part of its own exception path.
 */
class UnhandledOpcodeException(opcodeVal: Int) :
    Throwable("Opcode ${"%02X".format(opcodeVal)} not implemented")