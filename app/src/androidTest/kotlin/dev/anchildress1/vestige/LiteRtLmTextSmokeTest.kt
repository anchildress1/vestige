package dev.anchildress1.vestige

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.vestige.inference.BackendChoice
import dev.anchildress1.vestige.inference.LiteRtLmEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Story 1.3 — text-only smoke test for LiteRT-LM + Gemma 4 E4B on the reference device.
 *
 * Prerequisite — you (the human) must adb-push the model file to the device first. Below,
 * `<MODEL_DIR>` is `/sdcard/Android/data/dev.anchildress1.vestige/files/models`:
 *
 *   adb push gemma-4-E4B-it.litertlm <MODEL_DIR>/
 *
 * Then run, replacing `<MODEL_DIR>` again:
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.modelPath=<MODEL_DIR>/gemma-4-E4B-it.litertlm
 *
 * If `modelPath` is not supplied or the file is missing the test is skipped via [assumeTrue]
 * rather than failing — this lets CI runs that lack the artifact stay green.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtLmTextSmokeTest {

    @Test
    fun gemma4E4B_respondsToOkPrompt() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("modelPath")
        assumeTrue("modelPath instrumentation argument not provided", modelPath != null)
        val modelFile = File(modelPath!!)
        assumeTrue("Model file not found at $modelPath", modelFile.exists() && modelFile.canRead())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = LiteRtLmEngine(
            modelPath = modelPath,
            backend = BackendChoice.Cpu,
            cacheDir = context.cacheDir.absolutePath,
        )
        engine.use {
            it.initialize()
            val response = it.generateText("Respond with the single word OK.")
            assertTrue("Response was blank", response.isNotBlank())
        }
    }
}
