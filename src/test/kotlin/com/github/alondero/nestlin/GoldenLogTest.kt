package com.github.alondero.nestlin

import com.github.alondero.nestlin.cpu.UnhandledOpcodeException
import com.github.alondero.nestlin.testutil.TestRoms
import com.github.alondero.nestlin.testutil.assertThrowsWithMessage
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Paths

class GoldenLogTest {

    @Test // TODO: Tidy up when more of a regression test
    fun compareToGoldenLog() {
        val nestlinOut = ByteArrayOutputStream()
        withRedirectedStdout(nestlinOut) {
            try {
                Nestlin().apply {
                    loadBytes(TestRoms.nestestBytes())
                    powerReset()
                    enableLogging()
                    start()
                }
            } catch (e: UnhandledOpcodeException) {
                //  Do nothing
            }
        }

        println("START\n${nestlinOut}\nEND")

        //  Trawl through output comparing line by line with the golden log
        val log = LogComparison(golden = Files.readAllLines(Paths.get("src/test/resources/nestest.log")), currLog = nestlinOut.toString().split(System.getProperty("line.separator")))

        (0..log.size - 1)
                .filter { !log.emptyLine(it) }
                .map { log.lineComparison(it) }
                .forEach {
                    line -> line.mismatchedTokens().firstOrNull()?.let {
                        throw AssertionError("Token mismatch on line ${line.line}, with address ${line.currLogTokens[0]}. Expected token:${it.tokenName()} to be ${line.goldenTokens[it]} but was ${line.currLogTokens[it]}.\nExpected line:\n${line.goldenLine}\nActual line:\n${line.currLogLine}")
                    }
                }
    }

    /**
     * Regression guard for issue #14.
     *
     * Redirects System.out to [buffer] for the duration of [block] and
     * ALWAYS restores the original stream — even when [block] throws an
     * exception the caller's catch block doesn't match.
     *
     * The previous in-test pattern restored stdout only AFTER the
     * try/catch, so any uncaught exception (e.g. a malformed-ROM
     * ArrayIndexOutOfBoundsException thrown out of `Nestlin().start()`)
     * would leave System.out pointing at a dead ByteArrayOutputStream for
     * the rest of the JVM's life, silently swallowing every subsequent
     * test's stdout.
     */
    private inline fun withRedirectedStdout(buffer: ByteArrayOutputStream, block: () -> Unit) {
        val prevOut = System.out
        System.setOut(PrintStream(buffer))
        try {
            block()
        } finally {
            System.setOut(prevOut)
        }
    }

    /**
     * Regression test for issue #14: the redirect/restore must survive
     * an exception the caller's `catch` block doesn't match. Without the
     * `finally` inside [withRedirectedStdout], the original System.out
     * stays pointed at a dead buffer for the rest of the JVM — which
     * silently breaks every subsequent test's output and was the actual
     * P0 hazard in the bug report.
     *
     * The thrown type is deliberately NOT [UnhandledOpcodeException] —
     * that's the only catch in [compareToGoldenLog], so any other throw
     * is the exact scenario that previously leaked the redirected stream.
     */
    @Test
    fun redirectedStdoutIsRestoredOnUncaughtException() {
        val originalOut = System.out
        val buffer = ByteArrayOutputStream()

        assertThrowsWithMessage<ArrayIndexOutOfBoundsException>("simulated bad ROM") {
            withRedirectedStdout(buffer) {
                throw ArrayIndexOutOfBoundsException("simulated bad ROM")
            }
        }

        // The exception escaped `block`, but `finally` must have run anyway.
        // Pre-fix: this assertion fails — System.out is still the buffer.
        assertSame(
            originalOut,
            System.out,
            "System.out should be restored even when the redirected block throws an uncaught exception",
        )
    }

    class LogComparison(val golden: List<String>, val currLog: List<String>) {
        val size = currLog.size

        fun lineComparison(line: Int) = LogLine(line, golden[line], currLog[line])
        fun emptyLine(line: Int) = currLog[line].isEmpty()
    }

    class LogLine(val line: Int, val goldenLine: String, val currLogLine: String) {
        val goldenTokens = split(goldenLine)
        val currLogTokens = split(currLogLine)

        fun mismatchedTokens() = (0..currLogTokens.size - 1)
                .filter { !currLogTokens[it].equals(goldenTokens[it]) }
                .filter { !(it == 5 && currLogTokens[it].contains("=")) }
                .filter { !(it == 6 && currLogTokens[1].equals("29")) }

        // nestest.log columns are fixed-width — see the format string in
        // Logger.cpuTick(). The cycle column lives at positions 74..80:
        // "CYC:" at 74-77 and a 3-char right-aligned value at 78-80.
        // nestest's largest cycle value is well under 1000, so 3 chars
        // never overflows for this log; if the value ever grows, switch
        // to a numeric comparison instead of a string one.
        private fun split(line: String) = arrayOf(
                line.substring(0, 4),    // PC
                line.substring(6, 8),    // O# (opcode byte)
                line.substring(9, 11),   // M1 (operand byte 1)
                line.substring(12, 14),  // M2 (operand byte 2)
                line.substring(16, 19),  // M3 (operand byte 3)
                line.substring(20, 48),  // OP (mnemonic display)
                line.substring(50, 52),  // A
                line.substring(55, 57),  // X
                line.substring(60, 62),  // Y
                line.substring(65, 67),  // P
                line.substring(71, 73),  // SP
                line.substring(74, 81))  // CYC:NNN (cycle column, 7 chars)
    }

    private fun Int.tokenName() =
        when (this) {
            0 -> "PC"
            1 -> "O#"
            2 -> "M1"
            3 -> "M2"
            4 -> "M3"
            5 -> "OP"
            6 -> "A"
            7 -> "X"
            8 -> "Y"
            9 -> "P"
            10 -> "SP"
            else -> "CYC"
        }
}