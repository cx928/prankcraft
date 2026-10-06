package com.prankcraft.prank;

import net.minecraft.server.level.ServerPlayer;

/**
 * One prank effect. Direct port of {@code com.prankcraft.prank.PrankEffect}; the contract is
 * unchanged because the contract is the safety model:
 * <ul>
 *   <li><b>harmless</b> - no block damage, no item loss, no health/position changes that a
 *       player cannot immediately undo;</li>
 *   <li><b>client-side where possible</b> - fake packets, particles and sounds, so a server
 *       rollback is never needed;</li>
 *   <li><b>idempotent-safe</b> - {@link #cancel(ServerPlayer)} must undo whatever {@link #fire}
 *       did.</li>
 * </ul>
 * Implementations get the target's consent checked by {@link PrankEngine} before they run;
 * they must never assume permission themselves.
 *
 * <p>Note what is <em>not</em> in this interface: no method to move a player, no method to
 * open a container, no method that returns another player's session. An effect physically
 * cannot reach those APIs from here, which is the point.
 */
public interface PrankEffect {

    /** Stable id used in config, commands and the audit log. */
    String id();

    /** Human description shown in {@code /prank list}. */
    String description();

    /**
     * Whether the effect is enabled in config. The engine reads this instead of the effect
     * holding a config reference, so an effect can never disagree with the config file.
     */
    boolean enabled();

    /** Roughly how many seconds the effect lasts; used for display and scheduling. */
    int defaultDurationSeconds();

    /** Seconds the engine should wait before calling {@link #cancel}, honouring config. */
    int durationSeconds();

    /**
     * Runs the effect for one target.
     *
     * @param target the player who will experience the prank
     * @return a short detail string for the audit log, or {@code null} if the effect refused
     *         to run (the engine treats that as a veto and records nothing)
     */
    String fire(ServerPlayer target);

    /** Stops the effect early and cleans up any fake entities, packets or titles. */
    default void cancel(ServerPlayer target) {
    }

    /**
     * Whether this effect can be aimed at a player who is offline. False for everything that
     * needs a live connection; only {@code fake-login} overrides it, because a fake join line
     * is precisely for somebody who is not here.
     */
    default boolean worksOffline() {
        return false;
    }
}
