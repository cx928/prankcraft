package com.prankcraft.util;

import java.util.regex.Pattern;

/**
 * Sanitiser for text that PrankCraft is about to show <em>as if another player wrote it</em>.
 *
 * <p>This exists because the fake-chat effect is the one feature here that can mislead a third
 * party about what somebody said. The line itself comes from the operator's config, so it is
 * already not the player's words - but a careless line could still impersonate the server:
 * {@code [Admin] give me op} in chat, next to a real staff broadcast, would be indistinguishable
 * to most players, and that is exactly the kind of thing a prank plugin must refuse to do.
 *
 * <p>So bracket prefixes are stripped, control characters are removed, newlines are collapsed
 * (a multi-line payload can forge a chat layout), and the result is length-capped.
 *
 * <p>Separated from the effect and unit tested, because a guardrail that is not tested is a
 * guardrail that quietly stops working. The end-to-end suite asserts the same property against a
 * live client as a second, independent check.
 */
public final class ChatGuard {

    /** Anything that looks like a rank or channel tag: "[Admin]", "[Server]", "[MOD]". */
    private static final Pattern BRACKET_PREFIX = Pattern.compile("\\s*\\[[^\\]]{0,24}\\]\\s*");

    /** Control characters except newline, which is handled separately. */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\n]]");

    private static final int MAX_LENGTH = 200;

    private ChatGuard() {
    }

    /**
     * @param input a raw config line, possibly already colour-translated
     * @return text that is safe to broadcast as another player's chat message
     */
    public static String sanitise(String input) {
        if (input == null) {
            return "";
        }
        String cleaned = BRACKET_PREFIX.matcher(input).replaceAll(" ");
        cleaned = CONTROL.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replace('\n', ' ').replace('\r', ' ').trim();
        // Collapse the runs of spaces that prefix-stripping leaves behind, so the result reads
        // like a sentence rather than a gap.
        cleaned = cleaned.replaceAll(" {2,}", " ");
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned;
    }

    /** True when the sanitiser would have changed something. Useful for warnings and tests. */
    public static boolean isSuspicious(String input) {
        return input != null && !sanitise(input).equals(input.strip());
    }
}
