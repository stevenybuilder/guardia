package com.stevenyang.datadogproactive.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the line-level match algorithm inside [ProactiveRiskHighlighter].
 *
 * These exercise `normalizeLine`, `isTrivialLine`, and `matchSnippetLines` directly without
 * booting the IntelliJ test fixture — the algorithm has no PSI dependencies.
 */
class ProactiveRiskHighlighterMatchTest {

    @Test
    fun `2 plus line match returns matched line indices`() {
        val bodyText = """
            {
                var order = repo.find(id);
                var userId = order.getUser().getId();
                var amount = order.getAmount();
                charge(userId, amount);
            }
        """.trimIndent()

        // Snippet has two distinct non-trivial lines that both appear verbatim in the body.
        val snippet = """
            var userId = order.getUser().getId();
            charge(userId, amount);
        """.trimIndent()

        val matches = ProactiveRiskHighlighter.matchSnippetLines(bodyText, 100, snippet)
        assertEquals("Expected 2 line matches", 2, matches.size)
        // Offsets must be body-relative + base offset (100).
        assertTrue("Match offsets should respect base offset", matches.all { it.startOffset >= 100 })
        // No two matches should share start offset (distinct claimed lines).
        assertEquals(
            "Each body line is claimed at most once",
            matches.map { it.startOffset }.toSet().size,
            matches.size,
        )
    }

    @Test
    fun `threshold not met returns empty matches when no line matches`() {
        val bodyText = """
            {
                log.info("totally unrelated body");
                return "ok";
            }
        """.trimIndent()
        val snippet = """
            var x = parseInt(raw);
            validateRange(x);
            persist(x);
        """.trimIndent()

        val matches = ProactiveRiskHighlighter.matchSnippetLines(bodyText, 0, snippet)
        assertTrue("No lines match → empty list", matches.isEmpty())
    }

    @Test
    fun `fuzzy Jaccard catches overlapping-but-not-identical lines`() {
        // Body line uses different whitespace, operator spacing, and variable name — but
        // 3 of 5 tokens overlap with the snippet line. Should be ≥0.4 Jaccard.
        val bodyText = """
            {
                country = customer.getBillingAddress().getCountry();
            }
        """.trimIndent()
        // Fixture-style line — different AST, same conceptual tokens.
        val snippet = "country = customer.billingAddress.country;"

        val matches = ProactiveRiskHighlighter.matchSnippetLines(bodyText, 0, snippet)
        assertTrue(
            "Fuzzy match should fire when keywords overlap (got ${matches.size} matches)",
            matches.isNotEmpty(),
        )
    }

    @Test
    fun `jaccard returns expected similarity for overlapping token sets`() {
        val a = ProactiveRiskHighlighter.tokenize("country = customer.getBillingAddress().getCountry()")
        val b = ProactiveRiskHighlighter.tokenize("country = customer.billingAddress.country")
        val sim = ProactiveRiskHighlighter.jaccard(a, b)
        assertTrue("Jaccard ≥ threshold for overlapping tokens (got $sim)", sim >= 0.4)
    }

    @Test
    fun `tokenize filters short tokens and lowercases`() {
        val toks = ProactiveRiskHighlighter.tokenize("var x = customer.getId()")
        assertTrue("Drops single-char tokens like 'x' and '='", !toks.contains("x"))
        assertTrue("Keeps real identifiers", toks.contains("customer"))
        assertTrue("Lowercases", toks.contains("getid"))
    }

    @Test
    fun `normalization strips comments and collapses whitespace`() {
        val raw = "    var   userId =   order.getUser().getId();   // grab id  "
        val normalized = ProactiveRiskHighlighter.normalizeLine(raw)
        assertFalse("Trailing comment stripped", normalized.contains("//"))
        assertFalse("Trailing semicolon trimmed", normalized.endsWith(";"))
        assertFalse("No runs of multiple spaces", normalized.contains("  "))
        assertEquals("var userId = order.getUser().getId()", normalized)
        assertNotEquals("Normalized form differs from raw", raw.trim(), normalized)
    }

    @Test
    fun `trivial lines excluded from matching`() {
        // Snippet is entirely trivial — all braces + empty. Should yield ZERO matches.
        val trivialSnippet = """
            {
            }
            return;
        """.trimIndent()

        val bodyText = """
            {
                doRealWork();
            }
        """.trimIndent()

        val matches = ProactiveRiskHighlighter.matchSnippetLines(bodyText, 0, trivialSnippet)
        assertTrue("Trivial-only snippets produce no matches", matches.isEmpty())

        // Direct trivial predicates.
        assertTrue(ProactiveRiskHighlighter.isTrivialLine(ProactiveRiskHighlighter.normalizeLine("{")))
        assertTrue(ProactiveRiskHighlighter.isTrivialLine(ProactiveRiskHighlighter.normalizeLine("}")))
        assertTrue(ProactiveRiskHighlighter.isTrivialLine(ProactiveRiskHighlighter.normalizeLine("return;")))
        assertTrue(ProactiveRiskHighlighter.isTrivialLine(ProactiveRiskHighlighter.normalizeLine("   ")))
        assertFalse(
            "Non-trivial line not flagged",
            ProactiveRiskHighlighter.isTrivialLine(
                ProactiveRiskHighlighter.normalizeLine("var userId = order.getUser().getId()")
            )
        )
    }
}
