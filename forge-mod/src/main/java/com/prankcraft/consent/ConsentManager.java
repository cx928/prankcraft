package com.prankcraft.consent;

import com.prankcraft.PrankCraftMod;
import com.prankcraft.util.Json;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks who has agreed to be pranked. Direct port of {@code com.prankcraft.consent.ConsentManager}.
 *
 * <p>Two independent gates exist, both configurable:
 * <ul>
 *   <li>{@code require-consent: true} - a target must have opted in at all.</li>
 *   <li>{@code require-per-target-consent: true} - and must have allowed <em>that</em> prankster.</li>
 * </ul>
 * Consent is the difference between a prank and harassment, so it is on by default and both
 * {@code /prank allow} and {@code /prank deny} take effect immediately.
 *
 * <p><b>Forge-specific differences, both deliberate.</b>
 * <ol>
 *   <li>There are no permissions on a Forge server, so the Paper module's
 *       {@code prankcraft.consent.bypass} node is replaced by an explicit deny list:
 *       {@code /prank deny *} is remembered as "never again", not merely as "not right now".
 *       That list is checked first, before every other gate.</li>
 *   <li>An opt-in does not survive a logout. Consent that has to be renewed is consent;
 *       consent that silently persists for months is a footgun. Operators who want a
 *       permanent arrangement can use the explicit allow list, which does persist.</li>
 * </ol>
 */
public final class ConsentManager {

    /** Wildcard entry meaning "anyone on the server may prank me". */
    public static final UUID ALL = new UUID(0L, 0L);

    private final PrankCraftMod mod;
    private final Path file;
    private MinecraftServer server;

    /** player -> whether they accept pranks at all */
    private final Map<UUID, Boolean> optedIn = new LinkedHashMap<>();
    /** player -> pranksters explicitly allowed to prank them */
    private final Map<UUID, Set<UUID>> allowed = new LinkedHashMap<>();
    /** player -> whether they have asked never to be pranked again */
    private final Set<UUID> denied = new LinkedHashSet<>();
    /** player -> names, so an audit line can name somebody who is offline */
    private final Map<UUID, String> names = new LinkedHashMap<>();
    /** incoming invitations waiting for a yes/no */
    private final Map<UUID, Deque<UUID>> pending = new LinkedHashMap<>();

    public ConsentManager(PrankCraftMod mod, Path file) {
        this.mod = mod;
        this.file = file;
        load();
    }

    /** Called once the server exists, so offline names can be resolved for the audit log. */
    public void attachServer(MinecraftServer server) {
        this.server = server;
    }

    // ------------------------------------------------------------------ gates

    /** True when {@code prankster} may currently prank {@code target}, honouring the config gates. */
    public boolean mayPrank(UUID prankster, UUID target) {
        if (prankster == null || target == null) {
            return false;
        }
        if (prankster.equals(target)) {
            return true;
        }
        // The deny list is checked before anything else, including the config gates: a player
        // who asked not to be involved must not become fair game because an operator edited a
        // config file. This replaces the Paper module's prankcraft.consent.bypass node.
        if (isDenied(target)) {
            return false;
        }
        if (!mod.config().requireConsent.get()) {
            return true;
        }
        if (!Boolean.TRUE.equals(optedIn.get(target))) {
            return false;
        }
        if (!mod.config().requirePerTargetConsent.get()) {
            return true;
        }
        Set<UUID> set = allowed.get(target);
        return set != null && (set.contains(prankster) || set.contains(ALL));
    }

    /** Players who asked never to be pranked cannot be pranked, whatever the config says. */
    public boolean isDenied(UUID target) {
        return target != null && denied.contains(target);
    }

    public String denyReason(UUID prankster, UUID target) {
        String name = nameOf(target);
        if (isDenied(target)) {
            return "&e" + name + " &chas asked never to be pranked and is protected.";
        }
        if (!Boolean.TRUE.equals(optedIn.get(target))) {
            return "&e" + name + " &chas not opted in. They can run &f/prank allow <you>&c.";
        }
        return "&e" + name + " &chas not allowed &fyou &cspecifically. They can run &f/prank allow <you>&c.";
    }

    // ------------------------------------------------------------- mutations

    public void setOptIn(UUID player, boolean value) {
        optedIn.put(player, value);
        if (!value) {
            allowed.remove(player);
            pending.remove(player);
        }
        save();
    }

    public boolean isOptedIn(UUID player) {
        return Boolean.TRUE.equals(optedIn.get(player));
    }

    public void allow(UUID target, UUID prankster) {
        denied.remove(target);
        optedIn.put(target, true);
        allowed.computeIfAbsent(target, k -> new HashSet<>()).add(prankster);
        save();
    }

    public void allowAll(UUID target) {
        denied.remove(target);
        optedIn.put(target, true);
        allowed.computeIfAbsent(target, k -> new HashSet<>()).add(ALL);
        save();
    }

    public void deny(UUID target, UUID prankster) {
        Set<UUID> set = allowed.get(target);
        if (set != null) {
            set.remove(prankster);
            if (prankster.equals(ALL)) {
                // "/prank deny *" is the strong form: remember it, permanently.
                optedIn.remove(target);
                allowed.remove(target);
                denied.add(target);
            }
        }
        save();
    }

    public void clear(UUID target) {
        optedIn.remove(target);
        allowed.remove(target);
        save();
    }

    public Set<UUID> allowedFor(UUID target) {
        Set<UUID> set = allowed.get(target);
        return set == null ? new HashSet<>() : new HashSet<>(set);
    }

