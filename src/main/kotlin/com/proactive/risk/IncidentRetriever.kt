package com.proactive.risk

import com.proactive.domain.Incident
import kotlin.math.ln

/**
 * Retrieves the top-5 historical incidents most relevant to a proposed diff.
 *
 * Pipeline (ARCHITECTURE.md §3.1):
 *   Stage 1 — STRUCTURAL  : incident.offendingMethods ∩ diff.methods, or file endsWith match.
 *                           Getafix-style — the highest-precision signal in this corpus.
 *   Stage 2 — BM25        : over (description + rootCause + keywords), query = methods+keywords.
 *   Stage 3 — RRF (k=60)  : reciprocal-rank fusion; structural hits dominate, lexical fills tail.
 *
 * Pure Kotlin, no IntelliJ imports. Backs the Codex `get_related_incidents` tool and the
 * `IncidentsRetrieved` streaming event.
 */
fun interface IncidentRetriever {
    fun retrieve(
        methods: List<String>,
        keywords: List<String>,
        touchedFiles: List<String>,
    ): List<Incident>
}

class DefaultIncidentRetriever(
    private val corpus: List<Incident>,
    private val topK: Int = 5,
    private val rrfK: Int = 60,
) : IncidentRetriever {

    // BM25 index rebuilt once at construction. For a 20-doc corpus this is trivial; for a
    // Phase-5 Datadog v2 ingest the same data structures scale to a few thousand docs before
    // disk-backed Lucene becomes worthwhile (see ARCHITECTURE.md §3.1 migration note).
    private val bm25: Bm25Index = Bm25Index.build(
        corpus.map { inc ->
            val text = buildString {
                append(inc.description)
                if (!inc.rootCause.isNullOrBlank()) { append(' '); append(inc.rootCause) }
                for (k in inc.keywords) { append(' '); append(k) }
            }
            inc.id to text
        },
    )

    override fun retrieve(
        methods: List<String>,
        keywords: List<String>,
        touchedFiles: List<String>,
    ): List<Incident> {
        if (corpus.isEmpty()) return emptyList()

        // Stage 1 — structural match preserves corpus order.
        val methodSet = methods.toSet()
        val structural = corpus.filter { inc ->
            inc.offendingMethods.any { it in methodSet } ||
                inc.offendingFiles.any { f ->
                    touchedFiles.any { t -> f == t || f.endsWith(t) || t.endsWith(f) }
                }
        }.map { it.id }

        // Stage 2 — BM25. Query = methods + keywords tokenized the same way as the corpus.
        val queryTokens = tokenize((methods + keywords).joinToString(" "))
        val bm25Ranked = bm25.topK(queryTokens, topK)

        // Stage 3 — RRF fusion.
        val fused = rrf(listOf(structural, bm25Ranked), rrfK).take(topK)

        val byId = corpus.associateBy { it.id }
        return fused.mapNotNull { byId[it] }
    }

    /**
     * Reciprocal-Rank Fusion. `k` is the smoothing constant from the LanceDB / Cormack paper
     * (60 is the standard default). Ties broken by first appearance across the input lists.
     */
    internal fun rrf(rankings: List<List<String>>, k: Int): List<String> {
        val scores = LinkedHashMap<String, Double>()
        for (ranking in rankings) {
            ranking.forEachIndexed { idx, id ->
                val contribution = 1.0 / (k + idx + 1)
                scores.merge(id, contribution) { a, b -> a + b }
            }
        }
        return scores.entries.sortedByDescending { it.value }.map { it.key }
    }
}

// ───────────────────────────────────── BM25 ─────────────────────────────────────
//
// Okapi BM25 with the standard hyperparameters (k1 = 1.5, b = 0.75). ~80 lines of
// pure Kotlin. Tokenizer is deliberately cheap: lowercase + split on non-alphanumerics.
// That's enough signal for 20 hand-authored incidents where the vocabulary is small and
// the demo queries lean on distinctive terms like "retry", "null", "race".

internal fun tokenize(s: String): List<String> {
    if (s.isBlank()) return emptyList()
    val out = ArrayList<String>()
    val buf = StringBuilder()
    for (ch in s) {
        if (ch.isLetterOrDigit()) {
            buf.append(ch.lowercaseChar())
        } else if (buf.isNotEmpty()) {
            out.add(buf.toString()); buf.setLength(0)
        }
    }
    if (buf.isNotEmpty()) out.add(buf.toString())
    return out
}

internal class Bm25Index private constructor(
    private val docIds: List<String>,
    private val termFreqs: List<Map<String, Int>>,
    private val docLengths: IntArray,
    private val avgDl: Double,
    private val idf: Map<String, Double>,
    private val k1: Double,
    private val b: Double,
) {
    fun topK(queryTokens: List<String>, k: Int): List<String> {
        if (queryTokens.isEmpty() || docIds.isEmpty()) return emptyList()
        val qTerms = queryTokens.filter { it in idf }
        if (qTerms.isEmpty()) return emptyList()
        val scored = DoubleArray(docIds.size)
        for (term in qTerms.toSet()) {
            val termIdf = idf[term] ?: continue
            for (i in docIds.indices) {
                val tf = termFreqs[i][term] ?: continue
                val dl = docLengths[i].toDouble()
                val denom = tf + k1 * (1 - b + b * dl / avgDl)
                scored[i] += termIdf * (tf * (k1 + 1) / denom)
            }
        }
        // Return only positive-scoring docs, highest first.
        val indexed = scored.mapIndexed { i, s -> i to s }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(k)
        return indexed.map { docIds[it.first] }
    }

    companion object {
        fun build(
            corpus: List<Pair<String, String>>,
            k1: Double = 1.5,
            b: Double = 0.75,
        ): Bm25Index {
            val docIds = corpus.map { it.first }
            val tokenized = corpus.map { tokenize(it.second) }
            val docLengths = IntArray(tokenized.size) { tokenized[it].size }
            val avgDl = if (docLengths.isEmpty()) 0.0 else docLengths.average()

            val termFreqs = tokenized.map { tokens ->
                val m = HashMap<String, Int>(tokens.size)
                for (t in tokens) m.merge(t, 1) { a, _ -> a + 1 }
                m
            }

            val df = HashMap<String, Int>()
            for (tf in termFreqs) for (term in tf.keys) df.merge(term, 1) { a, _ -> a + 1 }

            val n = docIds.size.toDouble()
            val idf = HashMap<String, Double>(df.size)
            for ((term, dfi) in df) {
                // Standard BM25 IDF with +0.5 smoothing; max(epsilon, …) to avoid negatives
                // flipping the score sign for very common terms in small corpora.
                val raw = ln(1.0 + (n - dfi + 0.5) / (dfi + 0.5))
                idf[term] = raw.coerceAtLeast(0.0)
            }

            return Bm25Index(
                docIds = docIds,
                termFreqs = termFreqs,
                docLengths = docLengths,
                avgDl = avgDl,
                idf = idf,
                k1 = k1,
                b = b,
            )
        }
    }
}
