package com.rieltor.application.orchestration

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class PublicationContext(val listingId: Long, val attemptId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PublicationContext>
}
