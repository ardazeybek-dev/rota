package dev.rota.cli

import com.github.ajalt.clikt.testing.test
import com.github.ajalt.clikt.core.subcommands
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest {
    private fun cli() = RotaCli().subcommands(Solve(), Check(), Init(), Bench())

    // Clikt's test() splits like a shell, so Windows backslashes need to become slashes.
    private val java.nio.file.Path.arg get() = toString().replace('\\', '/')

    @Test
    fun initThenSolve() {
        val dir = Files.createTempDirectory("rota")
        val file = dir.resolve("flat.rota")
        assertEquals(0, cli().test("init flat -o ${file.arg}").statusCode)
        val r = cli().test("solve ${file.arg} --no-color --ics ${dir.resolve("ics").arg}")
        assertEquals(0, r.statusCode, r.stderr)
        assertTrue(r.stdout.contains("fully staffed"))
        assertTrue(Files.list(dir.resolve("ics")).count() >= 3)
    }

    @Test
    fun unfilledRotaExitsWithTwo() {
        val file = Files.createTempFile("holiday", ".rota")
        cli().test("init impossible -o ${file.arg} --force")
        val r = cli().test("solve ${file.arg} --no-color")
        assertEquals(2, r.statusCode)
        assertTrue(r.stdout.contains("Unfilled shifts"))
    }

    @Test
    fun checkReportsErrorsWithCaret() {
        val file = Files.createTempFile("bad", ".rota")
        Files.writeString(file, "period 2026-11\nshift a daily 9-17\npersn Ana\n")
        val r = cli().test("check ${file.arg}")
        assertEquals(1, r.statusCode)
        assertTrue(r.stderr.contains("did you mean 'person'") && r.stderr.contains("^^^^^"), r.stderr)
    }
}
