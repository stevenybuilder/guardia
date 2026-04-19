package com.proactive.domain

data class ServiceContext(
    val name: String,
    val errorRate1h: Double,
    val errorRateDeltaVsLastCommit: Double,
    val directDepCount: Int,
    val changeVelocity7d: Int,
    val deployWindowToday: Boolean,
    val openIncidentCount: Int,
)

data class Incident(
    val id: String,
    val service: String,
    val status: Status,
    val severity: String,
    val title: String,
    val description: String,
    val rootCause: String?,
    val offendingMethods: List<String>,
    val offendingFiles: List<String>,
    val offendingCodeSnippet: String?,
    val keywords: List<String>,
    val openedAt: String,
    val resolvedAt: String?,
) {
    enum class Status { OPEN, RESOLVED }
}

data class Diff(
    val text: String,
    val touchedFiles: List<String>,
    val linesChanged: Int,
)
