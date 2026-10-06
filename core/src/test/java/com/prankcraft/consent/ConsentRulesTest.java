package com.prankcraft.consent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the consent gate.
 *
 * <p>This is the rule that decides whether a player can be pranked at all, so it is tested as a
 * truth table rather than spot-checked. {@code ConsentRules} has no Bukkit dependency precisely
 * so that these tests can run without a server.
 */
class ConsentRulesTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CAROL = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** Strictest configuration: opt in, and allow the specific prankster. */
    private static ConsentRules.Decision strict(UUID actor, UUID target, boolean optedIn,
                                                Set<UUID> allowList, boolean exempt) {
        return ConsentRules.decide(actor, target, optedIn, true, true, allowList, exempt);
    }

    @Test
    @DisplayName("a target who never opted in cannot be pranked")
    void noOptInRefuses() {
        assertEquals(ConsentRules.Decision.NO_CONSENT, strict(BOB, ALICE, false, Set.of(), false));
        assertFalse(ConsentRules.allowed(strict(BOB, ALICE, false, Set.of(), false)));
    }

    @Test
    @DisplayName("an actor who is not on the allow list is refused")
    void otherPlayerRefused() {
        assertEquals(ConsentRules.Decision.NO_CONSENT,
                strict(CAROL, ALICE, true, Set.of(BOB), false));
    }

    @Test
    @DisplayName("an actor on the allow list is accepted")
    void listedPlayerAllowed() {
        assertEquals(ConsentRules.Decision.ALLOWED, strict(BOB, ALICE, true, Set.of(BOB), false));
        assertTrue(ConsentRules.allowed(strict(BOB, ALICE, true, Set.of(BOB), false)));
    }

    @Test
    @DisplayName("the wildcard entry accepts anybody")
    void wildcardAllowsAnyone() {
        assertEquals(ConsentRules.Decision.ALLOWED,
                strict(CAROL, ALICE, true, Set.of(ConsentRules.ANYONE), false));
    }

    @Test
    @DisplayName("the bypass permission beats consent, even from an allowed prankster")
    void exemptionWinsOverAllowList() {
        // Alice allowed Bob, but Alice now holds prankcraft.consent.bypass. Protection wins.
        assertEquals(ConsentRules.Decision.EXEMPT, strict(BOB, ALICE, true, Set.of(BOB), true));
        assertFalse(ConsentRules.allowed(strict(BOB, ALICE, true, Set.of(BOB), true)));
    }

    @Test
    @DisplayName("the bypass permission still applies when consent is switched off entirely")
    void exemptionWinsOverDisabledConsentGate() {
        ConsentRules.Decision decision = ConsentRules.decide(BOB, ALICE, true, false, false,
                Set.of(), true);
        assertEquals(ConsentRules.Decision.EXEMPT, decision);
    }

    @Test
    @DisplayName("a target cannot prank themselves")
    void selfIsRejected() {
        assertEquals(ConsentRules.Decision.SELF, strict(ALICE, ALICE, true, Set.of(ALICE), false));
    }

    @Test
    @DisplayName("the console is always allowed, and this is how staff tests fire effects")
    void consoleIsAllowed() {
        assertEquals(ConsentRules.Decision.CONSOLE, strict(null, ALICE, false, Set.of(), false));
        assertTrue(ConsentRules.allowed(strict(null, ALICE, false, Set.of(), false)));
    }

    @Test
    @DisplayName("turning the consent gate off allows everyone who is not protected")
    void disabledGateAllowsEveryone() {
        assertEquals(ConsentRules.Decision.ALLOWED,
                ConsentRules.decide(BOB, ALICE, false, false, false, Set.of(), false));
        assertEquals(ConsentRules.Decision.EXEMPT,
                ConsentRules.decide(BOB, ALICE, false, false, false, Set.of(), true));
    }

    @Test
    @DisplayName("per-target consent off means opting in is enough")
    void relaxedGateAcceptsAnyOptedInTarget() {
        assertEquals(ConsentRules.Decision.ALLOWED,
                ConsentRules.decide(CAROL, ALICE, true, true, false, Set.of(BOB), false));
    }

    @Test
    @DisplayName("a null allow list is treated as 'allowed nobody', never as 'allowed everybody'")
    void nullAllowListIsNotPermissive() {
        assertEquals(ConsentRules.Decision.NO_CONSENT, strict(BOB, ALICE, true, null, false));
        // ...and an opted-in player with no allow list at all is still refused under the
        // per-target rule, which is the safe default.
        assertEquals(ConsentRules.Decision.NO_CONSENT, strict(BOB, ALICE, true, Set.of(), false));
    }

    @Test
    @DisplayName("a null target is refused rather than treated as a wildcard")
    void nullTargetRefused() {
        assertEquals(ConsentRules.Decision.NO_CONSENT, strict(BOB, null, true, Set.of(BOB), false));
        assertFalse(ConsentRules.allowed(strict(BOB, null, true, Set.of(BOB), false)));
    }
}
