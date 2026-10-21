package dev.kigya.translatepdf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.PageIterator
import com.googlecode.tesseract.android.ResultIterator
import com.googlecode.tesseract.android.TessBaseAPI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.lang.reflect.Constructor

private object TessShadowState {
  var initializePath: String? = null
  var initializeLanguage: String? = null
  var pageSegMode: Int? = null
  var imageSet = 0
  var recognizeCalls = 0
  var iteratorBegins = 0
  var iteratorDeletes = 0
  var clears = 0
  var stops = 0
  var recycles = 0

  fun reset() {
    initializePath = null
    initializeLanguage = null
    pageSegMode = null
    imageSet = 0
    recognizeCalls = 0
    iteratorBegins = 0
    iteratorDeletes = 0
    clears = 0
    stops = 0
    recycles = 0
  }
}

@Implements(TessBaseAPI::class, callNativeMethodsByDefault = false)
class ShadowTessBaseAPI {
  @Implementation fun __constructor__() = Unit
  @Implementation fun init(dataPath: String, language: String): Boolean {
    TessShadowState.initializePath = dataPath
    TessShadowState.initializeLanguage = language
    return true
  }
  @Implementation fun setPageSegMode(mode: Int) { TessShadowState.pageSegMode = mode }
  @Implementation fun setImage(bitmap: Bitmap) { TessShadowState.imageSet++ }
  @Implementation fun getHOCRText(page: Int): String { TessShadowState.recognizeCalls++; return "" }
  @Implementation fun getResultIterator(): ResultIterator {
    val constructor: Constructor<ResultIterator> = ResultIterator::class.java.getDeclaredConstructor(Long::class.javaPrimitiveType)
    constructor.isAccessible = true
    return constructor.newInstance(1L)
  }
  @Implementation fun clear() { TessShadowState.clears++ }
  @Implementation fun stop() { TessShadowState.stops++ }
  @Implementation fun recycle() { TessShadowState.recycles++ }

  companion object {
    @Implementation @JvmStatic fun __staticInitializer__() = Unit
  }
}

@Implements(PageIterator::class, callNativeMethodsByDefault = false)
open class ShadowPageIterator {
  @Implementation open fun __constructor__(nativeIterator: Long) = Unit
  @Implementation fun begin() { TessShadowState.iteratorBegins++ }
  @Implementation fun next(level: Int): Boolean = false
  @Implementation fun getBoundingRect(level: Int): Rect = Rect(10, 20, 70, 40)

  companion object {
    @Implementation @JvmStatic fun __staticInitializer__() = Unit
  }
}

@Implements(ResultIterator::class, callNativeMethodsByDefault = false)
class ShadowResultIterator : ShadowPageIterator() {
  @Implementation override fun __constructor__(nativeIterator: Long) = Unit
  @Implementation fun getUTF8Text(level: Int): String = "  adapter boundary  "
  @Implementation fun delete() { TessShadowState.iteratorDeletes++ }

  companion object {
    @Implementation @JvmStatic fun __staticInitializer__() = Unit
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(
  sdk = [37],
  application = Application::class,
  shadows = [ShadowTessBaseAPI::class, ShadowPageIterator::class, ShadowResultIterator::class],
)
class NativeTesseractApiTest {
  @Test
  fun defaultEngineExercisesProductionTesseractAdapterAgainstShadowedJniBoundary() {
    TessShadowState.reset()
    val context = RuntimeEnvironment.getApplication()
    val trainedData = File(context.filesDir, "tesseract/tessdata/eng.traineddata")
    trainedData.delete()
    val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
    val engine = OcrEngine(context, sourceLanguage = "en")

    val recognized = engine.recognize(bitmap, pageWidth = 100f, pageHeight = 50f)

    assertEquals("eng", TessShadowState.initializeLanguage)
    assertTrue(TessShadowState.initializePath.orEmpty().endsWith("/tesseract"))
    assertEquals(TessBaseAPI.PageSegMode.PSM_AUTO, TessShadowState.pageSegMode)
    assertEquals(1, TessShadowState.imageSet)
    assertEquals(1, TessShadowState.recognizeCalls)
    assertEquals(1, TessShadowState.iteratorBegins)
    assertEquals(1, TessShadowState.iteratorDeletes)
    assertEquals(1, TessShadowState.clears)
    assertEquals("adapter boundary", recognized.getJSONObject(0).getString("sourceText"))
    assertEquals(5.0, recognized.getJSONObject(0).getDouble("left"), 0.001)
    assertEquals(35.0, recognized.getJSONObject(0).getDouble("right"), 0.001)

    engine.stop()
    engine.close()
    assertEquals(1, TessShadowState.stops)
    assertEquals(1, TessShadowState.recycles)
    bitmap.recycle()
  }

  @Test
  fun languageCatalogGetterExposesOnlyBundledOcrLanguages() {
    val companionField = OcrEngine::class.java.getDeclaredField("Companion").apply { isAccessible = true }
    val companion = companionField.get(null)
    val catalogGetter = companion.javaClass.getDeclaredMethod("getDATA_BY_PRODUCT_LANGUAGE").apply { isAccessible = true }
    val catalog = catalogGetter.invoke(companion) as Map<*, *>

    assertEquals(setOf("en", "ru", "zh-Hans", "fr", "de", "es"), catalog.keys)
    assertTrue(catalog.values.all { entry ->
      val assetName = entry!!.javaClass.getDeclaredField("assetName").apply { isAccessible = true }.get(entry) as String
      val digest = entry.javaClass.getDeclaredField("sha256").apply { isAccessible = true }.get(entry) as String
      assetName.endsWith(".traineddata") && digest.matches(Regex("^[0-9a-f]{64}$"))
    })
  }
}
