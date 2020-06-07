package dev.lunarcoffee.quinoa

import com.google.gson.annotations.SerializedName
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.gson.gson
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.*
import io.ktor.server.netty.EngineMain
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.eq
import org.litote.kmongo.reactivestreams.KMongo
import org.slf4j.event.Level
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.random.Random

// -- Kotlin linguine with unmaintainable code sauce and a side of long file salad
//    Bon appetit  :)

val client = KMongo.createClient().coroutine
val database = client.getDatabase("Quinoa")
val dataCol = database.getCollection<UserData>() // .apply { runBlocking { deleteMany() } } // TODO:

data class GetSuggestResponse(
    val tag: String,
    @SerializedName("start_date") val startDate: String,
    val length: Int,
    val repeats: String
)

data class PostResponse(val ok: Boolean, val err: String)

data class Events(val events: List<ScheduledEvent>)

fun main(args: Array<String>) = EngineMain.main(args)

@Suppress("unused")
fun Application.module() {
    install(AutoHeadResponse)
    install(CallLogging) { level = Level.INFO }
    install(CORS) { anyHost() }
    install(ContentNegotiation) { gson() }

    routing {
        route("/suggest") {
            get {
                val params = call.request.queryParameters

                val action = params["action"] ?: return@get call.respondErrQP("action")
                val by = params["by"]
                    ?.ifEmpty { null }
                    ?.run { timeToSlotIndex(LocalDateTime.parse(this, DateTimeFormatter.ISO_DATE_TIME)) }
                val after = params["after"]
                    ?.ifEmpty { null }
                    ?.run { timeToSlotIndex(LocalDateTime.parse(this, DateTimeFormatter.ISO_DATE_TIME)) }

                val tag = inferTagsByKeywords(action)
                val startDate = inferStartDate(tag, by, after).format(DateTimeFormatter.ISO_DATE_TIME)
                val length = inferLength(tag) * 5
                val repeats = "none"

                val response = GetSuggestResponse(tag, startDate, length, repeats)
                call.respond(response)
            }

            post {
                val params = call.request.queryParameters

                val tag = params["tag"] ?: return@post call.respondErrQP("tag")
                val startDate = LocalDateTime.parse(
                    params["start_date"] ?: return@post call.respondErrQP("start_date"),
                    DateTimeFormatter.ISO_DATE_TIME
                )
                val length = (params["length"] ?: return@post call.respondErrQP("length")).toInt() / 5
//                val repeats = params["repeats"] ?: return@post call.respondErrQP("repeats")
                val accepted = (params["accepted"] ?: return@post call.respondErrQP("accepted")) == "true"

                withData { data ->
                    val prob = data.monthProbability[tag]!!.flatten()
                    val slotIndex = timeToSlotIndex(startDate)

                    val newProb = adjustProbability(prob, slotIndex, accepted)
                    data.monthProbability[tag] = newProb.chunked(288)
                    data.subjectAvgLength[tag] = (data.subjectAvgLength[tag]!! + length) / 2

                    data
                }
                call.respondOk()
            }
        }

        route("/schedule") {
            get {
                var events = emptyList<ScheduledEvent>()
                withData { data ->
                    events = data
                        .monthSchedule
                        .flatten()
                        .mapNotNull { it.event }
                        .groupBy { it.action }
                        .map { it.value.first() }
                        .map { it.copy(length = it.length * 5) }
                    data
                }
                call.respond(Events(events))
            }

            post {
                val params = call.request.queryParameters

                val action = params["action"] ?: return@post call.respondErrQP("action")
                val tag = params["tag"] ?: return@post call.respondErrQP("tag")
                val startDate = LocalDateTime.parse(
                    params["start_date"] ?: return@post call.respondErrQP("start_date"),
                    DateTimeFormatter.ISO_DATE_TIME
                )
                val length = (params["length"] ?: return@post call.respondErrQP("length")).toInt() / 5
                val repeats = params["repeats"] ?: return@post call.respondErrQP("repeats")

                withData { data ->
                    val slotIndex = timeToSlotIndex(startDate)
                    val newSchedule = data.monthSchedule.flatten().toMutableList()
                    val startTime = startDate.format(DateTimeFormatter.ISO_DATE_TIME)
                    for (i in slotIndex..slotIndex + length) {
                        newSchedule[i] =
                            EventTimeSlot(ScheduledEvent(action, tag, startTime, length, repeats), i + slotIndex)
                    }
                    data.copy(monthSchedule = newSchedule.chunked(288))
                }
                call.respondOk()
            }
        }

        get("reset") {
            if (dataCol.deleteMany().deletedCount < 1) {
                call.respond(PostResponse(false, "Failed to reset DB collection"))
            } else {
                call.respondOk()
            }
        }
    }
}

