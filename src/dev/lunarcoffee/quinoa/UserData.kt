package dev.lunarcoffee.quinoa

import com.google.gson.annotations.SerializedName

data class ScheduledEvent(
    val action: String,
    val tag: String,
    @SerializedName("start_date") val startTime: String,
    val length: Int,
    val repeats: String
)

// One time slot is 5 minutes.
data class EventTimeSlot(val event: ScheduledEvent?, val which: Int) {
    val minutesSince = which * 5
}

typealias ProbabilityTimeSlot = Double

// These should contain 288 elements.
typealias DaySchedule = List<EventTimeSlot>
typealias DayProbability = List<ProbabilityTimeSlot>

data class UserData(
    // These should contain 30 elements.
    val monthSchedule: List<DaySchedule>,
    val monthProbability: MutableMap<String, List<DayProbability>>,

    val subjectAvgLength: MutableMap<String, Int>,

    // List of 3 elements to prevent duplicates after an intermediate suggestion.
    var recentIndices: List<Int>
) {
    val id = 0
}
