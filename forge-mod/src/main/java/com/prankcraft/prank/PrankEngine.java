package com.prankcraft.prank;

import com.prankcraft.PrankCraftMod;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Owns the effect registry and is the single place where consent is enforced. Port of
 * {@code com.prankcraft.prank.PrankEngine}.
 *
 * <p>Every command funnels through {@link #apply(Object, ServerPlayer, String, boolean)}. Effects
 * themselves re-check nothing, because a prank that only sometimes checks consent is worse than no
 * prank at all: if you add a new command later, this is the one gate it has to pass.
 *
 * <p><b>Difference from the Paper module.</b> There is no permission system on a Forge server, so
 * the Paper version's per-effect permission nodes are gone. What replaces them is not "no check":
 * it is the two gates this engine can actually enforce - the operator level required to run the
 * command at all (checked by the command layer, level 2), and consent for the target (checked
 * here, on every single fire). The audit log is what makes the missing permission nodes
 * survivable: an operator cannot quietly use this, because every use is written down.
 */
public final class PrankEngine {

    /** Outcome of an attempted prank, for messaging and auditing. */
    public enum Result {
        OK,
        UNKNOWN_EFFECT,
        DISABLED,
        SELF,
        NO_CONSENT,
        DENIED,
        VETOED
    }

    private final PrankCraftMod mod;
    private final Map<String, PrankEffect> effects = new LinkedHashMap<>();

    public PrankEngine(PrankCraftMod mod) {
        this.mod = mod;
    }

    public void register(PrankEffect effect) {
        effects.put(effect.id().toLowerCase(Locale.ROOT), effect);
    }

    public PrankEffect effect(String id) {
        return id == null ? null : effects.get(id.toLowerCase(Locale.ROOT));
    }

    public List<PrankEffect> effects() {
        return new ArrayList<>(effects.values());
    }

    public boolean enabled(PrankEffect effect) {
        return effect != null && effect.enabled();
    }

    /** Effects that are switched on, for tab completion and {@code /prank list}. */
    public List<String> enabledIds() {
        List<String> ids = new ArrayList<>();
        for (PrankEffect effect : effects.values()) {
            if (effect.enabled()) {
                ids.add(effect.id());
            }
        }
        return ids;
    }

    /** The effect shown by {@code /prank random <player>}. */
    public PrankEffect randomEffect() {
        List<PrankEffect> pool = new ArrayList<>();
        for (PrankEffect effect : effects.values()) {
            if (effect.enabled() && !effect.worksOffline()) {
                pool.add(effect);
            }
        }
        if (pool.isEmpty()) {
            return null;
        }
        return pool.get((int) (Math.random() * pool.size()));
    }

    public Result apply(Object actor, ServerPlayer target, String effectId) {
        return apply(actor, target, effectId, false);
    }

    /**
     * The one gate. Checks configuration and consent, fires the effect, schedules its cleanup,
     * and records the result.
     *
     * @param actor the player who fired it, or {@code null} for the console
     * @param force operator override: skips <em>consent</em> only, never config, and never the
     *              deny list - a player who asked never to be pranked stays untouchable
     */
    public Result apply(Object actor, ServerPlayer target, String effectId, boolean force) {
        PrankEffect effect = effect(effectId);
        if (effect == null) {
            return Result.UNKNOWN_EFFECT;
        }
        if (!effect.enabled()) {
            return Result.DISABLED;
        }
        if (target == null) {
            return Result.VETOED;
        }
        if (actor instanceof ServerPlayer && ((ServerPlayer) actor).getUUID().equals(target.getUUID())) {
            return Result.SELF;
        }
        if (mod.consent().isDenied(target.getUUID())) {
            // The deny list survives "force" on purpose. This is the one promise the mod makes
            // that no operator command can override.
            return Result.DENIED;
        }
        if (!force) {
            java.util.UUID actorId = actor instanceof ServerPlayer ? ((ServerPlayer) actor).getUUID() : null;
            if (!mod.consent().mayPrank(actorId, target.getUUID())) {
                return Result.NO_CONSENT;
            }
        }

        String detail;
        try {
            detail = effect.fire(target);
        } catch (Throwable throwable) {
            mod.logger().warn("Effect '{}' failed for {}: {}", effect.id(),
                    target.getGameProfile().getName(), throwable.toString());
            return Result.VETOED;
        }
        if (detail == null) {
            return Result.VETOED;
        }

        mod.audit().record(actor instanceof ServerPlayer ? ((ServerPlayer) actor).getUUID() : null,
                target.getUUID(), effect.id(), (force ? "FORCED " : "") + detail);

        int seconds = effect.durationSeconds();
        if (seconds > 0) {
            final ServerPlayer watched = target;
            mod.schedule(seconds * 20, () -> {
                ServerPlayer online = mod.player(watched.getUUID());
                if (online != null) {
                    effect.cancel(online);
                }
            });
        }
        return Result.OK;
    }

    /** Human-readable explanation for a failed attempt. */
    public String explain(Result result, ServerPlayer actor, ServerPlayer target) {
        String name = target == null ? "that player" : target.getGameProfile().getName();
        switch (result) {
            case UNKNOWN_EFFECT:
                return "&cUnknown prank effect. Try &f/prank list&c.";
            case DISABLED:
                return "&cThat effect is disabled in the config file.";
            case NO_CONSENT:
                if (target == null) {
                    return "&cThat player has not consented to pranks.";
                }
                return mod.consent().denyReason(
                        actor == null ? null : actor.getUUID(), target.getUUID());
            case DENIED:
                return "&e" + name + " &chas asked never to be pranked. That request is final.";
            case SELF:
                return "&cPick somebody else - you cannot prank yourself.";
            case VETOED:
                return "&cThat prank could not run (no valid spot, or the server refused it).";
            case OK:
            default:
                return "&aPrank fired at &f" + name + "&a.";
        }
    }
}
