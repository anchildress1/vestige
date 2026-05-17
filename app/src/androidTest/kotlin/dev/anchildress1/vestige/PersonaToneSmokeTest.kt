package dev.anchildress1.vestige

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.vestige.inference.BackendChoice
import dev.anchildress1.vestige.inference.LiteRtLmEngine
import dev.anchildress1.vestige.inference.PersonaPromptComposer
import dev.anchildress1.vestige.model.Persona
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Story 1.8 — persona tone smoke test (manual, visual inspection).
 *
 * Runs the same input through Witness / Hardass / Editor and logs all three responses. The
 * automated assertions verify only that the three prompts diverge and each response is non-blank;
 * the human inspects the logcat output to confirm the tone actually differs.
 *
 * Prerequisite — model at `/data/local/tmp/gemma-4-E4B-it.litertlm`:
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -PmodelPath=/data/local/tmp/gemma-4-E4B-it.litertlm
 *
 * Then check logcat for tag [VestigeTone] and compare the three responses visually.
 */
@RunWith(AndroidJUnit4::class)
class PersonaToneSmokeTest {

    @Test
    fun allThreePersonas_produceNonBlankDivergentResponses() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("modelPath")
        assumeTrue("modelPath instrumentation argument not provided", modelPath != null)
        assumeTrue(
            "Model file not found at $modelPath",
            File(modelPath!!).let { it.exists() && it.canRead() },
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = LiteRtLmEngine(
            modelPath = modelPath,
            backend = BackendChoice.Cpu,
            cacheDir = context.cacheDir.absolutePath,
        )

        val input = "I said I'd send the doc by two. It's 4. I renamed it twice. Still open."

        engine.use {
            it.initialize()

            val responses = Persona.entries.associateWith { persona ->
                val systemPrompt = PersonaPromptComposer.compose(persona)
                it.generateText(systemPrompt, "User entry: $input")
            }

            responses.forEach { (persona, response) ->
                android.util.Log.i(TAG, "=== ${persona.name} ===")
                android.util.Log.i(TAG, response)
                assertTrue("${persona.name} response was blank", response.isNotBlank())
            }

            // All three prompts must differ (structural check — tone divergence is visual)
            val prompts = Persona.entries.map { PersonaPromptComposer.compose(it) }
            assertNotEquals("WITNESS and HARDASS prompts must differ", prompts[0], prompts[1])
            assertNotEquals("WITNESS and EDITOR prompts must differ", prompts[0], prompts[2])
            assertNotEquals("HARDASS and EDITOR prompts must differ", prompts[1], prompts[2])
        }
    }

    private companion object {
        const val TAG = "VestigeTone"
    }
}
