package com.prankcraft.effects;

import com.prankcraft.PrankCraftMod;
import com.prankcraft.prank.PrankEffect;
import com.prankcraft.util.Text;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundChatPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Prints a line in chat that looks like the target said it. Port of
 * {@code com.prankcraft.effects.FakeChatEffect}.
 *
 * <p><b>This is the one effect in the module that can genuinely mislead a third party, so it is
 * fenced in on four sides:</b>
 * <ul>
 *   <li>the lines come from your config, so they are always your words, not the player's;</li>
 *   <li>they are cleaned of colour codes and {@code [Tag]} prefixes and capped in length, so
 *       nobody can forge an {@code [Admin]} prefix or a fake plugin message;</li>
 *   <li>the target is excluded from the broadcast and is told it happened, so they are never
 *       gaslit about their own chat log;</li>
 *   <li>every use is written to the audit log with the exact text that was shown, which is what
 *       makes "somebody made it look like I said that" answerable rather than arguable.</li>
 * </ul>
 *
 * <p>What this deliberately does NOT do: touch the real player's chat, their name, their tab-list
 * entry or their skin. The packet carries the target's UUID only so a client that resolves skins
 * from chat renders the right head; nothing about the player's actual session changes. Keep the
 * list silly - a prank line should make people laugh at the sender, never at a person who is not
 * in the room.
 */
public final class FakeChatEffect implements PrankEffect {

    private static final int MAX_LENGTH = 200;

    private final PrankCraftMod mod;
    private final Random random = new Random();

    public FakeChatEffect(PrankCraftMod mod) {
        this.mod = mod;
    }

    @Override
    public String id() {
        return "fake-chat";
    }

    @Override
    public String description() {
        return "Someone appears to say a silly configured line. The target is told, so they are never gaslit.";
    }

    @Override
    public boolean enabled() {
        return mod.config().fakeChatEnabled.get();
    }

    @Override
    public int defaultDurationSeconds() {
        return 0;
    }

    @Override
    public int durationSeconds() {
        return 0;
    }

    @Override
    public String fire(ServerPlayer target) {
        List<? extends String> configured = mod.config().fakeChatMessages.get();
        if (configured == null || configured.isEmpty()) {
            return null;
        }
        String raw = configured.get(random.nextInt(configured.size()));
        String body = Text.sanitise(Text.translate(raw), MAX_LENGTH);
        if (body.isEmpty()) {
            return null;
        }

        // The vanilla chat format is "<name> message", and that is what a real player's line
        // renders as, so this is indistinguishable from a genuine message. That is the entire
        // point of the effect - and the entire reason for the audit record.
        Component line = Text.fromLegacy("&f<" + target.getGameProfile().getName() + "> &r" + body);

        int shown = 0;
        for (ServerPlayer viewer : onlineViewers(target)) {
            if (viewer.getUUID().equals(target.getUUID())) {
                continue;
            }
            // ChatType.CHAT = 0, the "player said something" type. A vanilla client renders the
            // component we built above, so viewers see a normal chat line.
            viewer.connection.send(new ClientboundChatPacket(line, ChatType.CHAT, target.getUUID()));
            shown++;
        }

        Text.prefixed(target, "&7A prank line was just shown in chat as you: &f" + body);
        return "said=\"" + body + "\" shown-to=" + shown;
    }

    /** Every player on the server: chat is global, so the fake line has to be too. */
    private List<ServerPlayer> onlineViewers(ServerPlayer target) {
        List<ServerPlayer> viewers = new ArrayList<>();
        if (mod.server() != null) {
            viewers.addAll(mod.server().getPlayerList().getPlayers());
        }
        return viewers;
    }
}
