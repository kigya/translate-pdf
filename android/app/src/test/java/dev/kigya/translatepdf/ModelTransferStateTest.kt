package dev.kigya.translatepdf

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class ModelTransferStateTest {
  @Test
  fun transferVisibilityIsSharedAndIncludesQueuedWorkUntilPauseOrRelease() {
    val context = RuntimeEnvironment.getApplication()
    val database = TranslatorDatabase(context)
    val module = ModelDownloader(context, database)
    val service = ModelDownloader(context, database)
    val id = ModelCatalog.models.first().id
    assertFalse(module.hasActiveTransfer(id))
    val token = module.reserve(id)
    try {
      assertTrue(service.hasActiveTransfer(id))
      assertTrue(service.claimServiceStart(id, token))
      assertTrue(module.hasActiveTransfer(id))
      service.pause(id)
      assertFalse(module.hasActiveTransfer(id))
      service.releaseServiceStart(id, token)
      assertFalse(service.hasActiveTransfer(id))
    } finally {
      module.releaseReservation(id, token)
      database.close()
    }
  }
}
