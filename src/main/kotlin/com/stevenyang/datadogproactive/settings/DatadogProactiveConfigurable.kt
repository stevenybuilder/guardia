package com.stevenyang.datadogproactive.settings

import com.datadog.proactive.datadog.DatadogModeListener
import com.datadog.proactive.datadog.DatadogService
import com.datadog.proactive.datadog.DatadogServiceProvider
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings → Tools → Datadog Proactive. Minimal: API key (masked), model, base URL,
 * USE_REAL_DATADOG toggle. Anything more is out of scope (CLAUDE.md).
 */
class DatadogProactiveConfigurable : Configurable {

    private val apiKeyField = JBPasswordField()
    private val modelField = JBTextField()
    private val baseUrlField = JBTextField()
    private val useRealDatadogCheckbox = JBCheckBox("Use Real Datadog (default: Mock)")
    private val testConnectionButton = JButton("Test Datadog connection")
    private val openDashboardButton = JButton("Open Datadog Feed")
    private val supabaseUrlField = JBTextField()
    private val supabaseAnonKeyField = JBPasswordField()
    private val testSupabaseButton = JButton("Test Supabase connection")

    private var panel: JPanel? = null

    private val settings
        get() = ApplicationManager.getApplication().getService(DatadogProactiveSettings::class.java)

    override fun getDisplayName(): String = "Guardia"

