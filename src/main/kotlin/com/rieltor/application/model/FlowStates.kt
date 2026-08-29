package com.rieltor.application.model

sealed interface TelegramSourceState {
    data object Stopped : TelegramSourceState
    data object Starting : TelegramSourceState
    data object AwaitingAuthorization : TelegramSourceState
    data object Ready : TelegramSourceState
    data class Failed(val reason: String) : TelegramSourceState
}

sealed interface RepostFlowState {
    data object Stopped : RepostFlowState
    data object WaitingForMessage : RepostFlowState
    data class Processing(val updateId: Long) : RepostFlowState
    data class Published(val updateId: Long, val publishId: String) : RepostFlowState
    data class Skipped(val updateId: Long, val reason: SkipReason) : RepostFlowState
    data class Failed(val updateId: Long?, val reason: String) : RepostFlowState
}

enum class SkipReason {
    DUPLICATE,
    UNMONITORED_SOURCE,
    UNSUPPORTED_CONTENT,
}
