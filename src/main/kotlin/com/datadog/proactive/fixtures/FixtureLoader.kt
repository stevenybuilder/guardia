package com.datadog.proactive.fixtures

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Application-level service. Loads the frozen fixture JSON once at plugin startup via
 * getResourceAsStream and hands the parsed root off to FixtureIndex for hashmap index
 * construction.
 *
 * Decision (2026-04-18 Decision Log): JSON on disk + in-memory Kotlin HashMap indexes.
 * Rejected SQLite/CSV/Parquet — zero judging upside, adds deps/demo risk.
 *
 * Do not move or re-serialize the fixture. Regeneration path:
 *   python3 scripts/generate-fixtures.py
 */
@Service(Service.Level.APP)
class FixtureLoader {

    private val log = logger<FixtureLoader>()

    /** Lazy so a plain-JVM test can construct this without the IntelliJ platform bootstrap. */
    val index: FixtureIndex by lazy {
        val built = FixtureIndex.build(load())
        log.info(
            "FixtureLoader ready: services=${built.root.services.size} " +
                "incidents=${built.root.incidents.size} " +
                "methods_of_interest=${built.root.methods_of_interest.size} " +
                "events=${built.root.events.size}"
        )
        built
    }

    fun load(): FixtureRoot {
        val stream = javaClass.getResourceAsStream(RESOURCE_PATH)
            ?: error("Fixture not found on classpath: $RESOURCE_PATH")
        val json = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(FixtureRoot::class.java)
        return adapter.fromJson(json) ?: error("Failed to parse $RESOURCE_PATH")
    }

    companion object {
        const val RESOURCE_PATH = "/fixtures/datadog-fixtures.json"

        fun getInstance(): FixtureLoader =
            ApplicationManager.getApplication().getService(FixtureLoader::class.java)
    }
}