    /** How many players have any kind of record: opted in, allowed somebody, or denied. */
    public int recordCount() {
        Set<UUID> everyone = new LinkedHashSet<>();
        everyone.addAll(optedIn.keySet());
        everyone.addAll(allowed.keySet());
        everyone.addAll(denied);
        return everyone.size();
    }

    // -------------------------------------------------------------- requests

    /** Queues an invitation from {@code prankster} to {@code target}; false if already queued. */
    public boolean request(UUID prankster, UUID target) {
        Deque<UUID> queue = pending.computeIfAbsent(target, k -> new ArrayDeque<>());
        if (queue.contains(prankster)) {
            return false;
        }
        queue.add(prankster);
        return true;
    }

    public List<UUID> requestsFor(UUID target) {
        Deque<UUID> queue = pending.get(target);
        return queue == null ? new ArrayList<>() : new ArrayList<>(queue);
    }

    /** Approves every pending request for {@code target}. Returns how many were approved. */
    public int approveAll(UUID target) {
        Deque<UUID> queue = pending.remove(target);
        if (queue == null || queue.isEmpty()) {
            return 0;
        }
        for (UUID prankster : queue) {
            allow(target, prankster);
        }
        save();
        return queue.size();
    }

    public int denyAll(UUID target) {
        Deque<UUID> queue = pending.remove(target);
        return queue == null ? 0 : queue.size();
    }

    /**
     * Called when a player disconnects. Drops the opt-in but keeps the explicit allow list and
     * the deny list, so "you may prank me" has to be said again next session while
     * "never prank me" is remembered forever.
     */
    public void forgetSession(UUID player) {
        if (player == null) {
            return;
        }
        optedIn.remove(player);
        pending.remove(player);
        save();
    }

    // ------------------------------------------------------------------ names

    /** Records a name so audit lines and deny messages can name an offline player. */
    public void remember(ServerPlayer player) {
        if (player == null) {
            return;
        }
        names.put(player.getUUID(), player.getGameProfile().getName());
    }

    /** Best-effort name for a UUID: online first, then our own record, then the profile cache. */
    public String nameOf(UUID id) {
        if (id == null) {
            return "console";
        }
        if (id.equals(ALL)) {
            return "everyone";
        }
        if (server != null) {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) {
                return online.getGameProfile().getName();
            }
            GameProfile cached = server.getProfileCache() == null ? null : server.getProfileCache().get(id);
            if (cached != null && cached.getName() != null) {
                return cached.getName();
            }
        }
        String remembered = names.get(id);
        return remembered == null ? id.toString().substring(0, 8) : remembered;
    }

    // ------------------------------------------------------------ persistence

    public void load() {
        optedIn.clear();
        allowed.clear();
        denied.clear();
        pending.clear();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Object root = Json.read(text);
            if (root == null) {
                // A file we cannot parse is treated as "nobody has consented", never as
                // "everybody has". Fail closed.
                mod.logger().warn("consent.json could not be parsed; treating every player as NOT opted in.");
                return;
            }
            Map<String, Object> players = Json.object(root, "players");
            for (Map.Entry<String, Object> entry : players.entrySet()) {
                UUID id = parse(entry.getKey());
                if (id == null) {
                    continue;
                }
                Map<String, Object> body = asMap(entry.getValue());
                if (Boolean.TRUE.equals(body.get("opt-in"))) {
                    optedIn.put(id, true);
                }
                if (Boolean.TRUE.equals(body.get("denied"))) {
                    denied.add(id);
                }
                String name = body.get("name") instanceof String ? (String) body.get("name") : null;
                if (name != null) {
                    names.put(id, name);
                }
                Set<UUID> set = new HashSet<>();
                for (String raw : Json.stringList(body.get("allowed"))) {
                    UUID other = parse(raw);
                    if (other != null) {
                        set.add(other);
                    }
                }
                if (!set.isEmpty()) {
                    allowed.put(id, set);
                }
            }
            mod.logger().info("Loaded consent records for {} player(s); {} player(s) asked never to be pranked.",
                    optedIn.size(), denied.size());
        } catch (IOException e) {
            mod.logger().warn("Could not read consent.json: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    public void save() {
        Map<String, Object> players = new LinkedHashMap<>();
        Set<UUID> everyone = new LinkedHashSet<>();
        everyone.addAll(optedIn.keySet());
        everyone.addAll(allowed.keySet());
        everyone.addAll(denied);
        everyone.addAll(names.keySet());
        for (UUID id : everyone) {
            Map<String, Object> body = new LinkedHashMap<>();
            String name = names.get(id);
            if (name != null) {
                body.put("name", name);
            }
            body.put("opt-in", Boolean.TRUE.equals(optedIn.get(id)));
            if (denied.contains(id)) {
                body.put("denied", true);
            }
            Set<UUID> set = allowed.get(id);
            if (set != null && !set.isEmpty()) {
                List<String> raw = new ArrayList<>();
                for (UUID other : set) {
                    raw.add(other.toString());
                }
                body.put("allowed", raw);
            }
            players.put(id.toString(), body);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("_comment", "PrankCraft consent records. opt-in=true means the player agreed to be pranked; "
                + "denied=true means they asked never to be pranked again and will not be, whatever the config says.");
        root.put("players", players);

        try {
            Path parent = file.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                Files.createDirectories(parent);
            }
            Files.write(file, Json.write(root).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            mod.logger().warn("Failed to save consent.json: {}", e.getMessage());
        }
    }

    private static UUID parse(String raw) {
        if (raw == null || raw.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(raw.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
