package com.prankcraft.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.prankcraft.PrankCraftMod;
import com.prankcraft.audit.AuditLog;
import com.prankcraft.util.Text;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;

/**
 * {@code /prankcraft ...} - the operator command. Port of
 * {@code com.prankcraft.commands.PrankAdminCommand}.
 *
 * <p>Contains the manual control for the fake-TNT trick (show it, fire it, clear it) and the audit
 * viewer. The audit viewer is the important one: it is how you answer "did staff do something to
 * my account?" with facts instead of a shrug - and on a Forge server, where there are no
 * permission nodes, it is the only record that exists.
 *
 * <p>Every subcommand requires vanilla operator level 2, the same bar {@code /kick} uses.
 */
public final class PrankAdminCommand {

    private final PrankCraftMod mod;

    public PrankAdminCommand(PrankCraftMod mod) {
        this.mod = mod;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("prankcraft")
                .requires(src -> src.hasPermission(PrankCommand.OPERATOR_LEVEL));

        root.then(Commands.literal("status").executes(ctx -> status(ctx.getSource())));
        root.then(Commands.literal("reload").executes(ctx -> reload(ctx.getSource())));

        root.then(Commands.literal("tnt")
                .then(Commands.literal("show")
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (ServerPlayer p : mod.onlinePlayers()) {
                                        builder.suggest(p.getGameProfile().getName());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> tnt(ctx.getSource(), "show",
                                        StringArgumentType.getString(ctx, "target")))))
                .then(Commands.literal("fire")
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (ServerPlayer p : mod.onlinePlayers()) {
                                        builder.suggest(p.getGameProfile().getName());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> tnt(ctx.getSource(), "fire",
                                        StringArgumentType.getString(ctx, "target")))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    builder.suggest("*");
                                    for (ServerPlayer p : mod.onlinePlayers()) {
                                        builder.suggest(p.getGameProfile().getName());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> tnt(ctx.getSource(), "clear",
                                        StringArgumentType.getString(ctx, "target"))))));

        root.then(Commands.literal("clear")
                .then(Commands.argument("target", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            builder.suggest("*");
                            for (ServerPlayer p : mod.onlinePlayers()) {
                                builder.suggest(p.getGameProfile().getName());
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> clear(ctx.getSource(), StringArgumentType.getString(ctx, "target")))));

        root.then(Commands.literal("audit")
                .executes(ctx -> audit(ctx.getSource(), 10))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                        .executes(ctx -> audit(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count")))));

        root.then(Commands.literal("consent")
                .then(Commands.argument("target", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (ServerPlayer p : mod.onlinePlayers()) {
                                builder.suggest(p.getGameProfile().getName());
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> consent(ctx.getSource(), StringArgumentType.getString(ctx, "target")))));

        dispatcher.register(root);
    }

    // ------------------------------------------------------------------ subcommands

    private int reload(CommandSourceStack source) {
        mod.reloadConfig();
        PrankCommand.feedback(source, "&aReloaded the config, consent data and the effect registry.");
        return 1;
    }

    private int tnt(CommandSourceStack source, String action, String targetName) {
        if (targetName.equals("*")) {
            return clear(source, "*");
        }
        ServerPlayer target = mod.playerByName(targetName);
        if (target == null) {
            PrankCommand.feedback(source, "&cNo online player named &f" + targetName + "&c.");
            return 0;
        }

        switch (action.toLowerCase(Locale.ROOT)) {
            case "show": {
                int shown = mod.tnt().fake(target, null);
                PrankCommand.feedback(source, shown > 0
                        ? "&aShowing &f" + shown + "&a fake TNT block(s) to &f"
                                + target.getGameProfile().getName() + "&a."
                        : "&cNo believable floor spots found near &f"
                                + target.getGameProfile().getName() + "&c.");
                mod.audit().recordFrom(source.getEntity(), target, "fake-tnt:show",
                        "show issued from console/staff; blocks=" + shown);
                return shown > 0 ? 1 : 0;
            }
            case "fire": {
                boolean fired = mod.tnt().detonate(target);
                PrankCommand.feedback(source, fired
                        ? "&aDetonated the fake TNT for &f" + target.getGameProfile().getName() + "&a."
                        : "&cNo fake TNT is currently shown to &f" + target.getGameProfile().getName() + "&c.");
                mod.audit().recordFrom(source.getEntity(), target, "fake-tnt:fire",
                        "fire issued from console/staff; fired=" + fired);
                return fired ? 1 : 0;
            }
            case "clear":
            default: {
                mod.tnt().clearFor(target);
                PrankCommand.feedback(source, "&eCleared the fake-TNT illusion for &f"
                        + target.getGameProfile().getName() + "&e.");
                mod.audit().recordFrom(source.getEntity(), target, "fake-tnt:clear",
                        "clear issued from console/staff");
                return 1;
            }
        }
    }

    private int clear(CommandSourceStack source, String targetName) {
        if (targetName.equals("*")) {
            int count = 0;
            for (ServerPlayer p : mod.onlinePlayers()) {
                mod.tnt().clearFor(p);
                mod.footsteps().cancel(p);
                mod.shake().cancel(p);
                count++;
            }
            PrankCommand.feedback(source, "&eCleared illusions for &f" + count + "&e player(s).");
            return count;
        }
        ServerPlayer target = mod.playerByName(targetName);
        if (target == null) {
            PrankCommand.feedback(source, "&cNo online player named &f" + targetName + "&c.");
            return 0;
        }
        mod.tnt().clearFor(target);
        mod.footsteps().cancel(target);
        mod.shake().cancel(target);
        PrankCommand.feedback(source, "&eCleared illusions for &f" + target.getGameProfile().getName() + "&e.");
        return 1;
    }

    private int audit(CommandSourceStack source, int limit) {
        List<AuditLog.Entry> entries = mod.audit().recent(limit);
        if (entries.isEmpty()) {
            PrankCommand.feedback(source, "&7No prank activity recorded yet.");
            return 0;
        }
        PrankCommand.feedback(source, "&8&m--------&r &dRecent prank activity &8&m--------");
        for (AuditLog.Entry entry : entries) {
            PrankCommand.feedback(source, "&8- " + entry.pretty());
        }
        PrankCommand.feedback(source, "&7Full history: &f"
                + mod.auditDirectory().resolve("audit-*.log"));
        return entries.size();
    }

    private int consent(CommandSourceStack source, String targetName) {
        ServerPlayer target = mod.playerByName(targetName);
        java.util.UUID id = target == null ? mod.lookupOfflineId(targetName) : target.getUUID();
        if (id == null) {
            PrankCommand.feedback(source, "&cNobody on this server is called &f" + targetName + "&c.");
            return 0;
        }
        String name = mod.consent().nameOf(id);
        PrankCommand.feedback(source, "&7Consent record for &f" + name + "&7:");
        PrankCommand.feedback(source, "&7  opted in: &f" + mod.consent().isOptedIn(id));
        PrankCommand.feedback(source, "&7  never prank: &f" + mod.consent().isDenied(id));
        StringBuilder allowed = new StringBuilder();
        for (java.util.UUID other : mod.consent().allowedFor(id)) {
            if (allowed.length() > 0) {
                allowed.append("&7, &f");
            }
            allowed.append(mod.consent().nameOf(other));
        }
        PrankCommand.feedback(source, "&7  allowed: &f" + (allowed.length() == 0 ? "nobody" : allowed));
        PrankCommand.feedback(source, "&7An operator cannot override 'never prank'; that is the point.");
        return 1;
    }

    private int status(CommandSourceStack source) {
        PrankCommand.feedback(source, "&7PrankCraft for Forge &f" + mod.modVersion()
                + " &7(Minecraft 1.16.5, server-side only)");
        PrankCommand.feedback(source, "&7Consent gate: &f" + mod.config().requireConsent.get()
                + " &7(per-target: " + mod.config().requirePerTargetConsent.get() + ")");
        PrankCommand.feedback(source, "&7Audit: &f" + mod.config().auditEnabled.get()
                + " &7(file: " + mod.config().auditFile.get() + ")");
        PrankCommand.feedback(source, "&7Active fake-TNT sessions: &f" + mod.tnt().activeSessions()
                + " &7display entities: &f" + mod.tnt().ownedEntityCount());
        int enabled = 0;
        for (com.prankcraft.prank.PrankEffect effect : mod.pranks().effects()) {
            if (effect.enabled()) {
                enabled++;
            }
        }
        PrankCommand.feedback(source, "&7Effects loaded: &f" + mod.pranks().effects().size()
                + " &7enabled: &f" + enabled);
        PrankCommand.feedback(source, "&7Consent records: &f" + mod.consentRecordCount()
                + " &7audit file: &f" + mod.auditDirectory());
        return 1;
    }
}
