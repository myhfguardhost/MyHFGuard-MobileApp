package com.vitalink.app.ui.screens

/** Count unique seconds actually played. Seeking to the end cannot unlock a reward. */
class WatchCoverage(initial: Set<Int> = emptySet()) {
    val seconds = initial.toMutableSet()
    private var previous = -1
    fun sample(position: Int, playing: Boolean) {
        if (playing && previous >= 0 && position - previous in 1..1500) {
            for (second in previous / 1000..position / 1000) seconds.add(second)
        }
        previous = if (playing) position else -1
    }
    fun complete(duration: Int) = duration > 0 && seconds.count { it * 1000 < duration } >= kotlin.math.ceil(duration / 1000.0 * .95).toInt()
}
