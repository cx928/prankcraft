package com.prankcraft.consent;

import com.prankcraft.PrankCraftPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks who has agreed to be pranked.
 *
 * <p>Two independent gates exist, both configurable:
 * <ul>
 *   <li>{@code require-consent: true} — a target must have opted in at all.</li>
 *   <li>{@code require-per-target-consent: true} — and must have allowed <em>that</em> prankster.</li>
 * </ul>
 * Consent is the difference between a prank and harassment, so it is on by default and
 * both /prank allow and /prank deny take effect immediately.
 */
public final class ConsentManager {

    private final PrankCraftPlugin plugin;
    private final File file;

    /** player -> whether they accept pranks at all */
    private final Map<UUID, Boolean> optedIn = new LinkedHashMap<>();
    /** player -> pranksters explicitly allowed to prank them */
    private final Map<UUID, Set<UUID>> allowed = new LinkedHashMap<>();
    /** incoming invitations waiting for a yes/no */
    private final Map<UUID, Deque<UUID>> pending = new LinkedHashMap<>();

    public ConsentManager(PrankCraftPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "consent.yml");
        load();
    }

    // ------------------------------------------------------------------ gates

    /** True when {@code prankster} may currently prank {@code target}, honouring the config gates. */
    public boolean mayPrank(UUID prankster, UUID target) {
        if (target == null) {
            return false;
        }
        // The rule itself lives in ConsentRules, which has no Bukkit dependency and is unit
        // tested; this method only supplies the current state.
        ConsentRules.Decision decision = ConsentRules.decide(
                prankster,
                target,
                Boolean.TRUE.equals(optedIn.get(target)),
                plugin.config().requireConsent,
                plugin.config().requirePerTargetConsent,
                allowed.get(target),
                isExempt(target),
                ConsentRules.ANYONE);
        return ConsentRules.allowed(decision);
    }

    /** Players holding {@code consent.bypass} can never be pranked, even by mistake. */
    public boolean isExempt(UUID target) {
        Player online = Bukkit.getPlayer(target);
        return online != null && online.hasPermission("prankcraft.consent.bypass");
    }

    public String denyReason(UUID prankster, UUID target) {
        OfflinePlayer tp = Bukkit.getOfflinePlayer(target);
        String name = tp.getName() == null ? target.toString() : tp.getName();
        if (isExempt(target)) {
            return "&e" + name + " &cis protected by &fprankcraft.consent.bypass&c.";
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
        optedIn.put(target, true);
        allowed.computeIfAbsent(target, k -> new HashSet<>()).add(prankster);
        save();
    }

    public void allowAll(UUID target) {
        optedIn.put(target, true);
        allowed.computeIfAbsent(target, k -> new HashSet<>()).add(ConsentRules.ANYONE);
        save();
    }

    public void deny(UUID target, UUID prankster) {
        Set<UUID> set = allowed.get(target);
        if (set != null) {
            set.remove(prankster);
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
        // Collections/EnumSet rather than Set.of/Set.copyOf: Java 9+/10+ only, and this source is
        // shared with the legacy builds that must run on Java 8 servers.
        return set == null
                ? Collections.<UUID>emptySet()
                : Collections.unmodifiableSet(new HashSet<>(set));
    }

    /** The wildcard entry meaning "anyone on the server may prank me". */
    public static final UUID ALL = ConsentRules.ANYONE;

    // -------------------------------------------------------------- requests

    /** Queues an invitation from {@code prankster} to {@code target}; returns false if already queued. */
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
        return queue == null ? Collections.<UUID>emptyList() : new ArrayList<>(queue);
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

    // ------------------------------------------------------------ persistence

    public void load() {
        optedIn.clear();
        allowed.clear();
        pending.clear();
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            UUID id = parse(key);
            if (id == null) {
                continue;
            }
            optedIn.put(id, yaml.getBoolean(key + ".opt-in", false));
            Set<UUID> set = new HashSet<>();
            for (String raw : yaml.getStringList(key + ".allowed")) {
                UUID other = parse(raw);
                if (other != null) {
                    set.add(other);
                }
            }
            if (!set.isEmpty()) {
                allowed.put(id, set);
            }
        }
        plugin.getLogger().info("Loaded consent records for " + optedIn.size() + " player(s).");
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        Set<UUID> everyone = new HashSet<>(optedIn.keySet());
        everyone.addAll(allowed.keySet());
        for (UUID id : everyone) {
            String base = id.toString();
            yaml.set(base + ".opt-in", Boolean.TRUE.equals(optedIn.get(id)));
            Set<UUID> set = allowed.get(id);
            if (set != null && !set.isEmpty()) {
                List<String> raw = new ArrayList<>();
                for (UUID other : set) {
                    raw.add(other.toString());
                }
                yaml.set(base + ".allowed", raw);
            }
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                plugin.getLogger().warning("Could not create " + parent.getAbsolutePath());
            }
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save consent.yml: " + e.getMessage());
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
