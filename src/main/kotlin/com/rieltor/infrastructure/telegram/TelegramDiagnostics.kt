package com.rieltor.infrastructure.telegram

import com.rieltor.domain.model.TelegramMonitoredTopic
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.jni.TdApi
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

internal class TelegramDiagnostics(
    private val monitoredTopics: Set<TelegramMonitoredTopic>,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun logStartupSnapshot(telegramClient: SimpleTelegramClient) {
        logAuthorizedAccount(telegramClient)
        logAvailableTelegramChats(telegramClient)
        logMonitoredTopics(telegramClient)
    }

    private fun logAuthorizedAccount(telegramClient: SimpleTelegramClient) {
        runCatching {
            telegramClient.send(TdApi.GetMe()).get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.onSuccess { user ->
            logger.info(
                "Telegram authorized account: userId={}, name='{} {}', username={}",
                user.id,
                user.firstName,
                user.lastName,
                user.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" } ?: "none",
            )
        }.onFailure { error ->
            logger.warn("Could not read the authorized Telegram account", error)
        }
    }

    private fun logAvailableTelegramChats(telegramClient: SimpleTelegramClient) {
        val chatLists = listOf(TdApi.ChatListMain(), TdApi.ChatListArchive())
        chatLists.forEach { chatList ->
            for (batch in 0 until CHAT_LOAD_BATCH_COUNT) {
                val loaded = runCatching {
                    telegramClient.send(TdApi.LoadChats(chatList, CHAT_LOAD_BATCH_SIZE))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }.isSuccess
                if (!loaded) break
            }
        }

        val chatIds = linkedSetOf<Long>()
        chatLists.forEach { chatList ->
            runCatching {
                telegramClient.send(TdApi.GetChats(chatList, AVAILABLE_CHAT_LOG_LIMIT))
                    .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrNull()?.chatIds?.forEach(chatIds::add)
        }

        val supergroupChats = chatIds.mapNotNull { chatId ->
            runCatching { getChat(telegramClient, chatId) }.getOrNull()
        }.mapNotNull { chat ->
            val type = chat.type as? TdApi.ChatTypeSupergroup ?: return@mapNotNull null
            chat to type
        }

        logger.info("Telegram available supergroup chats: count={}", supergroupChats.size)
        supergroupChats.forEach { (chat, type) ->
            val supergroup = runCatching {
                telegramClient.send(TdApi.GetSupergroup(type.supergroupId))
                    .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrNull()
            val membership = supergroup?.status?.javaClass?.simpleName
                ?.removePrefix("ChatMemberStatus") ?: "unknown"
            val kind = when {
                supergroup?.isForum == true -> "forum"
                type.isChannel -> "channel"
                else -> "group"
            }
            logger.info(
                "Telegram available chat: chatId={}, supergroupId={}, type={}, title='{}', membership={}",
                chat.id,
                type.supergroupId,
                kind,
                chat.title,
                membership,
            )
        }
    }

    private fun logMonitoredTopics(telegramClient: SimpleTelegramClient) {
        monitoredTopics
            .filter { it.messageThreadId == null }
            .forEach { monitored ->
                logger.info("Telegram monitoring check: all messages in chatId={} are monitored", monitored.chatId)
            }

        monitoredTopics
            .mapTo(linkedSetOf()) { it.chatId }
            .forEach { chatId ->
                runCatching {
                    ensureChatLoaded(telegramClient, chatId)
                    val chat = getChat(telegramClient, chatId)
                    val chatType = chat.type as? TdApi.ChatTypeSupergroup
                        ?: return@runCatching null
                    val supergroup = telegramClient.send(TdApi.GetSupergroup(chatType.supergroupId))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    if (!supergroup.isForum) return@runCatching null

                    readAllForumTopics(telegramClient, chatId)
                }.onSuccess { forumTopics ->
                    if (forumTopics == null) return@onSuccess
                    val selectedTopics = forumTopics.topics.filter { topic ->
                        isTopicMonitored(chatId, topic.info.messageThreadId)
                    }
                    logger.info(
                        "Telegram monitoring check: chatId={}, configuredTopics={}, foundTopics={}, totalTopics={}",
                        chatId,
                        monitoredTopics.filter { it.chatId == chatId }.map { it.messageThreadId },
                        selectedTopics.size,
                        forumTopics.totalCount,
                    )
                    forumTopics.topics.forEach { topic ->
                        val lastMessage = topic.lastMessage
                        logger.info(
                            "Telegram forum topic: chatId={}, messageThreadId={}, name='{}', monitored={}, " +
                                "lastMessageId={}, lastMessage={}",
                            chatId,
                            topic.info.messageThreadId,
                            topic.info.name,
                            isTopicMonitored(chatId, topic.info.messageThreadId),
                            lastMessage?.id,
                            lastMessage?.summary() ?: "no messages",
                        )
                    }
                    val foundTopicIds = forumTopics.topics.map { it.info.messageThreadId }.toSet()
                    val missingTopicIds = monitoredTopics
                        .asSequence()
                        .filter {
                            it.chatId == chatId &&
                                it.messageThreadId != null &&
                                it.messageThreadId !in foundTopicIds
                        }
                        .map { it.messageThreadId }
                        .toList()
                    if (missingTopicIds.isNotEmpty()) {
                        logger.warn(
                            "Configured Telegram topic IDs were not found in chat {}: {}",
                            chatId,
                            missingTopicIds,
                        )
                    }
                }.onFailure { error ->
                    logger.warn(
                        "Could not read Telegram forum topics for monitored chat {}. " +
                            "Verify that this TDLib account is a member of the chat and that the parent chat ID is correct.",
                        chatId,
                        error,
                    )
                }
            }
    }

    private fun readAllForumTopics(
        telegramClient: SimpleTelegramClient,
        chatId: Long,
    ): ForumTopicSnapshot {
        val topicsById = linkedMapOf<Long, TdApi.ForumTopic>()
        val seenOffsets = mutableSetOf<ForumTopicOffset>()
        var offset = ForumTopicOffset(0, 0, 0)
        var totalCount = 0

        while (seenOffsets.add(offset)) {
            val page = telegramClient.send(
                TdApi.GetForumTopics(
                    chatId,
                    "",
                    offset.date,
                    offset.messageId,
                    offset.messageThreadId,
                    FORUM_TOPIC_PAGE_SIZE,
                )
            ).get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            totalCount = maxOf(totalCount, page.totalCount)
            page.topics.forEach { topic -> topicsById[topic.info.messageThreadId] = topic }

            if (page.topics.isEmpty() || topicsById.size >= totalCount) break
            offset = ForumTopicOffset(
                page.nextOffsetDate,
                page.nextOffsetMessageId,
                page.nextOffsetMessageThreadId,
            )
        }

        return ForumTopicSnapshot(totalCount, topicsById.values.toList())
    }

    private fun isTopicMonitored(chatId: Long, messageThreadId: Long): Boolean =
        monitoredTopics.any { monitored -> monitored.matches(chatId, messageThreadId) }

    private fun ensureChatLoaded(telegramClient: SimpleTelegramClient, chatId: Long) {
        if (runCatching { getChat(telegramClient, chatId) }.isSuccess) return

        val supergroupId = telegramChatIdToSupergroupId(chatId)
        if (supergroupId != null) {
            val createdChat = runCatching {
                telegramClient.send(TdApi.CreateSupergroupChat(supergroupId, true))
                    .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrNull()
            if (createdChat?.id == chatId) {
                logger.info(
                    "Telegram supergroup chat {} was loaded directly from supergroup ID {}",
                    chatId,
                    supergroupId,
                )
                return
            }
        }

        logger.info("Telegram chat {} is not in the local TDLib database; loading cloud chat lists", chatId)
        val chatLists = listOf(TdApi.ChatListMain(), TdApi.ChatListArchive())
        for (chatList in chatLists) {
            for (batch in 0 until CHAT_LOAD_BATCH_COUNT) {
                val loaded = runCatching {
                    telegramClient.send(TdApi.LoadChats(chatList, CHAT_LOAD_BATCH_SIZE))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }.isSuccess

                if (runCatching { getChat(telegramClient, chatId) }.isSuccess) return
                if (!loaded) break
            }
        }

        runCatching { getChat(telegramClient, chatId) }.getOrElse { error ->
            logAvailableSupergroupChats(telegramClient)
            throw error
        }
    }

    private fun getChat(telegramClient: SimpleTelegramClient, chatId: Long): TdApi.Chat =
        telegramClient.send(TdApi.GetChat(chatId))
            .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun telegramChatIdToSupergroupId(chatId: Long): Long? {
        if (chatId >= TELEGRAM_SUPERGROUP_CHAT_ID_OFFSET) return null
        return -chatId + TELEGRAM_SUPERGROUP_CHAT_ID_OFFSET
    }

    private fun logAvailableSupergroupChats(telegramClient: SimpleTelegramClient) {
        val chatIds = linkedSetOf<Long>()
        listOf(TdApi.ChatListMain(), TdApi.ChatListArchive()).forEach { chatList ->
            runCatching {
                telegramClient.send(TdApi.GetChats(chatList, AVAILABLE_CHAT_LOG_LIMIT))
                    .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrNull()?.chatIds?.forEach(chatIds::add)
        }

        val supergroups = chatIds.mapNotNull { availableChatId ->
            runCatching { getChat(telegramClient, availableChatId) }.getOrNull()
        }.filter { it.type is TdApi.ChatTypeSupergroup }

        if (supergroups.isEmpty()) {
            logger.warn("TDLib account has no available supergroup chats in its main or archived chat lists")
            return
        }
        supergroups.forEach { chat ->
            val type = chat.type as TdApi.ChatTypeSupergroup
            logger.info(
                "Telegram available supergroup: chatId={}, supergroupId={}, title='{}', isChannel={}",
                chat.id,
                type.supergroupId,
                chat.title,
                type.isChannel,
            )
        }
    }

    private companion object {
        const val FORUM_TOPIC_PAGE_SIZE = 100
        const val CHAT_LOAD_BATCH_SIZE = 100
        const val CHAT_LOAD_BATCH_COUNT = 20
        const val AVAILABLE_CHAT_LOG_LIMIT = 200
        const val TELEGRAM_SUPERGROUP_CHAT_ID_OFFSET = -1_000_000_000_000L
        const val REQUEST_TIMEOUT_SECONDS = 30L
    }

    private data class ForumTopicOffset(
        val date: Int,
        val messageId: Long,
        val messageThreadId: Long,
    )

    private data class ForumTopicSnapshot(
        val totalCount: Int,
        val topics: List<TdApi.ForumTopic>,
    )
}
