package dev.rota.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import dev.rota.Examples
import dev.rota.RotaEngine
import dev.rota.export.Csv
import dev.rota.export.Ics
import dev.rota.export.Markdown
import dev.rota.parse.RotaParseException
import dev.rota.parse.RotaParser
import dev.rota.solve.Plan
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class RotaCli : CliktCommand(name = "rota") {
    override fun help(context: Context) =
        "Fair shift, on-call and chore rotas — solved exactly as a min-cost flow."

    override fun run() = Unit
}

private fun readSource(file: String): String =
    if (file == "-") System.`in`.readBytes().toString(Charsets.UTF_8) else Path.of(file).readText()

class Solve : CliktCommand() {
    override fun help(context: Context) = "Solve a .rota file and print the plan. Exits with 2 when shifts stay unfilled."

    private val file by argument(help = "rota file, or - for stdin")
    private val format by option("-f", "--format", help = "output format").choice("table", "md", "json", "csv").default("table")
    private val icsDir by option("--ics", help = "also write one .ics calendar per person into this directory").path(canBeFile = false)
    private val certify by option("--certify", help = "prove optimality with a negative-cycle check (slower)").flag()
    private val noColor by option("--no-color", help = "plain output (also honours NO_COLOR)").flag()

    override fun run() {
        val source = readSource(file)
        val plan = try {
            RotaEngine.plan(source, certify = certify)
        } catch (e: RotaParseException) {
            echo(Diagnostics.render(e, file, color = useColor()), err = true)
            throw ProgramResult(1)
        }
        plan.warnings.forEach { echo("warning: $it", err = true) }
        when (format) {
            "json" -> echo(RotaEngine.prettyJson.encodeToString(Plan.serializer(), plan))
            "csv" -> echo(Csv.render(plan), trailingNewline = false)
            "md" -> echo(Markdown.render(plan))
            else -> echo(Terminal(useColor()).render(plan))
        }
        icsDir?.let { dir ->
            dir.createDirectories()
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
            for (p in plan.people.filter { it.assigned > 0 }) {
                val out = dir.resolve(p.name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".ics")
                out.writeText(Ics.render(plan, p.name, stamp))
                echo("wrote $out", err = true)
            }
        }
        if (!plan.complete) throw ProgramResult(2)
    }

    private fun useColor() = !noColor && System.getenv("NO_COLOR") == null && System.console() != null
}

class Check : CliktCommand() {
    override fun help(context: Context) = "Validate a .rota file without solving it."

    private val file by argument(help = "rota file, or - for stdin")

    override fun run() {
        try {
            val r = RotaParser.parse(readSource(file))
            r.warnings.forEach { echo("warning: $it", err = true) }
            echo("ok: ${r.rota.people.size} people, ${r.rota.shifts.size} shifts, ${r.rota.start} → ${r.rota.end}")
        } catch (e: RotaParseException) {
            echo(Diagnostics.render(e, file, color = System.console() != null && System.getenv("NO_COLOR") == null), err = true)
            throw ProgramResult(1)
        }
    }
}

class Init : CliktCommand() {
    override fun help(context: Context) = "Write an example rota file to start from: ${Examples.all.joinToString { it.id }}."

    private val example by argument(help = "example to copy").choice(*Examples.all.map { it.id }.toTypedArray()).default("oncall")
    private val output by option("-o", "--output", help = "file to write").path().default(Path.of("team.rota"))
    private val force by option("--force", help = "overwrite an existing file").flag()

    override fun run() {
        if (output.exists() && !force) {
            echo("$output already exists (use --force to overwrite)", err = true)
            throw ProgramResult(1)
        }
        output.writeText(Examples.all.first { it.id == example }.source + "\n")
        echo("wrote $output — edit it, then run: rota solve $output")
    }
}

class Bench : CliktCommand() {
    override fun help(context: Context) = "Solve a synthetic team rota and report timing."

    private val people by option("--people", help = "team size").int().default(40)
    private val weeks by option("--weeks", help = "rota length in weeks").int().default(13)
    private val runs by option("--runs", help = "timed runs after warm-up").int().default(5)

    override fun run() {
        val source = Synthetic.rota(people, weeks)
        repeat(3) { RotaEngine.plan(source) }
        val times = (1..runs).map { RotaEngine.plan(source) }
        val plan = times.last()
        val ms = times.map { it.stats.millis }.sorted()
        echo("${plan.stats.units} shift-person units · ${plan.stats.nodes} nodes · ${plan.stats.arcs} arcs · ${plan.stats.augmentations} augmenting paths")
        echo("median %.1f ms (min %.1f, max %.1f) over $runs runs".format(ms[ms.size / 2], ms.first(), ms.last()))
        echo("unfilled: ${plan.gaps.sumOf { it.missing }} · objective ${plan.objective.total}")
    }
}

fun main(args: Array<String>) = RotaCli().subcommands(Solve(), Check(), Init(), Bench()).main(args)
