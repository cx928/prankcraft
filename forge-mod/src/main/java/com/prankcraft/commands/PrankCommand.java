package com.prankcraft.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.prankcraft.PrankCraftMod;
import com.prankcraft.consent.ConsentManager;
import com.prankcraft.prank.PrankEffect;
import com.prankcraft.prank.PrankEngine;
import com.prankcraft.util.Text;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /prank ...} - the player-facing command. Port of
 * {@code com.prankcraft.commands.PrankCommand}.
 *
 * <p>Anyone can run the consent half ({@code allow}, {@code deny}, {@code status}); the effect
 * half needs operator level 2, and the target still has to have agreed. The consent half is
 * deliberately open to everyone: a player must always be able to say no, without asking staff
 * for permission to do it.
 *
 * <p><b>Why {@code requires(src -> src.hasPermission(2))} and not a permission node.</b> Forge
 * 1.16.5 has no permission system. Level 2 is the vanilla equivalent of "may run /gamemode and
 * /kick": it is the level a server operator has by default, and it is the closest honest
 * translation of the Paper module's {@code prankcraft.admin} node. Reading it as "any operator"
 * is correct for a vanilla Forge server; a permissions mod can refine it later by wrapping
 * {@code hasPermission}.
 */
public final class PrankCommand {

    /** Vanilla operator level 2: the same bar /gamemode and /kick use. */
    public static final int OPERATOR_LEVEL = 2;

    private final PrankCraftMod mod;

