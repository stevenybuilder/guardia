package com.stevenyang.datadogproactive.util

/**
 * Minimal HTML-escape utility for strings interpolated into Swing HTML
 * contexts (tooltips, JLabel text, tool-window panels).
 *
 * Fixture data is synthetic + safe, but the RealDatadogService path may
 * surface arbitrary incident titles/descriptions that could contain
 * angle brackets or embedded HTML fragments. Swing's HTML renderer will
 * happily render them, so every user-sourced string that lands inside
 * an `<html>...</html>` block must go through [escape] first.
 *
 * Pure function; EDT-safe.
 */
object Html {
    fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
