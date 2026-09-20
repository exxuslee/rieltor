package com.rieltor.application.model

sealed interface TelegramSourceState {
    data object Stopped : TelegramSourceState
    data object Starting : TelegramSourceState
    data object AwaitingAuthorization : TelegramSourceState
    data object Ready : TelegramSourceState
    data class Failed(val reason: String) : TelegramSourceState
}
