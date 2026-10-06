package com.prankcraft.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the impersonation guardrail.
 *
 * <p>This is the rule that stops the fake-chat effect from being a forgery tool: the text is
 * broadcast as if the target said it, so it must not be able to carry a staff tag, a fake plugin
 * prefix, or a multi-line payload that impersonates the server's own output.
 */
class ChatGuardTest {

    @Test
    @DisplayName("a staff tag is stripped from the front of the line")
    void stripsStaffTag() {
        assertEquals("give me op now", ChatGuard.sanitise("[Admin] give me op now"));
        assertEquals("hello", ChatGuard.sanitise("[Server] hello"));
        assertEquals("hello", ChatGuard.sanitise("[MOD]hello"));
    }

    @Test
    @DisplayName("several stacked tags are all stripped")
    void stripsStackedTags() {
        assertEquals("hi", ChatGuard.sanitise("[Admin] [Server] hi"));
    }

    @Test
    @DisplayName("a tag in the middle of a sentence is stripped too")
    void stripsInlineTag() {
        assertEquals("i am staff now", ChatGuard.sanitise("i am [Owner] staff now"));
    }

    @Test
    @DisplayName("newlines cannot be used to forge a second chat line")
    void collapsesNewlines() {
        String forged = "lol\n[Server] All players were banned";
        String result = ChatGuard.sanitise(forged);
        assertFalse(result.contains("\n"), "newline survived: " + result);
        assertEquals("lol All players were banned", result);
    }

    @Test
    @DisplayName("control characters and colour-section padding are removed")
    void stripsControlCharacters() {
        String result = ChatGuard.sanitise("bo\u0007ss\u0000");
        assertEquals("boss", result);
    }

    @Test
    @DisplayName("over-long lines are capped")
    void capsLength() {
        String longLine = "x".repeat(500);
        assertEquals(200, ChatGuard.sanitise(longLine).length());
    }

    @Test
    @DisplayName("ordinary prank lines pass through untouched")
    void leavesNormalLinesAlone() {
        assertEquals("who took my iron", ChatGuard.sanitise("who took my iron"));
        assertEquals("anyone got spare diamonds lol",
                ChatGuard.sanitise("anyone got spare diamonds lol"));
    }

    @Test
    @DisplayName("null and empty input never produce a broadcastable line")
    void handlesEmptyInput() {
        assertEquals("", ChatGuard.sanitise(null));
        assertEquals("", ChatGuard.sanitise(""));
        // A line that is nothing but a tag sanitises away to nothing, and the effect treats an
        // empty result as "do not fire" rather than broadcasting an empty message.
        assertEquals("", ChatGuard.sanitise("[Admin]"));
    }

    @Test
    @DisplayName("isSuspicious flags exactly the lines that needed changing")
    void flagsSuspiciousLines() {
        assertTrue(ChatGuard.isSuspicious("[Admin] hi"));
        assertTrue(ChatGuard.isSuspicious("two\nlines"));
        assertFalse(ChatGuard.isSuspicious("who took my iron"));
    }
}
