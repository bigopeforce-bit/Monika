package com.bigopeforcebit.monika.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityListTest {
    private val monika = PermissionCommands.accessibilityService("com.bigopeforcebit.monika")
    private val monikaLong = "com.bigopeforcebit.monika/com.bigopeforcebit.monika.a11y.MonikaAccessibilityService"
    private val other = "com.example.reader/.ReaderService"
    private val other2 = "org.sample.keys/org.sample.keys.KeyService"

    @Test
    fun emptyOrUnsetValues() {
        listOf(null, "", "   ", "null", "null\n", ":", " : ").forEach { raw ->
            assertEquals("raw=$raw", emptyList<String>(), AccessibilityList.entries(raw))
            assertFalse(AccessibilityList.contains(raw, monika))
            assertEquals(monika, AccessibilityList.with(raw, monika))
            assertEquals("", AccessibilityList.without(raw, monika))
        }
    }

    @Test
    fun appendKeepsOtherServicesInOrder() {
        assertEquals("$other:$monika", AccessibilityList.with(other, monika))
        assertEquals("$other:$other2:$monika", AccessibilityList.with("$other:$other2\n", monika))
    }

    @Test
    fun alreadyPresentIsNotDuplicated() {
        assertEquals("$other:$monika", AccessibilityList.with("$other:$monika", monika))
        // 系统里存的是完整类名写法,也算已经有了
        assertEquals("$monikaLong:$other", AccessibilityList.with("$monikaLong:$other", monika))
        assertTrue(AccessibilityList.contains(monikaLong, monika))
    }

    @Test
    fun removeKeepsOthersAndHandlesBothSpellings() {
        assertEquals("$other:$other2", AccessibilityList.without("$other:$monika:$other2", monika))
        assertEquals(other, AccessibilityList.without("$monikaLong:$other", monika))
        assertEquals(other, AccessibilityList.without(other, monika))
        assertEquals("", AccessibilityList.without(monika, monika))
    }

    @Test
    fun strayColonsAndSpacesAreCleanedUp() {
        assertEquals("$other:$other2:$monika", AccessibilityList.with(":$other:: $other2 :", monika))
        assertEquals("$other:$other2", AccessibilityList.without("$other::$monika:$other2:", monika))
    }

    @Test
    fun differentServiceOfSamePackageIsNotConfused() {
        val sibling = "com.bigopeforcebit.monika/.a11y.OtherService"
        assertFalse(AccessibilityList.contains(sibling, monika))
        assertEquals(sibling, AccessibilityList.without("$sibling:$monika", monika))
    }
}
