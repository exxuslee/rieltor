package com.rieltor.application.orchestration

import com.rieltor.application.model.RepostFlowState
import com.rieltor.application.model.SkipReason
import com.rieltor.application.model.TelegramSourceState
import com.rieltor.application.port.PhotoRepostHandler
import com.rieltor.application.port.TelegramMessageSource
import com.rieltor.application.usecase.RepostPublishException
import com.rieltor.domain.model.RepostResult
import com.rieltor.domain.model.TelegramListing
import com.rieltor.domain.repository.QueueEnqueueResult
import com.rieltor.domain.repository.TelegramRepostQueue
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/** Persistent FIFO coordinator and the only owner of the cross-destination dispatch quota. */
class TelegramRepostCoordinator(
    private val source: TelegramMessageSource,
    private val repostHandler: PhotoRepostHandler,
    private val queue: TelegramRepostQueue = InMemoryTelegramRepostQueue(),
    private val masterLimiter: RepostMasterLimiter = RepostMasterLimiter { },
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val mutableState = MutableStateFlow<RepostFlowState>(RepostFlowState.Stopped)
    private val queueSignal = Channel<Unit>(Channel.CONFLATED)
    private var observerJob: Job? = null
    private var sourceStateObserverJob: Job? = null
    private var workerJob: Job? = null

    val state: StateFlow<RepostFlowState> = mutableState.asStateFlow()
    val sourceState: StateFlow<TelegramSourceState> = source.state

    fun start() {
        if (!started.compareAndSet(false, true)) return
        require(queueCapacity > 0) { "Repost queue capacity must be positive." }
        require(retryDelayMillis >= 0) { "Repost retry delay must not be negative." }
        mutableState.value = RepostFlowState.WaitingForMessage
        queue.recoverInterrupted()

        sourceStateObserverJob = scope.launch {
            source.state.collect { sourceState -> logger.info("Telegram source state changed: {}", sourceState) }
        }
        workerJob = scope.launch {
            for (ignored in queueSignal) {
                while (!closing.get()) {
                    val message = queue.peekOldest() ?: break
                    if (!process(message)) break
                }
            }
        }
        observerJob = scope.launch {
            try {
                source.messages.collect(::accept)
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
            queueSignal.trySend(Unit)
        } catch (error: Throwable) {
            observerJob?.cancel()
            sourceStateObserverJob?.cancel()
            workerJob?.cancel()
            runCatching(source::close).onFailure(error::addSuppressed)
            scope.cancel()
            mutableState.value = RepostFlowState.Failed(null, error.failureReason())
            throw error
        }
    }

    private fun accept(message: TelegramListing) {
        when {
            message.normalizedPrice.isNullOrBlank() -> {
                queue.reject(message, STATUS_REJECTED_NO_PRICE)
                mutableState.value = RepostFlowState.Skipped(message.updateId, SkipReason.MISSING_PRICE)
            }
            message.googleDriveLinks.isEmpty() -> {
                queue.reject(message, STATUS_REJECTED_NO_DRIVE)
                mutableState.value = RepostFlowState.Skipped(message.updateId, SkipReason.MISSING_GOOGLE_DRIVE_LINK)
            }
            else -> {
                val result = queue.enqueue(message, queueCapacity)
                result.droppedUpdateId?.let { dropped ->
                    logger.warn(
                        "Persistent repost queue is full; oldest pending message was dropped. updateId={}, queueCapacity={}",
                        dropped,
                        queueCapacity,
                    )
                }
                queueSignal.trySend(Unit)
            }
        }
    }

    /** Returns true when the FIFO head was finalized and the next row may start. */
    private suspend fun process(message: TelegramListing): Boolean {
        logger.info("Persistent FIFO repost processing started. updateId={}", message.updateId)
        mutableState.value = RepostFlowState.Processing(message.updateId)
        return try {
            masterLimiter.awaitSlot()
            when (val result = repostHandler.handle(message)) {
                is RepostResult.Published -> {
                    result.receipts.forEach { receipt -> logger.info(
                        "Telegram/Google Drive photo post completed. destination={}, updateId={}, publishId={}, creator={}, privacy={}",
                        receipt.destination, message.updateId, receipt.publishId, receipt.creatorName, receipt.privacyLevel,
                    ) }
                    if (result.failures.isNotEmpty()) {
                        val reason = result.failures.joinToString("; ") { "${it.destination}: ${it.reason}" }
                        retry(message.updateId, reason)
                        false
                    } else {
                        queue.complete(message.updateId, STATUS_PUBLISHED)
                        mutableState.value = RepostFlowState.Published(message.updateId, result.receipt.publishId)
                        true
                    }
                }
                RepostResult.Duplicate -> {
                    queue.complete(message.updateId, STATUS_PUBLISHED)
                    mutableState.value = RepostFlowState.Skipped(message.updateId, SkipReason.DUPLICATE)
                    true
                }
                RepostResult.IgnoredSource -> {
                    queue.complete(message.updateId, STATUS_IGNORED_SOURCE)
                    mutableState.value = RepostFlowState.Skipped(message.updateId, SkipReason.UNMONITORED_SOURCE)
                    true
                }
                RepostResult.IgnoredContent -> {
                    queue.complete(message.updateId, STATUS_IGNORED_CONTENT)
                    mutableState.value = RepostFlowState.Skipped(message.updateId, SkipReason.UNSUPPORTED_CONTENT)
                    true
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RepostPublishException) {
            logger.error("Failed to repost Telegram message. updateId={}, reason={}", message.updateId, error.failureReason())
            retry(message.updateId, error.failureReason())
            false
        } catch (error: Throwable) {
            logger.error("Failed to repost Telegram message {}", message.updateId, error)
            retry(message.updateId, error.failureReason())
            false
        }
    }

    private fun retry(updateId: Long, reason: String) {
        queue.markRetryPending(updateId, reason)
        mutableState.value = RepostFlowState.Failed(updateId, reason)
        scope.launch {
            delay(retryDelayMillis)
            queueSignal.trySend(Unit)
        }
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        runCatching(source::close).onFailure { logger.warn("Could not close Telegram message source cleanly", it) }
        observerJob?.cancel()
        sourceStateObserverJob?.cancel()
        workerJob?.cancel()
        queueSignal.close()
        scope.cancel()
        (queue as? AutoCloseable)?.let { runCatching(it::close) }
        mutableState.value = RepostFlowState.Stopped
    }

    private fun Throwable.failureReason(): String = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName

    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 64
        const val DEFAULT_RETRY_DELAY_MILLIS = 60_000L
        const val STATUS_REJECTED_NO_PRICE = "REJECTED_NO_PRICE"
        const val STATUS_REJECTED_NO_DRIVE = "REJECTED_NO_GOOGLE_DRIVE_LINK"
        const val STATUS_PUBLISHED = "PUBLISHED"
        const val STATUS_IGNORED_SOURCE = "IGNORED_SOURCE"
        const val STATUS_IGNORED_CONTENT = "IGNORED_CONTENT"
    }
}

private class InMemoryTelegramRepostQueue : TelegramRepostQueue, AutoCloseable {
    private val messages = ArrayDeque<TelegramListing>()
    private var claimedUpdateId: Long? = null

    override fun recoverInterrupted() = Unit

    override fun enqueue(listing: TelegramListing, capacity: Int): QueueEnqueueResult = synchronized(messages) {
        val pendingCount = messages.count { it.updateId != claimedUpdateId }
        val dropped = if (pendingCount >= capacity) {
            messages.firstOrNull { it.updateId != claimedUpdateId }?.also(messages::remove)
        } else {
            null
        }
        dropped?.closePhotos()
        messages.addLast(listing)
        QueueEnqueueResult(dropped?.updateId)
    }

    override fun peekOldest(): TelegramListing? = synchronized(messages) {
        messages.firstOrNull()?.also { claimedUpdateId = it.updateId }
    }

    override fun complete(updateId: Long, status: String) {
        synchronized(messages) {
            val message = messages.firstOrNull()
            if (message?.updateId == updateId) messages.removeFirst()
            if (claimedUpdateId == updateId) claimedUpdateId = null
            message?.closePhotos()
        }
    }

    override fun reject(listing: TelegramListing, status: String) = listing.closePhotos()
    override fun markRetryPending(updateId: Long, reason: String) = Unit
    override fun cleanHistoryBefore(cutoffEpochSeconds: Long): Int = 0

    override fun close() = synchronized(messages) {
        messages.forEach(TelegramListing::closePhotos)
        messages.clear()
        claimedUpdateId = null
    }
}

private fun TelegramListing.closePhotos() {
    photos.forEach { photo -> runCatching { photo.content.close() } }
}
