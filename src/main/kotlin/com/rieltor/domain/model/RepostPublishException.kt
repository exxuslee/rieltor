package com.rieltor.domain.model

class RepostPublishException(val failures: List<RepostFailure>) : Exception(
    failures.joinToString("; ") { "${it.destination}: ${it.reason}" }
)