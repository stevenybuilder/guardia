package com.proactive.risk

import com.proactive.domain.Incident
import com.proactive.domain.ServiceContext

fun interface RiskHeuristic {
    fun compute(
        request: RiskRequest,
        services: List<ServiceContext>,
        incidents: List<Incident>,
    ): BaselineScore
}