private suspend fun ApplicationCall.respondOk() = respondText("OK")
private suspend fun ApplicationCall.respondErrQP(error: String) =
    respondText("Missing `$error` query parameter")

// ------------------------------------------------------------------------------------------------

private val keywords = mapOf(
    "school" to listOf("study", "essay", "test", "quiz", "evaluation", "exam", "learn", "school"),
    "work" to listOf("meeting", "supervise", "report", "coworker"),
    "leisure" to listOf("play", "fun", "enjoy", "relax", "day off")
)

private fun inferTagsByKeywords(action: String): String {
    val normalized = action.replace("-", "").toLowerCase()
    return keywords
        .map { (tag, words) -> Pair(tag, words.count { normalized.contains(it) }) }
        .filter { (_, count) -> count > 0 }
        .sortedBy { it.second }
        .firstOrNull()
        ?.first
        ?: "task"
}

private suspend fun inferStartDate(tag: String, by: Int?, after: Int?): LocalDateTime {
    var time = LocalDateTime.now()
    withData { data ->
        val prob = data.monthProbability[tag]!!.flatten()

        val initial = prob.all { it == 0.5 }
        val s = prob.slice((after ?: 0)..(by ?: (after ?: 0) + 2016))
        val timeSlot = (after ?: 0) + if (initial)
            6 + Random.nextInt(3) * 3
        else
            s.indexOf(s.withIndex().maxBy { (i, p) -> if (i in data.recentIndices) -1.0 else p }!!.value)

        val slot = timeSlot.coerceIn(prob.indices)
        data.recentIndices = data.recentIndices.drop(3) + listOf(slot - 1, slot, slot + 1)
        time = slotToTime(data.monthSchedule.flatten()[slot])

        data
    }
    return time
}

private fun adjustProbability(prob: List<ProbabilityTimeSlot>, index: Int, high: Boolean): List<ProbabilityTimeSlot> {
    val modifyFunc = { x: Double, d: Int ->
        val updated = (x + (if (high) 1.0 else -1.0) / (d + 3)).coerceAtLeast(0.0)
        updated.coerceIn((x - 0.3).coerceAtLeast(0.0), (x + 0.3).coerceAtMost(1.0))
    }

    val newProb = prob.toMutableList()
    for (i in prob.indices) {
        val d = abs(i - index)
        val newWeight = modifyFunc(prob[i], d)
        newProb[i] = newWeight
    }

    println("\nold: $prob") // TODO:
    println("new: $newProb\n")

    return newProb
}

// Length in minutes.
private suspend fun inferLength(tag: String): Int {
    var length = 0
    withData { data ->
        length = data.subjectAvgLength[tag]!!
        data
    }
    return length
}

// ------------------------------------------------------------------------------------------------

// ------------------------------------------------------------------------------------------------

private suspend fun withData(op: (UserData) -> UserData) {
    val data = dataCol.find().first()
        ?: suspend {
            val data = UserData(
                List(30) { day -> List(288) { EventTimeSlot(null, it + day * 288) } },
                mutableMapOf(
                    "work" to List(30) { List(288) { 0.5 } },
                    "school" to List(30) { List(288) { 0.5 } },
                    "leisure" to List(30) { List(288) { 0.5 } },
                    "task" to List(30) { List(288) { 0.5 } }
                ),
                mutableMapOf(
                    "work" to 6,
                    "school" to 6,
                    "leisure" to 6,
                    "task" to 6
                ),
                List(9) { 0 }
            )
            dataCol.insertOne(data)
            data
        }()
    val newData = op(data)
    dataCol.updateOne(UserData::id eq data.id, newData)
}

private fun slotToTime(slot: EventTimeSlot): LocalDateTime {
    val mins = slot.minutesSince
    return LocalDateTime
        .now()
        .withDayOfYear(158 + mins / 1440)
        .withHour(mins / 60 % 24)
        .plusHours(7)
        .withMinute(mins % 60)
        .withSecond(0)
        .withNano(0)
}

private val sub = LocalDateTime
    .now()
    .withDayOfMonth(6)
    .withHour(7)
    .withMinute(0)
    .withSecond(0)
    .withNano(0)

private fun timeToSlotIndex(time: LocalDateTime) = ChronoUnit.MINUTES.between(sub, time).toInt() / 5