    public PrankCommand(PrankCraftMod mod) {
        this.mod = mod;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("prank");

        // ---------------------------------------------------------------- consent (open to all)
        root.then(Commands.literal("help").executes(ctx -> help(ctx.getSource())));
        root.then(Commands.literal("list").executes(ctx -> list(ctx.getSource())));
        root.then(Commands.literal("status").executes(ctx -> status(ctx.getSource())));
        root.then(Commands.literal("yes").executes(ctx -> respond(ctx.getSource(), true)));
        root.then(Commands.literal("no").executes(ctx -> respond(ctx.getSource(), false)));
        root.then(Commands.literal("stop").executes(ctx -> stopSelf(ctx.getSource())));

        root.then(Commands.literal("allow")
                .then(Commands.argument("who", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            builder.suggest("*");
                            for (ServerPlayer p : mod.onlinePlayers()) {
                                builder.suggest(p.getGameProfile().getName());
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> allow(ctx.getSource(), StringArgumentType.getString(ctx, "who")))));

        root.then(Commands.literal("deny")
                .executes(ctx -> deny(ctx.getSource(), null))
                .then(Commands.argument("who", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            builder.suggest("*");
                            for (ServerPlayer p : mod.onlinePlayers()) {
                                builder.suggest(p.getGameProfile().getName());
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> deny(ctx.getSource(), StringArgumentType.getString(ctx, "who")))));

        // ---------------------------------------------------------------- effect firing
        root.then(Commands.literal("random")
                .requires(src -> src.hasPermission(OPERATOR_LEVEL))
                .then(Commands.argument("target", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (ServerPlayer p : mod.onlinePlayers()) {
                                builder.suggest(p.getGameProfile().getName());
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> random(ctx.getSource(), StringArgumentType.getString(ctx, "target")))));

        root.then(Commands.literal("reload")
                .requires(src -> src.hasPermission(OPERATOR_LEVEL))
                .executes(ctx -> reload(ctx.getSource())));

        // One literal per registered effect, so tab completion and /prank help agree with the
        // registry by construction rather than by a hand-maintained list.
        for (PrankEffect effect : mod.pranks().effects()) {
            root.then(Commands.literal(effect.id())
                    .requires(src -> src.hasPermission(OPERATOR_LEVEL))
                    .then(Commands.argument("target", StringArgumentType.word())
                            .suggests((ctx, builder) -> {
                                for (ServerPlayer p : mod.onlinePlayers()) {
                                    builder.suggest(p.getGameProfile().getName());
                                }
                                return builder.buildFuture();
                            })
                            .executes(ctx -> fire(ctx.getSource(), effect.id(),
                                    StringArgumentType.getString(ctx, "target"), false))));
        }

        dispatcher.register(root);
    }

    // ------------------------------------------------------------------ effect firing

    private int fire(CommandSourceStack source, String effectId, String targetName, boolean force) {
        ServerPlayer actor = source.getEntity() instanceof ServerPlayer ? (ServerPlayer) source.getEntity() : null;
        ServerPlayer target = mod.playerByName(targetName);
        if (target == null) {
            feedback(source, "&cNo online player named &f" + targetName + "&c.");
            return 0;
        }
        PrankEngine.Result result = mod.pranks().apply(actor, target, effectId, force);
        feedback(source, mod.pranks().explain(result, actor, target));
        return result == PrankEngine.Result.OK ? 1 : 0;
    }

    private int random(CommandSourceStack source, String targetName) {
        ServerPlayer actor = source.getEntity() instanceof ServerPlayer ? (ServerPlayer) source.getEntity() : null;
        ServerPlayer target = mod.playerByName(targetName);
        if (target == null) {
            feedback(source, "&cNo online player named &f" + targetName + "&c.");
            return 0;
        }
        PrankEffect effect = mod.pranks().randomEffect();
        if (effect == null) {
            feedback(source, "&cNo enabled prank effect is available.");
            return 0;
        }
        PrankEngine.Result result = mod.pranks().apply(actor, target, effect.id());
        feedback(source, mod.pranks().explain(result, actor, target));
        return result == PrankEngine.Result.OK ? 1 : 0;
    }

    // ------------------------------------------------------------------ consent

    private int allow(CommandSourceStack source, String who) {
        ServerPlayer self = requirePlayer(source);
        if (self == null) {
            return 0;
        }
        if (who.equals("*") || who.equalsIgnoreCase("all")) {
            mod.consent().allowAll(self.getUUID());
            feedback(source, "&aAnyone on the server may now prank you. &7Undo with &f/prank deny *&7.");
            return 1;
        }
        ServerPlayer other = mod.playerByName(who);
        if (other == null) {
            feedback(source, "&cNo online player named &f" + who + "&c.");
            return 0;
        }
        if (other.getUUID().equals(self.getUUID())) {
            feedback(source, "&7You are always allowed to prank yourself. Pick somebody else.");
            return 0;
        }
        mod.consent().allow(self.getUUID(), other.getUUID());
        feedback(source, "&aYou will now accept pranks from &f" + other.getGameProfile().getName() + "&a.");
        Text.prefixed(other, "&aYou may now prank &f" + self.getGameProfile().getName() + "&a.");
        return 1;
    }

    private int deny(CommandSourceStack source, String who) {
        ServerPlayer self = requirePlayer(source);
        if (self == null) {
            return 0;
        }
        if (who == null) {
            mod.consent().setOptIn(self.getUUID(), false);
            feedback(source, "&ePranks are now off for you. &7Re-enable with &f/prank allow <player>&7.");
            return 1;
        }
        if (who.equals("*") || who.equalsIgnoreCase("all")) {
            // The strong form. Remembered permanently, and not overridable by an operator force.
            mod.consent().deny(self.getUUID(), ConsentManager.ALL);
            feedback(source, "&eYou will never be pranked on this server again. "
                    + "&7Only a config change or an operator removing your record can undo this.");
            return 1;
        }
        ServerPlayer other = mod.playerByName(who);
        if (other != null) {
            mod.consent().deny(self.getUUID(), other.getUUID());
            feedback(source, "&e" + other.getGameProfile().getName() + " may no longer prank you.");
            return 1;
        }
        mod.consent().setOptIn(self.getUUID(), false);
        feedback(source, "&ePranks are now off for you.");
        return 1;
    }

    private int respond(CommandSourceStack source, boolean approve) {
        ServerPlayer self = requirePlayer(source);
        if (self == null) {
            return 0;
        }
        int count = approve
                ? mod.consent().approveAll(self.getUUID())
                : mod.consent().denyAll(self.getUUID());
        if (count == 0) {
            feedback(source, "&7You have no pending prank requests.");
            return 0;
        }
        feedback(source, approve
                ? "&aApproved &f" + count + "&a prank request(s)."
                : "&eDeclined &f" + count + "&e prank request(s).");
        return count;
    }

    private int status(CommandSourceStack source) {
        ServerPlayer self = requirePlayer(source);
        if (self == null) {
            feedback(source, "&7Consent data is per-player. Use /prankcraft consent <player> to inspect one.");
            return 0;
        }
        boolean optedIn = mod.consent().isOptedIn(self.getUUID());
        boolean denied = mod.consent().isDenied(self.getUUID());
        feedback(source, "&7Pranks: " + (denied ? "&cnever" : optedIn ? "&aenabled" : "&cdisabled"));
        StringBuilder allowed = new StringBuilder();
        for (java.util.UUID id : mod.consent().allowedFor(self.getUUID())) {
            if (allowed.length() > 0) {
                allowed.append("&7, &f");
            }
            allowed.append(mod.consent().nameOf(id));
        }
        feedback(source, "&7Allowed: " + (allowed.length() == 0 ? "&7nobody" : "&f" + allowed));
        List<java.util.UUID> pending = mod.consent().requestsFor(self.getUUID());
        if (!pending.isEmpty()) {
            feedback(source, "&ePending requests from: &f" + pending.size()
                    + "&e player(s). &7Use &f/prank yes&7 or &f/prank no&7.");
        }
        return 1;
    }

    private int stopSelf(CommandSourceStack source) {
        ServerPlayer self = requirePlayer(source);
        if (self == null) {
            return 0;
        }
        mod.tnt().clearFor(self);
        mod.footsteps().cancel(self);
        mod.shake().cancel(self);
        feedback(source, "&7Cleared any illusion you were seeing.");
        return 1;
    }

    private int reload(CommandSourceStack source) {
        mod.reloadConfig();
        feedback(source, "&aConfiguration and consent data reloaded.");
        return 1;
    }

    private int list(CommandSourceStack source) {
        feedback(source, "&8&m--------&r &dPrank effects &8&m--------");
        for (PrankEffect effect : mod.pranks().effects()) {
            feedback(source, (effect.enabled() ? "&a" : "&8") + " /prank " + effect.id()
                    + " <player> &7- " + effect.description());
        }
        feedback(source, "&8&m--------------------------------");
        return 1;
    }

    private int help(CommandSourceStack source) {
        boolean operator = source.hasPermission(OPERATOR_LEVEL);
        feedback(source, "&8&m--------&r &dPrankCraft &8&m--------");
        feedback(source, "&f/prank list &7- show available effects");
        feedback(source, "&f/prank allow <player|*> &7- let them prank you");
        feedback(source, "&f/prank deny [player|*] &7- opt out (any time, takes effect immediately)");
        feedback(source, "&f/prank status &7- see your current settings");
        feedback(source, "&f/prank stop &7- clear an illusion you are stuck in");
        if (operator) {
            feedback(source, "&f/prank <effect> <player> &7- prank somebody who agreed to it");
            feedback(source, "&f/prank random <player> &7- surprise them with any enabled effect");
            feedback(source, "&f/prank reload &7- reload the config and consent data");
        }
        feedback(source, "&7Pranks here are cosmetic only. Nothing can harm you, move you, or take your items.");
        return 1;
    }

    // ------------------------------------------------------------------ helpers

    private static ServerPlayer requirePlayer(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer) {
            return (ServerPlayer) source.getEntity();
        }
        feedback(source, "&cConsent is per-player; run this in game.");
        return null;
    }

    /** Sends a prefixed line to whoever ran the command, player or console. */
    static void feedback(CommandSourceStack source, String raw) {
        if (source.getEntity() instanceof ServerPlayer) {
            Text.prefixed((ServerPlayer) source.getEntity(), raw);
        } else {
            source.sendSuccess(Text.fromLegacy("&8[&dPrank&8] &r" + raw), false);
        }
    }

    /** Every enabled effect id, for tab completion elsewhere. */
    public static List<String> effectIds(PrankCraftMod mod) {
        List<String> ids = new ArrayList<>();
        for (String id : mod.pranks().enabledIds()) {
            ids.add(id.toLowerCase(Locale.ROOT));
        }
        return ids;
    }
}
