package com.github.alondero.nestlin

import com.github.alondero.nestlin.cpu.UnhandledOpcodeException
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Paths

class GoldenLogTest {

    @Test // TODO: Tidy up when more of a regression test
    fun compareToGoldenLog() {
        val prevOut = System.out
        var nestlinOut = ByteArrayOutputStream()
        System.setOut(PrintStream(nestlinOut))
        try {
            Nestlin().apply {
                load(Paths.get("testroms/nestest.nes"))
                powerReset()
                enableLogging()
                start()
            }
        } catch (e: UnhandledOpcodeException) {
            //  Do nothing
        }

        System.setOut(prevOut)

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

        private fun split(line: String) = arrayOf(
                line.substring(0, 4),
                line.substring(6, 8),
                line.substring(9, 11),
                line.substring(12, 14),
                line.substring(16, 19),
                line.substring(20, 48),
                line.substring(50, 52),
                line.substring(55, 57),
                line.substring(60, 62),
                line.substring(65, 67),
                line.substring(71, 73))
                // Ignore cycles for now
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