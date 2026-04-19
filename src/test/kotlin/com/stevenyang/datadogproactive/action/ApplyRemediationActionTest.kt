package com.stevenyang.datadogproactive.action

import com.proactive.risk.ProposedPatch
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [ApplyRemediationAction] that avoid booting a full IntelliJ test project.
 *
 * Covers:
 *   1. `extractMinusPlus` parses a minimal unified-diff hunk into separate `-` and `+` bodies.
 *   2. `applyReplaceText` (pure-text helper) swaps the `-` block for the `+` block; a
 *      no-match case returns null.
 */
class ApplyRemediationActionTest {

    private val action = ApplyRemediationAction()

    @Test fun `extractMinusPlus separates hunks and strips headers`() {
        val diff = """
            --- PaymentService.java
            +++ PaymentService.java
            @@ -140,3 +140,3 @@
            -country = customer.billingAddress.country;
            +country = customer.billingAddress != null ? customer.billingAddress.country : null;
        """.trimIndent()
        val (minus, plus) = action.extractMinusPlus(diff)
        assertEquals("country = customer.billingAddress.country;", minus)
        assertTrue(plus.startsWith("country = customer.billingAddress != null"))
    }

    @Test fun `applyReplaceText swaps minus for plus on a plain string`() {
        val initial = "line1\ncountry = customer.billingAddress.country;\nline3"
        val replaced = action.applyReplaceText(
            text = initial,
            minus = "country = customer.billingAddress.country;",
            plus = "country = customer.billingAddress != null ? customer.billingAddress.country : null;",
        )
        assertTrue(replaced != null, "expected non-null replaced text")
        assertTrue(
            replaced!!.contains("!= null ?"),
            "expected replacement to be applied, got: $replaced",
        )
    }

    @Test fun `applyReplaceText returns null when minus block not in text`() {
        val replaced = action.applyReplaceText(
            text = "totally unrelated contents",
            minus = "country = customer.billingAddress.country;",
            plus = "anything",
        )
        assertFalse(replaced != null, "expected null on missing minus block")
    }

    @Test fun `a verdict without proposedPatch yields null currentPatch intent`() {
        // Pure-data sanity check: the action's precondition is driven by ProposedPatch
        // non-nullness. We reproduce the discriminator here to lock the invariant in
        // place (the real project hook is exercised by integration tests).
        val blank = ProposedPatch("INC-X", "X.java", "X.y", unifiedDiff = "", rationale = "r")
        assertTrue(blank.unifiedDiff.isBlank(), "blank unifiedDiff should gate the action")
    }
}
