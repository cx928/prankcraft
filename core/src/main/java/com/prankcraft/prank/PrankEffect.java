package com.prankcraft.prank;

import org.bukkit.entity.Player;

/**
 * One prank effect. Implementations must be:
 * <ul>
 *   <li><b>harmless</b> — no block damage, no item loss, no health/position changes that a
 *       player cannot immediately undo;</li>
 *   <li><b>client-side where possible</b> — fake packets, particles and sounds, so a server
 *       rollback is never needed;</li>
 *   <li><b>idempotent-safe</b> — {@link #cancel(Player)} must undo whatever {@link #fire} did.</li>
 * </ul>
 * Implementations get the target's consent checked by {@link PrankEngine} before they run;
 * they must never assume permission themselves.
 */
public interface PrankEffect {

    /** Stable id used in config, commands and the audit log. */
    String id();

    /** Human description shown in {@code /prank list}. */
    String description();

    /** Config key holding the toggle, e.g. {@code pranks.fake-tnt.enabled}. */
    default String configKey() {
        return "pranks." + id() + ".enabled";
    }

    /** Permissions node required to fire this effect at somebody. */
    String permission();

    /** Roughly how many seconds the effect lasts; used for display and scheduling. */
    int defaultDurationSeconds();

    /**
     * Runs the effect for one target.
     *
     * @param target the player who will experience the prank
     * @return a short detail string for the audit log (never {@code null})
     */
    String fire(Player target);

    /** Stops the effect early and cleans up any fake entities, packets or bars. */
    default void cancel(Player target) {
    }

    /** Called when the plugin disables; drop every trace of this effect for {@code target}. */
    default void reset(Player target) {
        cancel(target);
    }
}
