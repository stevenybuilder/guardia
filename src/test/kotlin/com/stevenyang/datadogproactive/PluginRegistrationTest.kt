package com.stevenyang.datadogproactive

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Confirms plugin.xml extension points resolve at runtime.
 *
 * Tool-window registration is NOT asserted here — BasePlatformTestCase uses a lightweight
 * test fixture that skips `<toolWindow>` extension loading. Real validation happens via
 * `./gradlew runIde` manual + remote-robot smoke (Phase 4).
 */
class PluginRegistrationTest : BasePlatformTestCase() {

    fun testAnalyzePrRiskActionRegistered() {
        val action = ActionManager.getInstance().getAction("DatadogProactive.AnalyzePrRisk")
        assertNotNull("DatadogProactive.AnalyzePrRisk action missing from plugin.xml", action)
    }
}
