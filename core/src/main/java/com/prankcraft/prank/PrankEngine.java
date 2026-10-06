package com.prankcraft.prank;

import com.prankcraft.PrankCraftPlugin;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Owns the effect registry and is the single place where permission and consent are enforced.
 *
 * <p>Every command funnels through {@link #apply(Player, Player, String)}. Effects themselves
 * re-check nothing, because a prank that only sometimes checks consent is worse than no prank
 * at all: if you add a new command later, this is the one gate it has to pass.
 */
public final class PrankEngine {

    /** Outcome of an attempted prank, for messaging and auditing. */
    public enum Result {
        OK,
        UNKNOWN_EFFECT,
        DISABLED,
        NO_PERMISSION,
        NO_CONSENT,
        EXEMPT,
        SELF,
        VETOED
    }

    private final PrankCraftPlugin plugin;
    private final Map<String, PrankEffect> effects = new LinkedHashMap<>();

    /**
     * Per-effect settings, resolved once per registration or reload.
     *
     * <p>These used to be config reads: {@code enabled()} ran a string concat plus a config path
     * walk for every effect on every tab-completion keystroke and every {@code /prank list}, and
     * {@code duration()} repeated it inside the fire path. They are now two field reads.
     */
    private record Settings(boolean enabled, int durationSeconds) {
    }

    private final Map<String, Settings> settings = new HashMap<>();

    public PrankEngine(PrankCraftPlugin plugin) {
        this.plugin = plugin;
    }

    public void register(PrankEffect effect) {
        effects.put(effect.id().toLowerCase(Locale.ROOT), effect);
        // Keep the resolved settings in step with the registry, so a newly registered effect is
        // immediately usable and the two maps cannot disagree.
        refresh();
    }

    public PrankEffect effect(String id) {
        return id == null ? null : effects.get(id.toLowerCase(Locale.ROOT));
    }

    public List<PrankEffect> effects() {
        return new ArrayList<>(effects.values());
    }

    public boolean enabled(PrankEffect effect) {
        Settings resolved = settings(effect);
        return resolved == null || resolved.enabled;
    }

    private Settings settings(PrankEffect effect) {
        return settings.get(effect.id().toLowerCase(Locale.ROOT));
    }

    /** Re-resolves every effect's enabled flag and duration. Called on enable and reload. */
    public void refresh() {
        settings.clear();
        for (Map.Entry<String, PrankEffect> entry : effects.entrySet()) {
            PrankEffect effect = entry.getValue();
            boolean on = plugin.getConfig().getBoolean(effect.configKey(), true);
            int seconds = Math.max(0, plugin.getConfig().getInt(
                    "pranks." + effect.id() + ".duration-seconds", effect.defaultDurationSeconds()));
            settings.put(entry.getKey(), new Settings(on, seconds));
        }
    }

    /** Effects the given player is allowed to fire, for tab completion. */
    public List<String> usableBy(Player actor) {
        List<String> ids = new ArrayList<>();
        for (PrankEffect effect : effects.values()) {
            if (enabled(effect) && actor.hasPermission(effect.permission())) {
                ids.add(effect.id());
            }
        }
        return ids;
    }

    /** The effect shown by {@code /prank random <player>}. */
    public PrankEffect randomEffect(Player actor) {
        List<PrankEffect> pool = new ArrayList<>();
        for (PrankEffect effect : effects.values()) {
            if (enabled(effect) && actor.hasPermission(effect.permission())) {
                pool.add(effect);
            }
        }
        if (pool.isEmpty()) {
            return null;
        }
        return pool.get((int) (Math.random() * pool.size()));
    }

    /** Seconds the effect should keep running, honouring the config override. */
    public int duration(PrankEffect effect) {
        Settings resolved = settings(effect);
        return resolved == null ? effect.defaultDurationSeconds() : resolved.durationSeconds;
    }

    public Result apply(Player actor, Player target, String effectId) {
        return apply(actor, target, effectId, false);
    }

    /**
     * The one gate. Checks configuration, permission and consent, fires the effect, cancels it
     * after its configured duration, and records the result.
     *
     * @param actor the player firing the effect, or {@code null} for the console
     * @param force operator override: skips <em>consent</em> only, never permission or config
     */
    public Result apply(Player actor, Player target, String effectId, boolean force) {
        PrankEffect effect = effect(effectId);
        if (effect == null) {
            return Result.UNKNOWN_EFFECT;
        }
        if (!enabled(effect)) {
            return Result.DISABLED;
        }
        // The console has no permission set of its own, and a null actor used to reach
        // actor.hasPermission() and throw - which surfaced as an unhelpful "prank was blocked".
        // Running the command at all is the console's permission check.
        if (actor != null && !actor.hasPermission(effect.permission())) {
            return Result.NO_PERMISSION;
        }
        if (target.equals(actor)) {
            return Result.SELF;
        }
        if (!force) {
            if (plugin.consent().isExempt(target.getUniqueId())) {
                return Result.EXEMPT;
            }
            if (!plugin.consent().mayPrank(actor == null ? null : actor.getUniqueId(), target.getUniqueId())) {
                return Result.NO_CONSENT;
            }
        }

        String detail;
        try {
            detail = effect.fire(target);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Effect '" + effect.id() + "' failed for " + target.getName()
                    + ": " + throwable);
            return Result.VETOED;
        }

        if (detail == null) {
            return Result.VETOED;
        }

        plugin.audit().record(actor == null ? null : actor.getUniqueId(), target.getUniqueId(), effect.id(),
                (force ? "FORCED " : "") + detail);

        int seconds = duration(effect);
        if (seconds > 0) {
            plugin.runSync(() -> {
                if (target.isOnline()) {
                    effect.cancel(target);
                }
            }, seconds * 20L);
        }
        return Result.OK;
    }

    /** Human-readable explanation for a failed attempt. */
    public String explain(Result result, Player actor, Player target) {
        String name = target == null ? "that player" : target.getName();
        return switch (result) {
            case UNKNOWN_EFFECT -> "&cUnknown prank effect. Try &f/prank list&c.";
            case DISABLED -> "&cThat effect is disabled in config.yml.";
            case NO_PERMISSION -> "&cYou do not have permission to fire that effect.";
            case NO_CONSENT -> target == null
                    ? "&cThat player has not consented to pranks."
                    : plugin.consent().denyReason(actor == null ? null : actor.getUniqueId(), target.getUniqueId());
            case EXEMPT -> "&e" + name + " &cis protected and cannot be pranked at all.";
            case SELF -> "&cPick somebody else - you cannot prank yourself.";
            case VETOED -> "&cThat prank was blocked (another plugin or the server refused it).";
            case OK -> "&aPrank fired at &f" + name + "&a.";
        };
    }
}
