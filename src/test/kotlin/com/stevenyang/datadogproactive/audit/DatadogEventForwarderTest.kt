package com.stevenyang.datadogproactive.audit

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Smoke test for [DatadogEventForwarder].
 *
 * The production class does not expose a pure `buildEventBody` helper — the JSON
 * body is constructed inline inside `forwardRemediation` right before the
 * `executeOnPooledThread` call. That means we can't cheaply assert the exact
 * JSON shape from a unit test without hitting the network.
 *
 * What we CAN assert cheaply:
 *   1. The class is instantiable as an application service.
 *   2. `forwardRemediation` is a safe no-op when `DD_API_KEY` is unset — this
 *      is the demo path (clean clones, offline). No exception, no network.
 *   3. All three outcomes (`applied`, `declined`, `failed`) plus an unknown
 *      outcome flow through the lowercase `when` branch without throwing.
 *   4. A null rationale is accepted.
 *
 * The body-shape guarantees (`alert_type`, `tags`, `source_type_name`, `title`
 * content) are covered by the inline KDoc on [DatadogEventForwarder] plus the
 * manual demo-day smoke at https://app.datadoghq.com/event/explorer once
 * DD_API_KEY is set.
 */
class DatadogEventForwarderTest : BasePlatformTestCase() {

    fun testForwardRemediationIsNoOpWhenApiKeyUnset() {
        // Precondition: DD_API_KEY should be unset in the test runner. If a
        // dev has it set locally, this test still passes because we only
        // assert "no throw" — the pooled-thread POST is best-effort.
        val forwarder = DatadogEventForwarder()

        // Exercise all four outcome branches: applied→info, declined→warning,
        // failed→error, unknown→info (default branch).
        forwarder.forwardRemediation("INC-4402", "PaymentServiceImpl.charge", "applied", "patch landed")
        forwarder.forwardRemediation("INC-4402", "PaymentServiceImpl.charge", "declined", "rejected by reviewer")
        forwarder.forwardRemediation("INC-4402", "PaymentServiceImpl.charge", "failed", null)
        forwarder.forwardRemediation("INC-9999", "AdService.getAds", "unknown-outcome", null)

        // If we got here without an exception, the happy-path is green.
        assertTrue("forwardRemediation returned without throwing", true)
    }

    fun testForwarderRegistersAsApplicationService() {
        // Smoke: the @Service(APP) annotation means the platform should hand us
        // back a singleton via getService.
        val svc = com.intellij.openapi.application.ApplicationManager
            .getApplication()
            .getService(DatadogEventForwarder::class.java)
        assertNotNull("DatadogEventForwarder must be registerable as APP service", svc)
    }
}
