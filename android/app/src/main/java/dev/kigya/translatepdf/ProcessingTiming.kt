package dev.kigya.translatepdf

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject

/** Bounded per-operation aggregates for private local performance diagnosis. */
internal class ProcessingTiming(
  private val clockMillis: () -> Long = SystemClock::elapsedRealtime,
  private val log: (String, String) -> Unit = { tag, message -> Log.i(tag, message) },
) {
  private data class Aggregate(var durationMillis: Long = 0, var maxMillis: Long = 0, var count: Int = 0)
  private val lock = Any()
  private val aggregates = linkedMapOf<String, Aggregate>()

  fun <T> measure(phase: String, action: () -> T): T {
    val started = clockMillis()
    try {
      return action()
    } finally {
      val elapsed = (clockMillis() - started).coerceAtLeast(0)
      synchronized(lock) {
        val aggregate = aggregates.getOrPut(phase) { Aggregate() }
        aggregate.durationMillis += elapsed
        aggregate.maxMillis = maxOf(aggregate.maxMillis, elapsed)
        aggregate.count++
      }
    }
  }

  fun flush() {
    val snapshot = synchronized(lock) {
      aggregates.map { (phase, value) ->
        JSONObject().put("phase", phase).put("durationMs", value.durationMillis)
          .put("maxDurationMs", value.maxMillis).put("count", value.count).toString()
      }.also { aggregates.clear() }
    }
    snapshot.forEach { message -> runCatching { log(TAG, message) } }
  }

  companion object {
    const val TAG = "FolivelaTiming"
  }
}
