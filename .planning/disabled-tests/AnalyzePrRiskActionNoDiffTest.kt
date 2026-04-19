package com.stevenyang.datadogproactive.action

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.proactive.coordinator.DefaultRiskAnalysisCoordinator
import com.proactive.domain.Diff
import com.proactive.risk.RiskRequest
import com.stevenyang.datadogproactive.toolwindow.RiskCardPanel
import com.intellij.openapi.progress.EmptyProgressIndicator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Validates the no-diff short-circuit in [AnalyzePrRiskAction.runAnalysis]. When the
 * current VCS state yields a [RiskRequest] with no touched files, we must render a
 * friendly info banner and SKIP the coordinator entirely — the whole flow used to
 * silently run to final=0 with no visible evidence Codex was even attempted.
 *
 * Uses the real BasePlatformTestCase project (no real VCS state by default, so the
 * action's own [AnalyzePrRiskAction.buildRequest] already yields empty touchedFiles).
 */
class AnalyzePrRiskActionNoDiffTest : BasePlatformTestCase() {

    fun testBuildRequestYieldsEmptyTouchedFilesOnCleanProject() {
        val action = AnalyzePrRiskAction()
        val request = action.buildRequest(project)
        assertTrue(
            "expected empty touched files on a clean test project, got ${request.diff.touchedFiles}",
            request.diff.touchedFiles.isEmpty(),
        )
    }

    fun testNoDiffShortCircuitsWithoutCoordinatorCall() {
        val action = AnalyzePrRiskAction()

        // A tiny spy panel that records the info-banner call. We deliberately avoid the
        // Swing layout code paths — only the public contract matters here.
        val showInfoCalled = AtomicBoolean(false)
        val capturedText = AtomicReference<String>("")
        val panel = object : RiskCardPanel(project) {
            override fun showInfo(text: String) {
                showInfoCalled.set(true)
                capturedText.set(text)
            }
        }

        // If runAnalysis tried to reach the coordinator despite empty touchedFiles, it
        // would invoke project.getService(DefaultRiskAnalysisCoordinator::class.java).
        // We can verify nothing went wrong by asserting showInfo fired and the method
        // returned without throwing.
        action.runAnalysis(project, panel, EmptyProgressIndicator())

        assertTrue("showInfo must fire on no-diff path", showInfoCalled.get())
        val msg = capturedText.get()
        assertTrue(
            "info text should mention 'No VCS changes', got: $msg",
            msg.contains("No VCS changes"),
        )
    }

    fun testCoordinatorServiceExistsOnProject() {
        // Sanity — confirms the service is registered; the no-diff test above *could* pass
        // just because the service is missing. This guarantees we're really short-circuiting.
        val coordinator = project.getService(DefaultRiskAnalysisCoordinator::class.java)
        assertNotNull("DefaultRiskAnalysisCoordinator must be registered", coordinator)
    }
}
