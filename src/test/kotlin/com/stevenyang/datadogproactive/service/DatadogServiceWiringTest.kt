package com.stevenyang.datadogproactive.service

import com.datadog.proactive.datadog.DatadogService
import com.datadog.proactive.datadog.DatadogServiceProvider
import com.datadog.proactive.datadog.MockDatadogService
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Confirms the plugin.xml registrations wire DatadogServiceProvider as the interface
 * implementation, and that it delegates to MockDatadogService by default
 * (useRealDatadog = false). RealDatadogService swapping is covered by manual UAT on the
 * sandbox IDE — BasePlatformTestCase can't simulate a Settings panel flip.
 */
class DatadogServiceWiringTest : BasePlatformTestCase() {

    fun testProviderIsBoundToDatadogServiceAndDelegatesToMockByDefault() {
        val svc = project.getService(DatadogService::class.java)
        assertNotNull("DatadogService not registered", svc)
        assertTrue(
            "Expected DatadogServiceProvider, got ${svc::class.qualifiedName}",
            svc is DatadogServiceProvider
        )
        val active = (svc as DatadogServiceProvider).get()
        assertTrue(
            "Expected provider to delegate to MockDatadogService by default, got ${active::class.qualifiedName}",
            active is MockDatadogService
        )
    }

    fun testPaymentServiceChargeIsMethodOfInterest() {
        val svc = project.getService(DatadogService::class.java)
        val ctx = svc.getMethodContext("PaymentServiceImpl.charge")
        assertNotNull("PaymentServiceImpl.charge should resolve to a MethodContext", ctx)
        assertEquals("HIGH", ctx!!.method.risk_hint)
    }

    fun testUnknownMethodReturnsNull() {
        val svc = project.getService(DatadogService::class.java)
        assertNull(svc.getMethodContext("SomeUnrelated.method"))
    }
}
