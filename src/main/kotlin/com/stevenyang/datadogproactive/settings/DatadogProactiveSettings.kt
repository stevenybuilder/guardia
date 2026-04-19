package com.stevenyang.datadogproactive.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.APP)
@State(
    name = "com.stevenyang.datadogproactive.settings.DatadogProactiveSettings",
    storages = [Storage("datadog-proactive.xml")]
)
class DatadogProactiveSettings : PersistentStateComponent<DatadogProactiveSettings.State> {

    data class State(
        var model: String = "gpt-5-codex",
        var baseUrl: String = "https://api.openai.com/v1",
        var useRealDatadog: Boolean = false,
        var offlineMode: Boolean = false,
        var supabaseUrl: String = "",
        var supabaseAnonKey: String = ""
    )

    private var state: State = State()

    override fun getState(): State = state
    override fun loadState(s: State) = XmlSerializerUtil.copyBean(s, state)

    var model: String
        get() = System.getenv("OPENAI_MODEL")?.takeIf { it.isNotBlank() } ?: state.model
        set(v) { state.model = v }

    var baseUrl: String
        get() = System.getenv("OPENAI_BASE_URL")?.takeIf { it.isNotBlank() } ?: state.baseUrl
        set(v) { state.baseUrl = v }

    var useRealDatadog: Boolean
        get() = state.useRealDatadog
        set(v) { state.useRealDatadog = v }

    /** When true, skip live OpenAI call — go straight from cache to pre-recorded fallback.
     * Demo-day insurance. Defaults to false; live-first is the dev experience. */
    var offlineMode: Boolean
        get() = state.offlineMode
        set(v) { state.offlineMode = v }

    /** Supabase project URL (e.g. `https://<proj>.supabase.co`). Env fallback:
     * `GUARDIA_SUPABASE_URL`. Blank → audit client is a no-op. */
    var supabaseUrl: String
        get() = state.supabaseUrl.ifBlank {
            System.getenv("GUARDIA_SUPABASE_URL")?.takeIf { it.isNotBlank() }.orEmpty()
        }
        set(v) { state.supabaseUrl = v }

    /** Supabase anon (public) API key. Env fallback: `GUARDIA_SUPABASE_ANON_KEY`. Stored
     * in plain state because the anon key is designed to be public (RLS enforces auth). */
    var supabaseAnonKey: String
        get() = state.supabaseAnonKey.ifBlank {
            System.getenv("GUARDIA_SUPABASE_ANON_KEY")?.takeIf { it.isNotBlank() }.orEmpty()
        }
        set(v) { state.supabaseAnonKey = v }

    /** API key lives in PasswordSafe, never in serialized state. */
    fun getApiKey(): String? =
        PasswordSafe.instance.getPassword(CREDS)
            ?: System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }

    fun setApiKey(v: String?) = PasswordSafe.instance.setPassword(CREDS, v)

    companion object {
        private val CREDS = CredentialAttributes(
            generateServiceName("DatadogProactive.OpenAI", "api-key")
        )
    }
}
