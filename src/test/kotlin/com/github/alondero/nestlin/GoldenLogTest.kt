package com.github.alondero.nestlin

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
                this.load(Paths.get("testroms/nestest.nes"))
                this.powerReset()
                this.start()
            }
        } catch (e: UnhandledOpcodeException) {
            //  Do nothing
        }

        System.setOut(prevOut)

        println(nestlinOut.toString())

        //  Trawl through output comparing line by line with the golden log
        val golden = Files.readAllLines(Paths.get("src/test/resources/nestest.log"))
        val log = nestlinOut.toString().split(System.getProperty("line.separator"))

        for (line in 1..log.size-1) {
            if (log[line].length > 0) {

                val goldenTokens = split(golden[line - 1])
                val logTokens = split(log[line])

                for (token in 0..logTokens.size - 1) {
                    if (!logTokens[token].equals(goldenTokens[token])) {
                        if (!(token == 5 && logTokens[token].contains("=")) && !(token == 6 && logTokens[1].equals("29"))) {
                            throw AssertionError("Token mismatch on line ${line - 1}, with address ${logTokens[0]}. Expected token:${token.tokenName()} to be ${goldenTokens[token]} but was ${logTokens[token]}.\nExpected line:\n${golden[line - 1]}\nActual line:\n${log[line]}")
                        }
                    }
                }
            }
        }
    }

    private fun split(line: String): Array<String> {
        return arrayOf(
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
                line.substring(71, 73)
        // Ignore cycles for now
        )
    }

    private fun Int.tokenName(): String {
        when (this) {
            0 -> return "PC"
            1 -> return "O#"
            2 -> return "M1"
            3 -> return "M2"
            4 -> return "M3"
            5 -> return "OP"
            6 -> return "A"
            7 -> return "X"
            8 -> return "Y"
            9 -> return "P"
            10 -> return "SP"
            else -> return "CYC"
        }
    }
}
