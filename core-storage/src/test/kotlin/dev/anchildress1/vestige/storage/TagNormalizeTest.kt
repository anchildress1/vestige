package dev.anchildress1.vestige.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class TagNormalizeTest {

    @Test
    fun `lowercases the input`() {
        assertEquals("aftermath", TagNormalize.kebab("Aftermath"))
    }

    @Test
    fun `replaces whitespace runs with a single hyphen`() {
        assertEquals("tuesday-meeting", TagNormalize.kebab("Tuesday Meeting"))
        assertEquals("tuesday-meeting", TagNormalize.kebab("tuesday  meeting"))
        assertEquals("tuesday-meeting", TagNormalize.kebab("tuesday\tmeeting"))
    }

    @Test
    fun `replaces underscores with hyphens`() {
        assertEquals("tuesday-meeting", TagNormalize.kebab("tuesday_meeting"))
    }

    @Test
    fun `collapses consecutive hyphens`() {
        assertEquals("a-b", TagNormalize.kebab("a--b"))
        assertEquals("a-b", TagNormalize.kebab("a -- b"))
    }

    @Test
    fun `trims surrounding hyphens and whitespace`() {
        assertEquals("aftermath", TagNormalize.kebab("  -aftermath-  "))
    }

    @Test
    fun `empty and whitespace-only inputs return empty string`() {
        assertEquals("", TagNormalize.kebab(""))
        assertEquals("", TagNormalize.kebab("   "))
    }

    @Test
    fun `already-canonical input is idempotent`() {
        assertEquals("tuesday-meeting", TagNormalize.kebab("tuesday-meeting"))
        assertEquals("a-b-c", TagNormalize.kebab(TagNormalize.kebab("A B C")))
    }
}
