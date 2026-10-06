package com.prankcraft.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * Chat and title helpers, mirroring {@code com.prankcraft.util.Text} in the Paper module.
 *
 * <p>The Paper version deliberately avoided the Adventure API so one jar could span many
 * server versions. Forge 1.16.5 has exactly one chat API, so this class is thin - but it
 * exists for the same reason: every message this mod sends goes through here, which is the
 * one place to audit for "does anything here pretend to be the server?"
 *
 * <p>It does not. {@link #fromLegacy} renders a player-supplied line as plain component text
 * with colour codes only; a prank line can never produce a {@code [Server]} prefix, a
 * clickable command, or a translatable staff tag. {@link #sanitise} strips those shapes out
 * of configured text before it ever reaches a component.
 */
public final class Text {

    /** Colour-code character players can type as {@code &}; translated to {@code §}. */
    private static final char SECTION = '\u00A7';

    private Text() {
    }

    /**
     * Turns {@code &}-style colour codes into a component.
     *
     * <p>Only formatting codes survive. The result is pure styled text - never a translatable
     * component, never a score, never a selector - so no configured string can impersonate a
     * vanilla system message.
     */
    public static Component fromLegacy(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new TextComponent("");
        }
        return new TextComponent(translate(raw));
    }

    /** Replaces {@code &x} with the section sign, exactly like Bukkit's colour translator. */
    public static String translate(String raw) {
        if (raw == null) {
            return "";
        }
        char[] chars = raw.toCharArray();
        for (int i = 0; i + 1 < chars.length; i++) {
            if (chars[i] == '&' && "0123456789AaBbCcDdEeFfKkLlMmNnOoRr".indexOf(chars[i + 1]) >= 0) {
                chars[i] = SECTION;
                chars[i + 1] = Character.toLowerCase(chars[i + 1]);
            }
        }
        return new String(chars);
    }

    /** Plain text with every colour code removed. Used for length checks and logging. */
    public static String strip(String raw) {
        String translated = translate(raw);
        StringBuilder sb = new StringBuilder(translated.length());
        for (int i = 0; i < translated.length(); i++) {
            char c = translated.charAt(i);
            if (c == SECTION && i + 1 < translated.length()) {
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static void msg(ServerPlayer to, String raw) {
        if (to == null || raw == null || raw.isEmpty()) {
            return;
        }
        to.sendMessage(fromLegacy(raw), net.minecraft.Util.NIL_UUID);
    }

    public static void prefixed(ServerPlayer to, String raw) {
        msg(to, "&8[&dPrank&8] &r" + raw);
    }

    /**
     * Strips anything that could impersonate the server, a plugin or staff, then caps the
     * length. Same intent as the Paper implementation: the fake-chat lines come from config,
     * and this makes sure a careless config entry cannot forge a prefix.
     */
    public static String sanitise(String input, int maxLength) {
        if (input == null) {
            return "";
        }
        String cleaned = input
                .replaceAll("(?i)\\s*\\[[^\\]]{0,24}\\]\\s*", " ")   // [Admin] / [Server] style prefixes
                .replaceAll("[\\p{Cntrl}&&[^\n]]", "")
                .replace('\n', ' ')
                .trim();
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }
}
