package com.axiom.launcher.solis

import com.axiom.launcher.solis.engine.Redactor
import com.axiom.launcher.solis.llm.ChatMessage
import com.axiom.launcher.solis.llm.ChatRole
import com.axiom.launcher.solis.llm.ContentPart
import com.axiom.launcher.solis.llm.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    private fun redact(vararg texts: String) = Redactor.redact("system", texts.map { ChatMessage.user(it) })

    @Test
    fun hidesEmailsPhonesCardsAndIbans() {
        val r = redact(
            "Mail ana.silva@example.pt or call +351 912 345 678. Card 4111 1111 1111 1111, IBAN DE89 3704 0044 0532 0130 00. " +
                "US office (555) 123-4567, Berlin 030 1234 5678.",
        )
        val sent = r.messages.single().text
        listOf("ana.silva@example.pt", "912 345 678", "4111 1111 1111 1111", "DE89", "123-4567", "1234 5678").forEach {
            assertFalse("$it leaked: $sent", sent.contains(it))
        }
        assertTrue(sent, sent.contains("⟦EMAIL_1⟧") && sent.contains("⟦CARD_1⟧") && sent.contains("⟦IBAN_1⟧"))
        assertEquals(mapOf("email" to 1, "iban" to 1, "card" to 1, "phone" to 3), r.redaction.counts)
        assertTrue(r.redaction.restore(sent).contains("ana.silva@example.pt"))
        assertTrue(r.redaction.restore(sent).contains("+351 912 345 678"))
    }

    @Test
    fun leavesOrdinaryNumbersDatesAndTimesAlone() {
        val text = "Meeting 2026-10-27 14:30, budget 1,250,000 EUR, order 123456, version 2.1.0, 3 kids, room 4021, card 4111 1111 1111 1112."
        val r = redact(text)
        assertEquals("nothing personal here (and the card number fails its checksum)", text, r.messages.single().text)
        assertEquals(0, r.redaction.total)
    }

    @Test
    fun numberingIsStableAcrossTurnsSoCachingKeepsWorking() {
        val turn1 = listOf(ChatMessage.user("Email bob@x.io about it"))
        val turn2 = turn1 + ChatMessage.assistant("Drafted it for bob@x.io.") + ChatMessage.user("Also cc eve@y.io and bob@x.io")
        val a = Redactor.redact("sys", turn1)
        val b = Redactor.redact("sys", turn2)
        assertEquals("earlier messages redact identically", a.messages[0].text, b.messages[0].text)
        assertEquals("Drafted it for ⟦EMAIL_1⟧.", b.messages[1].text)
        assertEquals("Also cc ⟦EMAIL_2⟧ and ⟦EMAIL_1⟧", b.messages[2].text)
    }

    @Test
    fun toolArgumentsAndResultsAreCoveredBothWays() {
        val call = ToolCall("c1", "compose_email", """{"to":"mia@corp.com","subject":"Hi"}""")
        val msgs = listOf(
            ChatMessage.user("Write to Mia"),
            ChatMessage(ChatRole.ASSISTANT, emptyList(), listOf(call)),
            ChatMessage.toolResult(call, "Contact: Mia, mia@corp.com, +44 20 7946 0958", false),
        )
        val r = Redactor.redact("sys", msgs)
        assertEquals("""{"to":"⟦EMAIL_1⟧","subject":"Hi"}""", r.messages[1].toolCalls.single().argumentsJson)
        assertEquals("Contact: Mia, ⟦EMAIL_1⟧, ⟦PHONE_1⟧", r.messages[2].text)
        // What the model asks for comes back with the real values before any tool runs.
        assertEquals("""{"to":"mia@corp.com"}""", r.redaction.restore("""{"to":"⟦EMAIL_1⟧"}"""))
        assertEquals("unknown placeholders stay as written", "⟦EMAIL_9⟧", r.redaction.restore("⟦EMAIL_9⟧"))
    }

    @Test
    fun imagesPassThroughUntouched() {
        val img = ContentPart.Image("image/jpeg", "AAAA")
        val r = Redactor.redact("sys", listOf(ChatMessage(ChatRole.USER, listOf(ContentPart.Text("me@x.io"), img))))
        assertEquals(img, r.messages.single().parts[1])
        assertEquals("⟦EMAIL_1⟧", (r.messages.single().parts[0] as ContentPart.Text).text)
    }
}
