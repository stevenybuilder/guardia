package com.stevenyang.datadogproactive.toolwindow

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.stevenyang.datadogproactive.util.DashboardResources
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Second tool-window tab: live in-IDE Datadog-style dashboard.
 *
 * Primary path — [JBCefApp.isSupported] is true: host the bundled
 * `/dashboard/index.html` in a [JBCefBrowser] so the dashboard renders *inside*
 * the tool window without leaving the IDE.
 *
 * Fallback path — JCEF unavailable (sandbox misconfig, headless, some older
 * IntelliJ builds without the JCEF runtime): render a polite text panel with a
 * link that opens the same bundled dashboard in the user's default browser via
 * [BrowserUtil]. Demo still works, just not embedded.
 *
 * Implements [Disposable] so the hosting `Content` can dispose the JCEF browser
 * deterministically on tool-window teardown.
 */
class LiveFeedPanel : JBPanel<LiveFeedPanel>(BorderLayout()), Disposable {

    private val log = Logger.getInstance(LiveFeedPanel::class.java)
    private val browser: JBCefBrowser?

    /** JS → Kotlin bridge that forwards a link-type string from in-page clicks to BrowserUtil. */
    private var openLinkQuery: JBCefJSQuery? = null

    init {
        border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
        // Action links now live inside the HTML dashboard itself (below the purple
        // header, above SERVICE HEALTH) so they flow with the dashboard's
        // typography + spacing. The Kotlin side exposes a JS-callable bridge
        // that routes clicks to BrowserUtil.
        browser = if (JBCefApp.isSupported()) {
            try {
                val htmlPath = DashboardResources.extractLocalDashboardInlined()
                JBCefBrowser().also { b ->
                    installOpenLinkBridge(b)
                    b.loadURL(htmlPath.toUri().toString())
                    add(b.component, BorderLayout.CENTER)
                }
            } catch (t: Throwable) {
                log.warn("JBCefBrowser init failed; falling back to link panel", t)
                add(buildFallbackPanel(t.message), BorderLayout.CENTER)
                null
            }
        } else {
            add(buildFallbackPanel(null), BorderLayout.CENTER)
            null
        }
    }

    /** Wire a JBCefJSQuery so in-page anchors tagged with data-guardia-link can
     *  call back into Kotlin via `window.guardiaOpen(kind)`. Kotlin resolves the
     *  kind to a real URL and opens it in the user's default browser. */
    private fun installOpenLinkBridge(browser: JBCefBrowser) {
        val query = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
        query.addHandler { kind: String ->
            val app = ApplicationManager.getApplication()
            val opener = Runnable {
                try {
                    when (kind) {
                        "datadog" -> BrowserUtil.browse(DashboardResources.liveDatadogEventsUrl())
                        "localDashboard" -> BrowserUtil.browse(DashboardResources.extractLocalDashboard().toUri())
                        else -> log.debug("guardiaOpen ignored unknown kind=$kind")
                    }
                } catch (t: Throwable) {
                    log.warn("guardiaOpen failed for kind=$kind: ${t.message}", t)
                }
            }
            if (app != null) app.invokeLater(opener) else opener.run()
            null
        }
        openLinkQuery = query
        // Inject the JS bridge + click handler on every page load so reloads keep working.
        browser.jbCefClient.addLoadHandler(object : org.cef.handler.CefLoadHandlerAdapter() {
            override fun onLoadEnd(
                cefBrowser: org.cef.browser.CefBrowser?,
                frame: org.cef.browser.CefFrame?,
                httpStatusCode: Int,
            ) {
                val js = """
                    (function(){
                        window.guardiaOpen = function(kind) {
                            ${query.inject("kind")}
                        };
                        document.querySelectorAll('a[data-guardia-link]').forEach(function(a){
                            a.addEventListener('click', function(e){
                                e.preventDefault();
                                window.guardiaOpen(a.getAttribute('data-guardia-link'));
                            });
                        });
                    })();
                """.trimIndent()
                cefBrowser?.executeJavaScript(js, cefBrowser.url, 0)
            }
        }, browser.cefBrowser)
    }

    /** JCEF unavailable or failed: surface a link panel that routes to the system browser. */
    private fun buildFallbackPanel(errorMessage: String?): JComponent {
        val wrapper = JBPanel<JBPanel<*>>()
        wrapper.layout = BoxLayout(wrapper, BoxLayout.Y_AXIS)
        wrapper.border = BorderFactory.createEmptyBorder(20, 24, 20, 24)

        val title = JBLabel("Live Feed").apply {
            font = font.deriveFont(Font.BOLD, font.size + 2f)
            alignmentX = LEFT_ALIGNMENT
        }
        val reason = errorMessage
            ?.let { "Embedded browser unavailable: $it" }
            ?: "Embedded browser (JCEF) is not available in this IDE runtime."
        val explainer = JBLabel("<html><body style='width: 360px'>" +
            reason +
            "<br/><br/>The bundled dashboard and the live Datadog Events Explorer both open " +
            "in your default browser via the buttons below.</body></html>").apply {
            foreground = JBColor(Color(0x555555), Color(0xBBBBBB))
            alignmentX = LEFT_ALIGNMENT
        }

        val openLocal = JButton("\uD83D\uDDA5 Open local dashboard in browser").apply {
            alignmentX = LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener {
                try {
                    val htmlPath = DashboardResources.extractLocalDashboard()
                    BrowserUtil.browse(htmlPath.toUri())
                } catch (t: Throwable) {
                    log.warn("Fallback: extract-and-open local dashboard failed", t)
                }
            }
        }
        val openLive = JButton("\uD83D\uDCCA Open Datadog Events Explorer").apply {
            alignmentX = LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { BrowserUtil.browse(DashboardResources.liveDatadogEventsUrl()) }
        }

        // Click-anywhere-on-title also opens the local dashboard for discoverability.
        title.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        title.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { openLocal.doClick() }
        })

        wrapper.add(title)
        wrapper.add(Box.createVerticalStrut(8))
        wrapper.add(explainer)
        wrapper.add(Box.createVerticalStrut(16))
        wrapper.add(openLocal)
        wrapper.add(Box.createVerticalStrut(6))
        wrapper.add(openLive)
        wrapper.add(Box.createVerticalGlue())
        return wrapper
    }

    /** True if the embedded JCEF browser initialized successfully. Exposed for tests / diagnostics. */
    fun isJcefEmbedded(): Boolean = browser != null

    override fun dispose() {
        openLinkQuery?.let { com.intellij.openapi.util.Disposer.dispose(it) }
        browser?.dispose()
    }
}
