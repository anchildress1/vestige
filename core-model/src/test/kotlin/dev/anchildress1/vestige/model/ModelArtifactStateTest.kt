package dev.anchildress1.vestige.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelArtifactStateTest {

    @Test
    fun `Absent is a singleton data object`() {
        assertSame(ModelArtifactState.Absent, ModelArtifactState.Absent)
    }

    @Test
    fun `Complete is a singleton data object`() {
        assertSame(ModelArtifactState.Complete, ModelArtifactState.Complete)
    }

    @Test
    fun `Absent and Complete are distinct`() {
        assertNotEquals(ModelArtifactState.Absent, ModelArtifactState.Complete)
    }

    @Test
    fun `Partial carries currentBytes and expectedBytes`() {
        val state = ModelArtifactState.Partial(currentBytes = 512L, expectedBytes = 1024L)
        assertEquals(512L, state.currentBytes)
        assertEquals(1024L, state.expectedBytes)
    }

    @Test
    fun `Partial equality holds when bytes match`() {
        val a = ModelArtifactState.Partial(currentBytes = 100L, expectedBytes = 200L)
        val b = ModelArtifactState.Partial(currentBytes = 100L, expectedBytes = 200L)
        assertEquals(a, b)
    }

    @Test
    fun `Partial equality discriminates on currentBytes`() {
        val a = ModelArtifactState.Partial(currentBytes = 50L, expectedBytes = 200L)
        val b = ModelArtifactState.Partial(currentBytes = 51L, expectedBytes = 200L)
        assertNotEquals(a, b)
    }

    @Test
    fun `Partial equality discriminates on expectedBytes`() {
        val a = ModelArtifactState.Partial(currentBytes = 50L, expectedBytes = 200L)
        val b = ModelArtifactState.Partial(currentBytes = 50L, expectedBytes = 300L)
        assertNotEquals(a, b)
    }

    @Test
    fun `Corrupt carries expectedSha256 and actualSha256`() {
        val expectedHash = "aaaa"
        val actualHash = "bbbb"
        val state = ModelArtifactState.Corrupt(expectedSha256 = expectedHash, actualSha256 = actualHash)
        assertEquals(expectedHash, state.expectedSha256)
        assertEquals(actualHash, state.actualSha256)
    }

    @Test
    fun `Corrupt equality holds when both SHA strings match`() {
        val a = ModelArtifactState.Corrupt("abc", "def")
        val b = ModelArtifactState.Corrupt("abc", "def")
        assertEquals(a, b)
    }

    @Test
    fun `Corrupt equality discriminates on actualSha256`() {
        val a = ModelArtifactState.Corrupt("abc", "def")
        val b = ModelArtifactState.Corrupt("abc", "xyz")
        assertNotEquals(a, b)
    }

    @Test
    fun `Corrupt equality discriminates on expectedSha256`() {
        val a = ModelArtifactState.Corrupt("abc", "def")
        val b = ModelArtifactState.Corrupt("xyz", "def")
        assertNotEquals(a, b)
    }

    @Test
    fun `all four sealed subtypes are discriminated by type check`() {
        val states: List<ModelArtifactState> = listOf(
            ModelArtifactState.Absent,
            ModelArtifactState.Partial(0L, 100L),
            ModelArtifactState.Complete,
            ModelArtifactState.Corrupt("x", "y"),
        )
        assertTrue(states[0] is ModelArtifactState.Absent)
        assertTrue(states[1] is ModelArtifactState.Partial)
        assertTrue(states[2] is ModelArtifactState.Complete)
        assertTrue(states[3] is ModelArtifactState.Corrupt)
    }

    @Test
    fun `Partial is not equal to Absent or Complete`() {
        val partial = ModelArtifactState.Partial(10L, 100L)
        assertNotEquals(partial, ModelArtifactState.Absent)
        assertNotEquals(partial, ModelArtifactState.Complete)
    }

    @Test
    fun `Corrupt is not equal to Absent or Complete`() {
        val corrupt = ModelArtifactState.Corrupt("e", "a")
        assertNotEquals(corrupt, ModelArtifactState.Absent)
        assertNotEquals(corrupt, ModelArtifactState.Complete)
    }
}
