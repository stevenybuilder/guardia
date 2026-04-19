package com.datadog.proactive.datadog

import com.datadog.proactive.fixtures.Incident
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.stevenyang.datadogproactive.settings.DatadogProactiveSettings

/**
 * Runtime-switchable DatadogService facade.
 *
 * Registered in plugin.xml as the implementation bound to the [DatadogService] interface.
 * Every call to a [DatadogService] method resolves the backing impl AT CALL TIME by reading
 * [DatadogProactiveSettings.useRealDatadog] — flipping the settings checkbox swaps the
 * active impl without restarting the plugin.
 *
 * Delegation pattern (not composition at construction time): the active impl is recomputed
 * on every method invocation. This is O(1) because IntelliJ's service registry caches the
 * concrete-class lookup after first resolution — we only pay one HashMap get per call.
 *
 * When `useRealDatadog == true` but the Real impl is missing (parallel agent still writing
 * RealDatadogService.kt) the provider degrades gracefully to Mock so the demo path never
 * blows up. This is the "Mock is the default in every run" rule (CLAUDE.md §Rules #7).
 */
@Service(Service.Level.PROJECT)
class DatadogServiceProvider(private val project: Project) : DatadogService {

    private val log = Logger.getInstance(DatadogServiceProvider::class.java)

    private val settings: DatadogProactiveSettings
        get() = ApplicationManager.getApplication()
            .getService(DatadogProactiveSettings::class.java)

    /**
     * Returns the currently-active backing DatadogService. Recomputed per call — flag flips
     * are live with no restart.
     *
     * Callers outside this class can use this to obtain the raw impl (e.g. the Settings
     * "Test Datadog connection" button).
     */
    fun get(): DatadogService {
        if (settings.useRealDatadog) {
            val real = loadRealServiceOrNull()
            if (real != null) return real
            log.warn("useRealDatadog=true but RealDatadogService not available; falling back to Mock.")
        }
        return project.getService(MockDatadogService::class.java)
    }

    /**
     * Reflection-based lookup so this file does not compile-depend on the parallel agent's
     * RealDatadogService.kt. Once that class lands, the service registry returns it normally.
     */
    private fun loadRealServiceOrNull(): DatadogService? {
        return try {
            val klass = Class.forName("com.datadog.proactive.datadog.RealDatadogService")
            @Suppress("UNCHECKED_CAST")
            project.getService(klass as Class<Any>) as? DatadogService
        } catch (_: ClassNotFoundException) {
            null
        } catch (t: Throwable) {
            log.warn("Failed to resolve RealDatadogService: ${t.message}")
            null
        }
    }

    // --- DatadogService delegation --------------------------------------------------

    override fun getServiceContext(service: String) = get().getServiceContext(service)

    override fun getMethodContext(fqName: String) = get().getMethodContext(fqName)

    override fun isInDeployWindow(service: String) = get().isInDeployWindow(service)

    override fun getRelatedIncidents(methods: List<String>, keywords: List<String>): List<Incident> =
        get().getRelatedIncidents(methods, keywords)

    override fun findIncidentsByCodePattern(pattern: String): List<CodePatternMatch> =
        get().findIncidentsByCodePattern(pattern)

    override fun allServices() = get().allServices()

    override fun allMethodsOfInterest() = get().allMethodsOfInterest()

    override fun getMethodDetail(fqName: String) = get().getMethodDetail(fqName)
}
