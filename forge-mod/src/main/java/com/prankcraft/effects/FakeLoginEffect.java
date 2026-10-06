package com.prankcraft.effects;

import com.prankcraft.PrankCraftMod;
import com.prankcraft.prank.PrankEffect;
import com.prankcraft.util.Text;
import com.mojang.authlib.GameProfile;
import net.minecraft.Util;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.protocol.game.ClientboundChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Emits a join or leave line for somebody who is not actually connected. Port of
 * {@code com.prankcraft.effects.FakeLoginEffect}.
 *
 * <p>When the target is online this only ever emits a <em>leave</em> line, and when they are
 * offline only a <em>join</em> line - so the fake message can never contradict the real one
 * sitting right above it. Used well, this is the setup for "wait, I thought you left?"; used
 * carelessly it just looks like a broken server.
 *
 * <p>The target themselves is always excluded from the broadcast. Nobody is ever told that they
 * personally left the game while sitting in front of it.
 *
 * <p><b>Why there are two entry points.</b> The engine's contract is "effects act on a live
 * player", and every other effect needs one. This effect has an offline half as well - a fake
 * join line is precisely for somebody who is not here - so that half takes a
 * {@link GameProfile} instead. Making it a separate method rather than a nullable parameter
 * keeps the "no effect ever acts on a player who is not connected" rule readable at the call
 * site.
 */
public final class FakeLoginEffect implements PrankEffect {

    private final PrankCraftMod mod;

    public FakeLoginEffect(PrankCraftMod mod) {
        this.mod = mod;
    }

    @Override
    public String id() {
        return "fake-login";
    }

    @Override
    public String description() {
        return "Fakes a join/leave message for the target, never contradicting the real one.";
    }

    @Override
    public boolean enabled() {
        return mod.config().fakeLoginEnabled.get();
    }

    @Override
    public int defaultDurationSeconds() {
        return 0;
    }

    @Override
    public int durationSeconds() {
        return 0;
    }

    /** The only effect that is useful when the target is not connected - that is the point. */
    @Override
    public boolean worksOffline() {
        return true;
    }

    /** Target is online, so this emits a leave line and nothing else. */
    @Override
    public String fire(ServerPlayer target) {
        MinecraftServer server = mod.server();
        if (server == null || target == null) {
            return null;
        }
        String name = target.getGameProfile().getName();
        String rendered = Text.translate(mod.config().fakeLoginLogoutMessage.get().replace("{player}", name));
        int shown = broadcast(server, target.getUUID(), rendered);
        mod.announce("&7Fake leave line emitted for &f" + name);
        return "fake-leave name=" + name + " shown-to=" + shown;
    }

    /**
     * Target is offline, so this emits a join line and nothing else.
     *
     * <p>The line itself is a plain SYSTEM chat packet, which every vanilla client renders. The
     * tab-list half of a real join is deliberately not faked: {@code ADD_PLAYER} carries the
     * profile of a live session, and inventing one is exactly the kind of client-side lie this
     * module refuses to tell (see README.md, "what this mod will not do"). The visible result is
     * a join line with nobody actually appearing - which reads as a player with a bad connection,
     * and is a prank rather than an impersonation.
     */
    public String fireOffline(MinecraftServer server, GameProfile profile) {
        if (server == null || profile == null) {
            return null;
        }
        String name = profile.getName();
        String rendered = Text.translate(mod.config().fakeLoginLoginMessage.get().replace("{player}", name));
        int shown = broadcast(server, profile.getId(), rendered);
        mod.announce("&7Fake join line emitted for offline player &f" + name);
        return "fake-join-offline name=" + name + " shown-to=" + shown;
    }

    /** Sends one rendered line to everybody except {@code excluded}. Returns how many saw it. */
    private int broadcast(MinecraftServer server, UUID excluded, String rendered) {
        int shown = 0;
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            if (excluded != null && viewer.getUUID().equals(excluded)) {
                continue;
            }
            // ChatType.SYSTEM is what vanilla uses for join/leave lines. NIL_UUID means "no
            // player wrote this", which is true: the server is emitting it, exactly as it would
            // for a real join.
            viewer.connection.send(new ClientboundChatPacket(
                    Text.fromLegacy(rendered), ChatType.SYSTEM, Util.NIL_UUID));
            shown++;
        }
        return shown;
    }
}
