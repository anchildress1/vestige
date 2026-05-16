package dev.anchildress1.vestige.ui.onboarding

import dev.anchildress1.vestige.model.DefaultNetworkGate
import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.ModelArtifactStore
import dev.anchildress1.vestige.model.ModelManifest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelAvailabilityTest {

    @Test
    fun `isReady is true only for Complete`() {
        assertTrue(ModelArtifactState.Complete.isReady)
        assertFalse(ModelArtifactState.Absent.isReady)
        assertFalse(ModelArtifactState.Partial(currentBytes = 1L, expectedBytes = 2L).isReady)
        assertFalse(ModelArtifactState.Corrupt(expectedSha256 = "expected", actualSha256 = "actual").isReady)
    }

    @Test
    fun `downloadFraction is null for Absent and Corrupt`() {
        assertNull(ModelArtifactState.Absent.downloadFraction)
        assertNull(
            ModelArtifactState.Corrupt(
                expectedSha256 = "expected",
                actualSha256 = "actual",
            ).downloadFraction,
        )
    }

    @Test
    fun `downloadFraction is null when Partial does not know a positive total`() {
        assertNull(ModelArtifactState.Partial(currentBytes = 5L, expectedBytes = 0L).downloadFraction)
    }

    @Test
    fun `downloadFraction reports partial progress and clamps above one`() {
        assertEquals(0.47f, ModelArtifactState.Partial(currentBytes = 470L, expectedBytes = 1_000L).downloadFraction)
        assertEquals(1f, ModelArtifactState.Partial(currentBytes = 2_000L, expectedBytes = 1_000L).downloadFraction)
    }

    @Test
    fun `downloadFraction is one for Complete`() {
        assertEquals(1f, ModelArtifactState.Complete.downloadFraction)
    }

    @Test
    fun `default availability status delegates to the cheap probe, not the hashing currentState`() {
        // currentState() and probe() return deliberately different values so the assertion can
        // only pass if status() routes through probe() — the no-SHA UI path. A regression that
        // points status() back at currentState() (the multi-GB hash) fails loudly here.
        val availability = ModelAvailability.Default(
            artifactStore = object : ModelArtifactStore {
                override val manifest: ModelManifest = TEST_MANIFEST
                override val artifactFile: File = File("ignored.bin")

                override suspend fun currentState(): ModelArtifactState =
                    ModelArtifactState.Corrupt(expectedSha256 = "expected", actualSha256 = "actual")

                override suspend fun probe(): ModelArtifactState =
                    ModelArtifactState.Partial(currentBytes = 123L, expectedBytes = 456L)

                override suspend fun download(onProgress: (Long, Long) -> Unit): ModelArtifactState =
                    ModelArtifactState.Complete

                override suspend fun verifyChecksum(): Boolean = true

                override suspend fun requireComplete(): File = artifactFile
            },
            networkGate = DefaultNetworkGate.ALWAYS_OPEN_FOR_TESTS,
        )

        assertEquals(
            ModelArtifactState.Partial(currentBytes = 123L, expectedBytes = 456L),
            runBlocking { availability.status() },
        )
    }

    private companion object {
        val TEST_MANIFEST = ModelManifest(
            schemaVersion = ModelManifest.SUPPORTED_SCHEMA_VERSION,
            artifactRepo = "test",
            filename = "model.bin",
            downloadUrl = "https://example.test/model.bin",
            expectedByteSize = 1_000L,
            sha256 = "abc123",
            allowedHosts = listOf("example.test"),
        )
    }
}
