package com.prankcraft.consent;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * The consent decision, as a pure function.
 *
 * <p>This is deliberately separated from {@link ConsentManager}, which has to talk to Bukkit to
 * load and save files. The rule that decides whether a prank may happen is the single most
 * important piece of logic in the plugin - it is the difference between a prank and harassment -
 * so it is expressed here with no Bukkit dependency at all, which makes it directly unit
 * testable. {@link ConsentManager#mayPrank} is a thin wrapper around {@link #decide}.
 *
 * <p>The Forge port implements the same rule in its own engine; keeping this file free of Bukkit
 * means the two platforms cannot drift apart on gate ordering without a test failing.
 */
public final class ConsentRules {

    /** Wildcard entry meaning "anyone may prank me". */
    public static final UUID ANYONE = new UUID(0L, 0L);

    /** Why a prank was allowed or refused. */
    public enum Decision {
        /** The console fired it; there is no consent relationship to check. */
        CONSOLE,
        /** A player targeting themselves. */
        SELF,
        /** The actor is on the target's allow list. */
        ALLOWED,
        /** The target holds the bypass permission and can never be pranked. */
        EXEMPT,
        /** The target has not opted in, or has not allowed this specific actor. */
        NO_CONSENT
    }

    private ConsentRules() {
    }

    public static boolean allowed(Decision decision) {
        return decision == Decision.CONSOLE || decision == Decision.SELF || decision == Decision.ALLOWED;
    }

    /** Convenience overload using {@link #ANYONE} as the wildcard. */
    public static Decision decide(UUID actor, UUID target, boolean optedIn, boolean requireConsent,
                                  boolean requirePerTargetConsent, Set<UUID> allowedByTarget,
                                  boolean targetIsExempt) {
        return decide(actor, target, optedIn, requireConsent, requirePerTargetConsent, allowedByTarget,
                targetIsExempt, ANYONE);
    }

    /**
     * Decides whether {@code actor} may prank {@code target}.
     *
     * <p>Order matters and is asserted by the tests: the bypass permission is checked before the
     * consent gates, so a protected player cannot be pranked even by somebody they once allowed.
     *
     * @param actor                   who is firing, or {@code null} for the console
     * @param target                  who would be pranked
     * @param optedIn                 whether the target has accepted pranks at all
     * @param requireConsent          config: a target must have opted in at all
     * @param requirePerTargetConsent config: the target must have allowed this specific actor
     * @param allowedByTarget         the target's allow list, may be {@code null} or empty
     * @param targetIsExempt          whether the target holds the bypass permission
     * @param wildcard                the sentinel that means "anyone", for tests
     */
    public static Decision decide(UUID actor, UUID target, boolean optedIn, boolean requireConsent,
                                  boolean requirePerTargetConsent, Set<UUID> allowedByTarget,
                                  boolean targetIsExempt, UUID wildcard) {
        if (actor == null) {
            return Decision.CONSOLE;
        }
        if (target == null) {
            return Decision.NO_CONSENT;
        }
        if (actor.equals(target)) {
            return Decision.SELF;
        }
        // A protected player stays protected no matter who asks.
        if (targetIsExempt) {
            return Decision.EXEMPT;
        }
        if (!requireConsent) {
            return Decision.ALLOWED;
        }
        if (!optedIn) {
            return Decision.NO_CONSENT;
        }
        if (!requirePerTargetConsent) {
            return Decision.ALLOWED;
        }
        // Collections.emptySet rather than Set.of: this rule is shared with the legacy builds
        // that must run on Java 8 servers, where Set.of does not exist.
        Set<UUID> allowList = allowedByTarget == null ? Collections.<UUID>emptySet() : allowedByTarget;
        return allowList.contains(actor) || allowList.contains(wildcard)
                ? Decision.ALLOWED
                : Decision.NO_CONSENT;
    }
}
