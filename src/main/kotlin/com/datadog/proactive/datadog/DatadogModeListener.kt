package com.datadog.proactive.datadog

import com.intellij.util.messages.Topic

/**
 * MessageBus contract broadcast when the USE_REAL_DATADOG flag flips. Subscribers (risk
 * tool window, banner) can rerender; the Configurable also calls DaemonCodeAnalyzer.restart
 * to refresh gutter icons, so subscribing here is only needed for non-Daemon surfaces.
 */
fun interface DatadogModeListener {
    fun onModeChanged(useRealDatadog: Boolean)

    companion object {
        @JvmField
        val TOPIC: Topic<DatadogModeListener> = Topic.create(
            "Datadog Proactive mode changed",
            DatadogModeListener::class.java,
        )
    }
}
