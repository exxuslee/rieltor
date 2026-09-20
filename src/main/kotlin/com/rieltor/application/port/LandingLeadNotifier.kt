package com.rieltor.application.port

import com.rieltor.domain.model.LandingLead

/** Delivers a validated landing lead to the sales operator. */
interface LandingLeadNotifier {
    suspend fun notify(lead: LandingLead): Boolean
}
