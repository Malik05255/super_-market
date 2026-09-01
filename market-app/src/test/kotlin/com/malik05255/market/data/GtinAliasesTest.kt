package com.malik05255.market.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GtinAliasesTest {
    @Test
    fun upcAAlsoChecksLeadingZeroEan13() {
        assertEquals(
            listOf("123456789012", "0123456789012"),
            equivalentGtins("123456789012")
        )
    }

    @Test
    fun leadingZeroEan13AlsoChecksUpcA() {
        assertEquals(
            listOf("0123456789012", "123456789012"),
            equivalentGtins("0123456789012")
        )
    }

    @Test
    fun ordinaryEan13IsNotRewritten() {
        assertEquals(
            listOf("6281234567890"),
            equivalentGtins("6281234567890")
        )
    }
}
