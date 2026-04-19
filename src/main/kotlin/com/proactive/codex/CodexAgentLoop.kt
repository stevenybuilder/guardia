package com.proactive.codex

import com.proactive.risk.BaselineScore
import com.proactive.risk.RiskEvent
import com.proactive.risk.RiskRequest
import kotlinx.coroutines.flow.Flow

data class CodexInput(
    val request: RiskRequest,
    val baseline: BaselineScore,
)

interface CodexAgentLoop {
    fun run(input: CodexInput): Flow<RiskEvent>
}