    override fun createComponent(): JComponent {
        reset()
        openDashboardButton.addActionListener { openDashboardInBrowser() }
        testConnectionButton.addActionListener { runConnectionTest() }
        testSupabaseButton.addActionListener { runSupabaseConnectionTest() }
        val p = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("OpenAI API key:"), apiKeyField, 1, false)
            .addLabeledComponent(JBLabel("Model:"), modelField, 1, false)
            .addLabeledComponent(JBLabel("Base URL:"), baseUrlField, 1, false)
            .addComponent(useRealDatadogCheckbox)
            .addComponent(testConnectionButton)
            .addLabeledComponent(JBLabel("Demo dashboard:"), openDashboardButton, 1, false)
            .addLabeledComponent(JBLabel("Supabase URL:"), supabaseUrlField, 1, false)
            .addLabeledComponent(JBLabel("Supabase anon key:"), supabaseAnonKeyField, 1, false)
            .addComponent(testSupabaseButton)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        panel = p
        return p
    }

    /**
     * Extracts the bundled HTML dashboard + fixtures JSON to a temp directory and opens
     * the resulting index.html in the user's default browser. Offline-safe: no network calls.
     */
    private fun openDashboardInBrowser() {
        val log = Logger.getInstance(DatadogProactiveConfigurable::class.java)
        try {
            val tempDir = Files.createTempDirectory("datadog-proactive-dashboard-")
            val htmlStream = javaClass.getResourceAsStream("/dashboard/index.html")
                ?: error("Missing /dashboard/index.html on classpath")
            val fixtureStream = javaClass.getResourceAsStream("/fixtures/datadog-fixtures.json")
                ?: error("Missing /fixtures/datadog-fixtures.json on classpath")
            val htmlPath = tempDir.resolve("index.html")
            val fixturePath = tempDir.resolve("datadog-fixtures.json")
            htmlStream.use { Files.copy(it, htmlPath, StandardCopyOption.REPLACE_EXISTING) }
            fixtureStream.use { Files.copy(it, fixturePath, StandardCopyOption.REPLACE_EXISTING) }
            BrowserUtil.browse(htmlPath.toUri())
        } catch (t: Throwable) {
            log.warn("Failed to open Datadog dashboard", t)
            Messages.showErrorDialog(
                panel,
                "Could not open the Datadog demo dashboard: ${t.message}",
                "Guardia"
            )
        }
    }

    override fun isModified(): Boolean {
        val currentKey = settings.getApiKey().orEmpty()
        val enteredKey = String(apiKeyField.password)
        val enteredSupabaseKey = String(supabaseAnonKeyField.password)
        return enteredKey != currentKey ||
            modelField.text != settings.model ||
            baseUrlField.text != settings.baseUrl ||
            useRealDatadogCheckbox.isSelected != settings.useRealDatadog ||
            supabaseUrlField.text != settings.state.supabaseUrl ||
            enteredSupabaseKey != settings.state.supabaseAnonKey
    }

    private val DatadogProactiveSettings.state: DatadogProactiveSettings.State
        get() = getState()

    override fun apply() {
        val entered = String(apiKeyField.password)
        settings.setApiKey(entered.ifBlank { null })
        settings.model = modelField.text.ifBlank { "gpt-5-codex" }
        settings.baseUrl = baseUrlField.text.ifBlank { "https://api.openai.com/v1" }
        settings.state.supabaseUrl = supabaseUrlField.text.trim()
        settings.state.supabaseAnonKey = String(supabaseAnonKeyField.password).trim()

        val prev = settings.useRealDatadog
        val next = useRealDatadogCheckbox.isSelected
        settings.useRealDatadog = next

        if (prev != next) {
            if (next && !hasDatadogApiKey()) {
                notifyWarn(
                    "USE_REAL_DATADOG enabled but no DD_API_KEY found — falling back to Mock on queries."
                )
            }
            broadcastModeChange(next)
            refreshOpenEditors()
        }
    }

    // --- mode-flip machinery ---------------------------------------------------------

    /** PasswordSafe ⟶ env ⟶ .env in any open project's basePath. */
    private fun hasDatadogApiKey(): Boolean {
        val fromSafe = com.intellij.ide.passwordSafe.PasswordSafe.instance.getPassword(
            com.intellij.credentialStore.CredentialAttributes(
                com.intellij.credentialStore.generateServiceName(
                    "DatadogProactive.Datadog", "apiKey"
                )
            )
        )
        if (!fromSafe.isNullOrBlank()) return true
        if (!System.getenv("DD_API_KEY").isNullOrBlank()) return true
        return readDotEnvKey("DD_API_KEY") != null
    }

    private fun readDotEnvKey(key: String): String? {
        for (project in ProjectManager.getInstance().openProjects) {
            val base = project.basePath ?: continue
            val dotEnv = File(base, ".env")
            if (!dotEnv.isFile) continue
            try {
                dotEnv.useLines { lines ->
                    for (raw in lines) {
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("#")) continue
                        val idx = line.indexOf('=')
                        if (idx <= 0) continue
                        val k = line.substring(0, idx).trim()
                        if (k != key) continue
                        val v = line.substring(idx + 1).trim()
                            .removeSurrounding("\"").removeSurrounding("'")
                        if (v.isNotBlank()) return v
                    }
                }
            } catch (_: Throwable) { /* best-effort */ }
        }
        return null
    }

    private fun broadcastModeChange(useReal: Boolean) {
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            project.messageBus.syncPublisher(DatadogModeListener.TOPIC).onModeChanged(useReal)
        }
    }

    /**
     * Cross-cutting refresh: restart the code-analysis daemon on every open project so
     * LineMarkerProviders (gutter icons) + EditorNotificationProviders (top banner) re-query
     * the provider against the freshly-toggled flag.
     */
    private fun refreshOpenEditors() {
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }

    // --- Test connection button ------------------------------------------------------

    private fun runConnectionTest() {
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        if (project == null) {
            notifyWarn("No open project — open a project to test the Datadog connection.")
            return
        }
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Testing Datadog connection", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                val app = ApplicationManager.getApplication()
                val future = app.executeOnPooledThread<Result<List<String>>> {
                    runCatching {
                        val svc = project.getService(DatadogService::class.java)
                        val concrete = (svc as? DatadogServiceProvider)?.get() ?: svc
                        concrete.allServices().map { it.name }
                    }
                }
                val result = try {
                    future.get(5, TimeUnit.SECONDS)
                } catch (t: Throwable) {
                    Result.failure(t)
                }
                result
                    .onSuccess { names ->
                        if (names.isEmpty()) {
                            notifyWarn("Connected, but no services were returned. Check API key / mode.")
                        } else {
                            notifyInfo("Connected to Datadog — ${names.size} services reachable.")
                        }
                    }
                    .onFailure { err ->
                        notifyError("Datadog connection failed: ${err.message ?: err::class.simpleName}")
                    }
            }
        })
    }

    // --- notifications ---------------------------------------------------------------

    private fun notifyInfo(msg: String) = notify(msg, NotificationType.INFORMATION)
    private fun notifyWarn(msg: String) = notify(msg, NotificationType.WARNING)
    private fun notifyError(msg: String) = notify(msg, NotificationType.ERROR)

    private fun notify(msg: String, type: NotificationType) {
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        val group = NotificationGroupManager.getInstance()
            .getNotificationGroup("Guardia")
            ?: NotificationGroupManager.getInstance().getNotificationGroup("Balloon")
        group?.createNotification("Guardia", msg, type)?.notify(project)
    }

    override fun reset() {
        apiKeyField.text = settings.getApiKey().orEmpty()
        modelField.text = settings.model
        baseUrlField.text = settings.baseUrl
        useRealDatadogCheckbox.isSelected = settings.useRealDatadog
        supabaseUrlField.text = settings.state.supabaseUrl
        supabaseAnonKeyField.text = settings.state.supabaseAnonKey
    }

    // --- Test Supabase connection button --------------------------------------------

    private fun runSupabaseConnectionTest() {
        val url = supabaseUrlField.text.trim().trimEnd('/')
        val key = String(supabaseAnonKeyField.password).trim()
        if (url.isBlank() || key.isBlank()) {
            notifyWarn("Supabase URL and anon key are required.")
            return
        }
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Testing Supabase connection", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                val app = ApplicationManager.getApplication()
                val future = app.executeOnPooledThread<Result<Int>> {
                    runCatching {
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(3, TimeUnit.SECONDS)
                            .readTimeout(3, TimeUnit.SECONDS)
                            .build()
                        val req = okhttp3.Request.Builder()
                            .url("$url/rest/v1/")
                            .addHeader("apikey", key)
                            .addHeader("Authorization", "Bearer $key")
                            .get()
                            .build()
                        client.newCall(req).execute().use { it.code }
                    }
                }
                val result = try {
                    future.get(5, TimeUnit.SECONDS)
                } catch (t: Throwable) {
                    Result.failure(t)
                }
                result
                    .onSuccess { code ->
                        if (code in 200..299 || code == 401 || code == 404) {
                            notifyInfo("Supabase reachable (HTTP $code).")
                        } else {
                            notifyWarn("Supabase responded with HTTP $code — check URL/key.")
                        }
                    }
                    .onFailure { err ->
                        notifyError("Supabase connection failed: ${err.message ?: err::class.simpleName}")
                    }
            }
        })
    }

    override fun disposeUIResources() {
        panel = null
    }
}
