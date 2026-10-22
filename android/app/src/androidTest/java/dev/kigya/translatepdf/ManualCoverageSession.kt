package dev.kigya.translatepdf

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in collection host for operator-driven, cached-document UI coverage.
 * Closing this session proves collection finished, not that any UI flow passed.
 * Ordinary instrumentation runs skip it; the delivered APK has no JaCoCo agent.
 */
@RunWith(AndroidJUnit4::class)
class ManualCoverageSession {
  @Test fun collectUntilOperatorFinishes() {
    val args = InstrumentationRegistry.getArguments()
    assumeTrue("Manual profiling session was not requested", args.getString("folivelaManualCoverage") == "true")
    val session = args.getString("folivelaCoverageSession") ?: "u036"
    require(session.matches(Regex("[A-Za-z0-9-]{1,48}")))
    val target = InstrumentationRegistry.getInstrumentation().targetContext
    val finished = File(target.cacheDir, "manual-coverage-$session.done")
    finished.delete()
    try {
      ActivityScenario.launch(MainActivity::class.java).use {
        val deadline = SystemClock.elapsedRealtime() + 15 * 60_000
        while (!finished.isFile) {
          check(SystemClock.elapsedRealtime() < deadline) { "The operator did not finish the manual coverage session." }
          Thread.sleep(250)
        }
      }
    } finally {
      finished.delete()
    }
  }
}
