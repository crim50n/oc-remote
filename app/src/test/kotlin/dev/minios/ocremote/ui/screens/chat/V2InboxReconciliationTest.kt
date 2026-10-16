package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.data.api.PromptPart
import dev.minios.ocremote.data.api.V2InboxItem
import dev.minios.ocremote.domain.model.TimeInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V2InboxReconciliationTest {
    @Test
    fun `restored prompt preserves remote identity and creation time without item owned selection`() {
        val item = inbox("""{"text":"hello","agent":"build","model":{"providerID":"p","modelID":"m"}}""")
        val record = requireNotNull(item.toPendingPromptRecord())

        assertEquals(item.id, record.messageId)
        assertEquals(item.sessionId, record.sessionId)
        assertEquals(item.time.created, record.createdAt)
        assertEquals(listOf(PromptPart(type = "text", text = "hello")), record.parts)
        assertNull(record.agent)
        assertNull(record.model)
        assertNull(record.variant)
        assertNull(record.directory)
        assertEquals(record, item.toPendingPromptRecord())
    }

    @Test
    fun `files and agent mentions restore even with blank text`() {
        val record = requireNotNull(inbox("""{
            "text":"  ",
            "files":[{"uri":"file:///tmp/report.txt","name":"report.txt"},{"uri":"https://example.test/image","mime":"image/png"}],
            "agents":[{"name":"reviewer"}]
        }""").toPendingPromptRecord())

        assertEquals(
            listOf(
                PromptPart(type = "file", url = "file:///tmp/report.txt", filename = "report.txt", mime = "application/octet-stream"),
                PromptPart(type = "file", url = "https://example.test/image", mime = "image/png"),
                PromptPart(type = "agent", text = "reviewer"),
            ),
            record.parts,
        )
        assertNull(record.agent)
    }

    @Test
    fun `non user inbox items never become pending user prompts`() {
        for (type in listOf("control", "synthetic", "compaction", "move", "agent-switched", "model-switched", "assistant", "future-type")) {
            assertNull(type, inbox("""{"text":"not a user prompt"}""", type).toPendingPromptRecord())
        }
    }

    @Test
    fun `strings retain whitespace escapes and literal json without extra quoting`() {
        val record = requireNotNull(inbox("""{
            "text":"  explain \"quoted\"\nC:\\work\\file {\"key\":true}  ",
            "files":[{"uri":"content://docs/a%20b?x=1&y=2","name":"a \"b\".txt"}],
            "agents":[{"name":"team/reviewer"}]
        }""").toPendingPromptRecord())

        assertEquals("  explain \"quoted\"\nC:\\work\\file {\"key\":true}  ", record.parts[0].text)
        assertEquals("content://docs/a%20b?x=1&y=2", record.parts[1].url)
        assertEquals("a \"b\".txt", record.parts[1].filename)
        assertEquals("team/reviewer", record.parts[2].text)
    }

    @Test
    fun `empty prompts and unsupported skills are not restored partially`() {
        for (payload in listOf(
            """{}""",
            """{"text":" \n ","files":[],"agents":[]}""",
            """{"text":"hello","skills":[{"name":"unknown-skill","extra":"data"}]}""",
            """{"text":"hello","skills":{"unknown":"shape"}}""",
        )) {
            assertNull(payload, inbox(payload).toPendingPromptRecord())
        }
        assertEquals(
            listOf(PromptPart(type = "text", text = "hello")),
            inbox("""{"text":"hello","skills":[]}""").toPendingPromptRecord()?.parts,
        )
    }

    @Test
    fun `malformed prompt fields do not throw or silently lose attachments`() {
        for (payload in listOf(
            """{"text":123}""",
            """{"text":"hello","files":{}}""",
            """{"text":"hello","files":[{"name":"missing uri"}]}""",
            """{"text":"hello","files":[{"uri":false}]}""",
            """{"text":"hello","files":[{"uri":"file:///tmp/a","name":123}]}""",
            """{"text":"hello","agents":["reviewer"]}""",
            """{"text":"hello","agents":[{"name":""}]}""",
        )) {
            assertNull(payload, inbox(payload).toPendingPromptRecord())
        }
    }

    @Test
    fun `attachment only prompts work without text for every delivery mode`() {
        val item = inbox("""{"agents":[{"name":"reviewer"}]}""")
        for (delivery in listOf("steer", "queue", "unknown")) {
            assertEquals(
                listOf(PromptPart(type = "agent", text = "reviewer")),
                item.copy(delivery = delivery).toPendingPromptRecord()?.parts,
            )
        }
        assertEquals(
            listOf(PromptPart(type = "file", url = "file:///tmp/a", mime = "application/octet-stream")),
            inbox("""{"text":"","files":[{"uri":"file:///tmp/a"}]}""").toPendingPromptRecord()?.parts,
        )
    }

    private fun inbox(payload: String, type: String = "user") = V2InboxItem(
        id = "inbox_remote_123",
        sessionId = "session_remote_456",
        type = type,
        payload = Json.parseToJsonElement(payload).jsonObject,
        delivery = "queue",
        time = TimeInfo(created = 1_726_000_123_456, completed = 1_726_000_999_999),
    )
}
