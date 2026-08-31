package com.rieltor.application.orchestration

import com.rieltor.application.model.RepostFlowState
import com.rieltor.application.model.SkipReason
import com.rieltor.application.model.TelegramSourceState
import com.rieltor.application.port.PhotoRepostHandler
import com.rieltor.application.port.TelegramMessageSource
import com.rieltor.application.usecase.RepostPublishException
import com.rieltor.domain.model.RepostResult
import com.rieltor.domain.model.TelegramPhotoMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/** Sequentially observes Telegram messages and runs the application repost use case. */
class TelegramRepostCoordinator(
    private val source: TelegramMessageSource,
    private val repostHandler: PhotoRepostHandler,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val mutableState = MutableStateFlow<RepostFlowState>(RepostFlowState.Stopped)
    private var observerJob: Job? = null
    private var sourceStateObserverJob: Job? = null

    val state: StateFlow<RepostFlowState> = mutableState.asStateFlow()
    val sourceState: StateFlow<TelegramSourceState> = source.state

    fun start() {
        if (!started.compareAndSet(false, true)) return
        mutableState.value = RepostFlowState.WaitingForMessage
        sourceStateObserverJob = scope.launch {
            source.state.collect { sourceState ->
                logger.info("Telegram source state changed: {}", sourceState)
            }
        }
        observerJob = scope.launch {
            try {
                source.messages.collect { message ->
                    scope.launch { process(message) }
                }
                if (!closing.get()) {
                    mutableState.value = RepostFlowState.Failed(null, "Telegram message stream completed")
                }
            } catch (error: Throwable) {
                if (!closing.get()) {
                    logger.error("Telegram message observer failed", error)
                    mutableState.value = RepostFlowState.Failed(null, error.failureReason())
                }
            }
        }

        try {
            source.start()
        } catch (error: Throwable) {
            observerJob?.cancel()
            sourceStateObserverJob?.cancel()
            runCatching(source::close)
                .onFailure(error::addSuppressed)
            scope.cancel()
            mutableState.value = RepostFlowState.Failed(null, error.failureReason())
            throw error
        }
    }

    private suspend fun process(message: TelegramPhotoMessage) {
        logger.info("Independent repost processing started. updateId={}", message.updateId)
        mutableState.value = RepostFlowState.Processing(message.updateId)
        try {
            when (val result = repostHandler.handle(message)) {
                is RepostResult.Published -> {
                    result.receipts.forEach { receipt -> logger.info(
                        "Telegram/Google Drive photo post completed. destination={}, updateId={}, publishId={}, creator={}, privacy={}",
                        receipt.destination, message.updateId, receipt.publishId, receipt.creatorName, receipt.privacyLevel,
                    ) }
                    result.failures.forEach { failure -> logger.error(
                        "Independent repost destination failed. destination={}, updateId={}, reason={}",
                        failure.destination, message.updateId, failure.reason,
                    ) }
                    mutableState.value = RepostFlowState.Published(message.updateId, result.receipt.publishId)
                }
                RepostResult.Duplicate -> {
                    logger.info("Telegram listing skipped as duplicate. updateId={}", message.updateId)
                    mutableState.value = RepostFlowState.Skipped(message.updateId, SkipReason.DUPLICATE)
                }
                RepostResult.IgnoredSource ->
                    mutableState.value = RepostFlowState.Skipped(message.updateId, SkipReason.UNMONITORED_SOURCE)
                RepostResult.IgnoredContent ->
                    mutableState.value = RepostFlowState.Skipped(message.updateId, SkipReason.UNSUPPORTED_CONTENT)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RepostPublishException) {
            logger.error(
                "Failed to repost Telegram message. updateId={}, reason={}",
                message.updateId,
                error.failureReason(),
            )
            mutableState.value = RepostFlowState.Failed(message.updateId, error.failureReason())
        } catch (error: Throwable) {
            logger.error("Failed to repost Telegram message {}", message.updateId, error)
            mutableState.value = RepostFlowState.Failed(message.updateId, error.failureReason())
        }
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        runCatching(source::close)
            .onFailure { logger.warn("Could not close Telegram message source cleanly", it) }
        observerJob?.cancel()
        sourceStateObserverJob?.cancel()
        scope.cancel()
        mutableState.value = RepostFlowState.Stopped
    }

    private fun Throwable.failureReason(): String = message?.takeIf(String::isNotBlank)
        ?: javaClass.simpleName
}
