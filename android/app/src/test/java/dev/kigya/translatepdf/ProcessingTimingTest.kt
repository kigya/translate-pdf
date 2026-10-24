package dev.kigya.translatepdf

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = android.app.Application::class)
class ProcessingTimingTest {
  @Test
  fun aggregatesActualDurationsAndFlushesOnlyPhaseCountersWithoutPayloadData() {
    val clockValues = listOf(10L, 17L, 20L, 24L, 30L, 30L).iterator()
    val entries = mutableListOf<Pair<String, JSONObject>>()
    val timing = ProcessingTiming(
      clockMillis = { clockValues.next() },
      log = { tag, value -> entries += tag to JSONObject(value) },
    )

    assertEquals("parsed", timing.measure("pdf_read") { "parsed" })
    val failure = runCatching { timing.measure("inference") { error("translation failure") } }.exceptionOrNull()
    assertEquals("translation failure", failure?.message)
    timing.measure("pdf_read") { Unit }
    timing.flush()
    timing.flush()

    assertEquals(2, entries.size)
    assertTrue(entries.all { (tag, event) -> tag == ProcessingTiming.TAG && event.length() == 4 })
    val read = entries[0].second
    assertEquals("pdf_read", read.getString("phase"))
    assertEquals(7L, read.getLong("durationMs"))
    assertEquals(7L, read.getLong("maxDurationMs"))
    assertEquals(2, read.getInt("count"))
    val inference = entries[1].second
    assertEquals("inference", inference.getString("phase"))
    assertEquals(4L, inference.getLong("durationMs"))
    assertEquals(1, inference.getInt("count"))
  }

  @Test
  fun negativeClockDeltaIsClampedAndLoggingFailureDoesNotAffectWorkOrFlush() {
    val clockValues = listOf(8L, 3L).iterator()
    val timing = ProcessingTiming(clockMillis = { clockValues.next() }, log = { _, _ -> error("log unavailable") })

    assertEquals(42, timing.measure("preview") { 42 })
    timing.flush()
    timing.flush()
  }
}
