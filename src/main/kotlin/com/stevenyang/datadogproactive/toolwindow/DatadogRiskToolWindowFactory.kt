package com.stevenyang.datadogproactive.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Phase 3: hosts the two-tab "Datadog Risk" tool window.
 *
 *  - Tab 1 ("Risk") — [RiskCardPanel]: inbox (idle) + live reasoning card (running).
 *  - Tab 2 ("Live Feed") — [LiveFeedPanel]: JCEF-embedded bundled dashboard with a
 *    link-panel fallback for runtimes that don't ship JCEF.
 *
 * The panel instance is stashed on the project via [RISK_CARD_KEY] so
 * [com.stevenyang.datadogproactive.action.AnalyzePrRiskAction] can push events to it
 * without re-walking the ContentManager on every click.
 */
class DatadogRiskToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = RiskCardPanel(project)
        project.putUserData(RISK_CARD_KEY, panel)

        val contentFactory = ContentFactory.getInstance()
        val riskContent = contentFactory.createContent(panel, "Risk", false)
        toolWindow.contentManager.addContent(riskContent)

        val liveFeed = LiveFeedPanel()
        val liveContent = contentFactory.createContent(liveFeed, "Live Feed", false).apply {
            setDisposer(liveFeed)
        }
        toolWindow.contentManager.addContent(liveContent)
        toolWindow.contentManager.setSelectedContent(riskContent)

        // Populate the inbox off-EDT so opening the tool window never stalls the UI.
        ApplicationManager.getApplication().executeOnPooledThread { panel.refreshInbox() }

        // Idempotent — first caller wins; the startup activity may have already run it.
        ActiveFileListener.install(project, panel)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true

    companion object {
        val RISK_CARD_KEY: Key<RiskCardPanel> = Key.create("com.stevenyang.datadogproactive.RiskCardPanel")

        /** Lazy access: creates-if-missing so callers before the tool window is first shown
         *  don't NPE. The authoritative instance is always registered back on the Key. */
        fun getOrCreate(project: Project): RiskCardPanel {
            project.getUserData(RISK_CARD_KEY)?.let { return it }
            val panel = RiskCardPanel(project)
            project.putUserData(RISK_CARD_KEY, panel)
            return panel
        }
    }
}
