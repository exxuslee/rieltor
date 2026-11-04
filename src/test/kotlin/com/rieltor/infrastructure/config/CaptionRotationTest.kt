package com.rieltor.infrastructure.config

import com.rieltor.domain.model.BalancedCaptionVariant.*
import com.rieltor.domain.model.ConversationalCaptionVariant.*
import com.rieltor.domain.model.RepostCaptionStyle.*
import com.rieltor.domain.model.RepostDestination.THREADS
import com.rieltor.domain.model.RepostDestination.TIKTOK
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptionRotationTest {
    @Test fun `legacy settings start at standard and destinations rotate independently across restarts`() {
        val path = Files.createTempDirectory("caption-rotation").resolve("settings.json")
        Files.writeString(path, "{}")
        JsonSettingsStore(path).use { store ->
            assertEquals(STANDARD, store.reserveCaption("t1", TIKTOK).style)
            assertEquals(STANDARD, store.reserveCaption("t1", TIKTOK).style)
            assertEquals(CONVERSATIONAL, store.reserveCaption("t2", TIKTOK).style)
            assertEquals(STANDARD, store.reserveCaption("h1", THREADS).style)
        }
        JsonSettingsStore(path).use { store ->
            assertEquals(CONVERSATIONAL, store.reserveCaption("t2", TIKTOK).style)
            assertEquals(BALANCED, store.reserveCaption("t3", TIKTOK).style)
            assertEquals(STANDARD, store.reserveCaption("t4", TIKTOK).style)
            assertEquals(CONVERSATIONAL, store.reserveCaption("h2", THREADS).style)
            assertEquals(BALANCED, store.reserveCaption("h3", THREADS).style)
            assertEquals(STANDARD, store.reserveCaption("h4", THREADS).style)
        }
    }

    @Test fun `conversation variants cycle only on conversation sends and persist idempotently`() {
        val path = Files.createTempDirectory("conversation-variants").resolve("settings.json")
        // Settings saved before variants existed must still load.
        Files.writeString(path, """{"tiktokCaptionRotation":{"nextStyle":"CONVERSATIONAL"}}""")
        JsonSettingsStore(path).use { store ->
            val first = store.reserveCaption("first", TIKTOK)
            assertEquals(DIRECT, first.conversationalVariant)
            assertEquals(first, store.reserveCaption("first", TIKTOK))
            assertNull(store.reserveCaption("balanced", TIKTOK).conversationalVariant)
            assertNull(store.reserveCaption("standard", TIKTOK).conversationalVariant)
        }
        JsonSettingsStore(path).use { store ->
            val second = store.reserveCaption("second", TIKTOK)
            assertEquals(SEARCH, second.conversationalVariant)
            assertEquals(second, store.reserveCaption("second", TIKTOK))
            store.reserveCaption("threads-standard", THREADS)
            assertEquals(DIRECT, store.reserveCaption("threads-first", THREADS).conversationalVariant)
            store.reserveCaption("balanced2", TIKTOK)
            store.reserveCaption("standard2", TIKTOK)
            assertEquals(PERSONAL, store.reserveCaption("third", TIKTOK).conversationalVariant)
            store.reserveCaption("balanced3", TIKTOK)
            store.reserveCaption("standard3", TIKTOK)
            assertEquals(DIRECT, store.reserveCaption("fourth", TIKTOK).conversationalVariant)
        }
    }

    @Test fun `balanced variants persist and cycle independently of conversational variants and destinations`() {
        val path = Files.createTempDirectory("balanced-variants").resolve("settings.json")
        Files.writeString(path, """{"tiktokCaptionRotation":{"nextStyle":"BALANCED","nextConversationalVariant":"SEARCH"}}""")
        JsonSettingsStore(path).use { store ->
            val first = store.reserveCaption("first", TIKTOK)
            assertEquals(STRUCTURED, first.balancedVariant)
            assertNull(first.conversationalVariant)
            assertEquals(first, store.reserveCaption("first", TIKTOK))
            assertNull(store.reserveCaption("standard", TIKTOK).balancedVariant)
            val conversation = store.reserveCaption("conversation", TIKTOK)
            assertEquals(SEARCH, conversation.conversationalVariant)
            assertNull(conversation.balancedVariant)
        }
        JsonSettingsStore(path).use { store ->
            val second = store.reserveCaption("second", TIKTOK)
            assertEquals(FEATURES, second.balancedVariant)
            assertEquals(second, store.reserveCaption("second", TIKTOK))
            store.reserveCaption("threads-standard", THREADS)
            store.reserveCaption("threads-conversation", THREADS)
            assertEquals(STRUCTURED, store.reserveCaption("threads-balanced", THREADS).balancedVariant)
            store.reserveCaption("standard2", TIKTOK)
            assertEquals(PERSONAL, store.reserveCaption("conversation2", TIKTOK).conversationalVariant)
            assertEquals(OVERVIEW, store.reserveCaption("third", TIKTOK).balancedVariant)
            store.reserveCaption("standard3", TIKTOK)
            store.reserveCaption("conversation3", TIKTOK)
            assertEquals(STRUCTURED, store.reserveCaption("fourth", TIKTOK).balancedVariant)
        }
    }
}
