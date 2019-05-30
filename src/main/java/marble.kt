@file:JvmName("Marble")

import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import com.xenomachina.argparser.mainBody
import org.knowm.xchart.BitmapEncoder
import org.knowm.xchart.CategoryChartBuilder
import org.knowm.xchart.style.Styler
import java.awt.Color
import java.io.File
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private data class Team(
    val name: String,
    val color: Color
)

private data class TeamStanding(
    val team: Team,
    val points: Int,
    val golds: Int = 0,
    val silvers: Int = 0,
    val bronzes: Int = 0
) {
    val medals: Int
        get() = golds + silvers + bronzes

    fun updated(position: Int, scoring: List<Int>) = copy(
        points = points + scoring[position - 1],
        golds = golds + if (position == 1) 1 else 0,
        silvers = silvers + if (position == 2) 1 else 0,
        bronzes = bronzes + if (position == 3) 1 else 0
    )
}

private typealias Standings = List<TeamStanding>
private typealias Scoring = List<Int>
private typealias TeamStat = Pair<Team, List<Double>>

private val defaultScoring
        = listOf(25, 20, 15, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)

// result[n] = rank of n^th team
private fun simulateEvent(teamCount: Int): List<Int> = (1 .. teamCount).shuffled()

private fun simulatePoints(standing: Standings, scoring: Scoring): Standings {
    val eventResult = simulateEvent(scoring.size)
    return standing.mapIndexed { teamIndex, teamStanding ->
        teamStanding.updated(eventResult[teamIndex], scoring)
    }
}

private tailrec fun simulateRemaining(standing: Standings, scoring: Scoring, remainingCount: Int): Standings =
    if (remainingCount > 0) {
        simulateRemaining(simulatePoints(standing, scoring), scoring, remainingCount - 1)
    } else {
        standing
    }

private val standingSorter: Comparator<Pair<TeamStanding, Int>> =
    compareBy<Pair<TeamStanding, Int>> { it.first.points }.thenBy { it.first.medals }.reversed()

// result[n] = index of team at the n^th position
private fun toPosition(standing: Standings): List<Int> =
    standing.mapIndexed { index, teamStanding -> teamStanding to index }
        .sortedWith(standingSorter)
        .map { it.second }

private class Approximation(val teamCount: Int) {

    private var iterations = 0
    private val perTeamRanks = ArrayList<IntArray>(teamCount)

    companion object {
        fun collect(approximations: Collection<Approximation>): Approximation =
            if (approximations.size == 1) {
                approximations.first()
            } else {
                Approximation(approximations.first().teamCount).apply {
                    approximations.forEach { part ->
                        iterations += part.iterations
                        perTeamRanks.forEachIndexed { teamIndex, ranking ->
                            part.perTeamRanks[teamIndex].forEachIndexed { rank, value ->
                                ranking[rank] += value
                            }
                        }
                    }
                }
            }
    }

    init {
        repeat(teamCount) {
            perTeamRanks += IntArray(teamCount)
        }
    }

    fun update(rankings: List<Int>) {
        iterations++
        rankings.forEachIndexed { index, teamIndex ->
            perTeamRanks[teamIndex][index]++
        }
    }

    fun teamStats(teams: List<Team>): List<TeamStat> = perTeamRanks.zip(teams) { rank, team ->
        team to rank.map { it.toDouble() / iterations }
    }

}

private fun approximate(startStanding: Standings, remainingCount: Int, scoring: Scoring, iterations: Int, threadCount: Int): Approximation {
    val iterationsByThread = iterations / threadCount
    val teamCount = startStanding.size

    val threadData: List<Pair<Thread, Approximation>> = (1 .. threadCount).map { t ->
        val approximation = Approximation(teamCount)
        val thread = thread(start = true) {
            val localIterations =
                if (t == 1) (iterations - (threadCount - 1) * iterationsByThread) else iterationsByThread

            (1 .. localIterations).forEach { i ->
                if (i % 10_000 == 0) {
                    println("Executor $t: iteration $i/$localIterations")
                }

                val finalStanding = simulateRemaining(startStanding, scoring, remainingCount)
                approximation.update(toPosition(finalStanding))
            }

        }
        thread to approximation
    }

    threadData.forEach { it.first.join() }
    println("Approximation done!")

    return Approximation.collect(threadData.map { it.second })
}

