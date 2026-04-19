package com.stevenyang.datadogproactive.gutter

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Exercises the LineMarkerProvider through the highlighting daemon and asserts the
 * correct gutter tooltip is populated for methods_of_interest.
 */
class DatadogLineMarkerProviderTest : BasePlatformTestCase() {

    fun testGutterOnPaymentServiceCharge() {
        myFixture.configureByText(
            "PaymentServiceImpl.java",
            """
            package com.hipstershop.paymentservicejava;
            public class PaymentServiceImpl {
                public void charge(double amount) { }
                public void refund(double amount) { }
            }
            """.trimIndent()
        )
        myFixture.doHighlighting()

        val tooltips = myFixture.findAllGutters().mapNotNull { it.tooltipText }
        assertTrue(
            "Expected a Datadog gutter tooltip mentioning PaymentServiceImpl.charge; got $tooltips",
            tooltips.any { it.contains("PaymentServiceImpl.charge") }
        )
    }

    fun testNoGutterOnUnknownMethod() {
        myFixture.configureByText(
            "UnknownService.java",
            """
            public class UnknownService { public void doSomething() { } }
            """.trimIndent()
        )
        myFixture.doHighlighting()
        val dd = myFixture.findAllGutters().mapNotNull { it.tooltipText }
            .filter { it.contains("Datadog:") }
        assertTrue("Expected no Datadog gutter on unknown method; got $dd", dd.isEmpty())
    }
}
