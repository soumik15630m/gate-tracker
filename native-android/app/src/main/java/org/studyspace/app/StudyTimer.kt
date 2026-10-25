package org.studyspace.app

import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

@Serializable data class StudyTimer(val id: String, val accumulatedMs: Long = 0, val startedAt: Long? = null) {
    fun elapsed(now: Long = System.currentTimeMillis()) = accumulatedMs + (startedAt?.let { (now - it).coerceAtLeast(0) } ?: 0)
    fun pause(now: Long = System.currentTimeMillis()) = copy(accumulatedMs = elapsed(now), startedAt = null)
    fun resume(now: Long = System.currentTimeMillis()) = if (startedAt == null) copy(startedAt = now) else this
    fun minutes() = (elapsed() / 60000.0).roundToLong().coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
fun timerClock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return listOf(seconds / 3600, seconds / 60 % 60, seconds % 60).joinToString(":") { it.toString().padStart(2, '0') }
}
