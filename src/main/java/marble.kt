@file:JvmName("Marble")

import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import com.xenomachina.argparser.mainBody
import org.knowm.xchart.BitmapEncoder
import org.knowm.xchart.CategoryChartBuilder
import java.io.File
import kotlin.concurrent.thread

private data class TeamStanding(
    val name: String,
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
private typealias TeamStat = Pair<String, List<Double>>

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

private fun approximate(startStanding: Standings, remainingCount: Int, scoring: Scoring, iterations: Int, threadCount: Int): List<TeamStat> {
    fun emptyData() = startStanding.map { IntArray(startStanding.size) }

    val iterationsByThread = iterations / threadCount

    val threadData: List<Pair<Thread, List<IntArray>>> = (1 .. threadCount).map { t ->
        val posCount = emptyData()
        val thread = thread(start = true) {
            val localIterations =
                if (t == 1) (iterations - (threadCount - 1) * iterationsByThread) else iterationsByThread

            (1 .. localIterations).forEach { i ->
                if (i % 10_000 == 0) {
                    println("Executor $t: iteration $i/$localIterations")
                }

                val finalStanding = simulateRemaining(startStanding, scoring, remainingCount)
                toPosition(finalStanding).forEachIndexed { rank, teamIndex ->
                    posCount[teamIndex][rank]++
                }
            }

        }
        thread to posCount
    }

    threadData.forEach { it.first.join() }
    println("Approximation done!")

    val finalPosCount = emptyData()
    threadData.forEach { (_, posCountPartition) ->
        posCountPartition.forEachIndexed { teamIndex, posCount ->
            posCount.forEachIndexed { rank, value ->
                finalPosCount[teamIndex][rank] += value
            }
        }
    }
    return startStanding.zip(finalPosCount) { initial, posCount ->
        initial.name to posCount.map { it.toDouble() / iterations }
    }
}

private fun standingFromFile(fileString: String): Standings {
    return File(fileString).readLines()
        .filterNot { it.trimStart().startsWith("#") }
        .map { line ->
            val split = line.split(",").map(String::trim)
            TeamStanding(
                name = split[0],
                golds = split[1].toInt(),
                silvers = split[2].toInt(),
                bronzes = split[3].toInt(),
                points = split[4].toInt()
            )
        }
}

private fun createVisualization(fileName: String, teamName: String, teamStat: TeamStat) {
    val chart = CategoryChartBuilder().apply {
        title("Probability distribution of $teamName's final place")
        xAxisTitle("Final place")
        yAxisTitle("Probability")
    }.build()

    chart.addSeries("possibilities", teamStat.second.mapIndexed { i, _ -> (i + 1).toDouble() }, teamStat.second)
    chart.styler.isLegendVisible = false

    BitmapEncoder.saveBitmapWithDPI(chart, fileName, BitmapEncoder.BitmapFormat.PNG, 300)
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
            val standing = standingFromFile(standingFile)
            val approxResult = approximate(standing, remainingCount, defaultScoring, iterations, threadCount)
            approxResult.forEach { teamStat ->
                val teamName = teamStat.first
                println(teamStat.second.joinToString(prefix = "$teamName:") {
                    String.format("%.6f", 100 * it) + "%"
                })
                createVisualization(outDir + File.separator + "$teamName.png", teamName, teamStat)
            }
        }
    }
}