private fun standingFromFile(fileString: String): Standings {
    fun team(name: String, hex: String?) = Team(
        name = name,
        color = if (!hex.isNullOrBlank()) {
            Color.decode(hex)
        } else {
            val random = Random(name.hashCode().toLong())
            Color(random.nextFloat(), random.nextFloat(), random.nextFloat())
        }
    )

    return File(fileString).readLines()
        .filterNot { it.trimStart().startsWith("#") }
        .map { line ->
            val split = line.split(",").map(String::trim)
            TeamStanding(
                team = team(split[0], split.getOrNull(5)),
                golds = split[1].toInt(),
                silvers = split[2].toInt(),
                bronzes = split[3].toInt(),
                points = split[4].toInt()
            )
        }
}

private fun createTeamVisualization(fileName: String, teamName: String, teamStat: TeamStat) {
    val chart = CategoryChartBuilder().apply {
        title("Probability distribution of $teamName's final place")
        xAxisTitle("Final place")
        yAxisTitle("Probability")
    }.build()

    chart.styler.apply {
        isLegendVisible = false
        antiAlias = true
        isToolTipsEnabled = true
        isToolTipsAlwaysVisible = true
        toolTipType = Styler.ToolTipType.yLabels
        yAxisDecimalPattern = "#.##%"
        seriesColors = arrayOf(teamStat.first.color)
    }

    chart.addSeries("possibilities", teamStat.second.mapIndexed { i, _ -> (i + 1).toDouble() }, teamStat.second)

    BitmapEncoder.saveBitmapWithDPI(chart, fileName, BitmapEncoder.BitmapFormat.PNG, 300)
}

private fun createSplitVisualization(fileName: String, namedApproximation: List<TeamStat>) {
    val chart = CategoryChartBuilder().apply {
        title("Probability distribution of final positions by team")
        xAxisTitle("Final place")
        yAxisTitle("Probability of each team")
    }.build()

    chart.styler.apply {
        isStacked = true
        antiAlias = true
        yAxisDecimalPattern = "#.##%"
        seriesColors = namedApproximation.map { (team, _) -> team.color }.toTypedArray()
    }

    val xValues = (1 .. namedApproximation.size).map { it.toDouble() }.toDoubleArray()
    namedApproximation.forEach { (team, teamData) ->
        chart.addSeries(team.name, xValues, teamData.toDoubleArray())
    }

    BitmapEncoder.saveBitmapWithDPI(chart, fileName, BitmapEncoder.BitmapFormat.PNG, 300)
}

private fun visualize(namedApprox: List<TeamStat>, outDir: String, await: Boolean = true) {
    val executor = Executors.newCachedThreadPool()

    executor.submit {
        createSplitVisualization(outDir + File.separator + "Combined.png", namedApprox)
    }

    namedApprox.forEach { teamStat ->
        val teamName = teamStat.first.name
        executor.submit {
            createTeamVisualization(outDir + File.separator + "$teamName.png", teamName, teamStat)
        }
    }

    executor.shutdown()

    if (await) {
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
    }
}


class Arguments(parser: ArgParser) {

    val standingFile by parser.storing("-f", "--standings-file", help = "File to load current standings from").default("standings.txt")
    val remainingCount by parser.storing("-r", "--remains", help = "Number of remaining events", transform = String::toInt).default(1)
    val threadCount by parser.storing("-t", "--threads", help = "Number of CPU threads to use for the simulation", transform = String::toInt).default(4)
    val iterations by parser.storing("-i", "--iterations", help = "Number of iterations to run", transform = String::toInt).default(1_000_000)
    val outDir by parser.storing("-o", "--out-dir", help = "Location to store the created charts").default("./")

}

fun main(args: Array<String>) {
    mainBody {
        ArgParser(args).parseInto(::Arguments).run {
            if (!File(outDir).isDirectory) {
                print("Output director '$outDir' not found, attempting to create it... ")
                if (File(outDir).mkdir()) {
                    println("success!")
                } else {
                    println("error!\nExiting")
                    return@mainBody
                }
            }

            val standing = standingFromFile(standingFile)
            val approximation = approximate(standing, remainingCount, defaultScoring, iterations, threadCount)
            val teams = standing.map(TeamStanding::team)
            val namedApprox = approximation.teamStats(teams)

            namedApprox.forEach { (teamName, teamStat) ->
                println(teamStat.joinToString(prefix = "$teamName: ") {
                    String.format("%.6f", 100 * it) + "%"
                })
            }

            println("Creating charts...")
            visualize(namedApprox, outDir)
            println("All done!")
        }
    }
}