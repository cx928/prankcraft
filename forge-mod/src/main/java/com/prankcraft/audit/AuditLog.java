package com.prankcraft.audit;

import com.prankcraft.PrankCraftMod;
import com.prankcraft.util.Json;
import net.minecraft.server.level.ServerPlayer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only record of every effect that was fired, who fired it, and at whom.
 * Port of {@code com.prankcraft.audit.AuditLog}.
 *
 * <p>This exists to protect players <em>and</em> the operator: if someone complains that
 * "the server faked my death message" or "somebody made it look like I said that", there is a
 * timestamped answer. That matters more here than in the Paper module, because a Forge server
 * has no permission system: the audit file is the only record of who did what.
 *
 * <p>Kept in memory for the most recent {@value #MEMORY_LIMIT} entries so
 * {@code /prankcraft audit} can show them, and mirrored to
 * {@code world/serverconfig/prankcraft/audit-YYYY-MM-DD.log} as one JSON object per line.
 */
public final class AuditLog {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int MEMORY_LIMIT = 200;

    /** One recorded action. */
    public static final class Entry {
        public final long millis;
        public final String actorName;
        public final UUID actorId;
        public final String targetName;
        public final UUID targetId;
        public final String effect;
        public final String detail;

        Entry(long millis, String actorName, UUID actorId, String targetName, UUID targetId,
              String effect, String detail) {
            this.millis = millis;
            this.actorName = actorName;
            this.actorId = actorId;
            this.targetName = targetName;
            this.targetId = targetId;
            this.effect = effect;
            this.detail = detail == null ? "" : detail;
        }

        /** The JSON line written to disk. One object per line, so the file stays grep-able. */
        public String jsonLine() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("time", LocalDateTime.now().format(STAMP));
            map.put("actor", actorName);
            map.put("actorId", actorId == null ? null : actorId.toString());
            map.put("target", targetName);
            map.put("targetId", targetId == null ? null : targetId.toString());
            map.put("effect", effect);
            map.put("detail", detail);
            return Json.write(map).trim();
        }

        /** Human-readable one-liner for chat. */
        public String pretty() {
            return "&7" + LocalDateTime.now().format(STAMP)
                    + " &f" + actorName + " &7-> &f" + targetName
                    + " &8[&d" + effect + "&8] &7" + detail;
        }
    }

    private final PrankCraftMod mod;
    private final Deque<Entry> recent = new ArrayDeque<>();

    private String currentDay;
    private BufferedWriter writer;

    public AuditLog(PrankCraftMod mod) {
        this.mod = mod;
    }

    // ------------------------------------------------------------------ writing

    public void record(UUID actorId, UUID targetId, String effect, String detail) {
        if (!mod.config().auditEnabled.get()) {
            return;
        }
        Entry entry = new Entry(System.currentTimeMillis(), mod.nameOf(actorId), actorId,
                mod.nameOf(targetId), targetId, effect, detail);

        synchronized (recent) {
            recent.addLast(entry);
            while (recent.size() > MEMORY_LIMIT) {
                recent.removeFirst();
            }
        }

        if (mod.config().auditConsole.get()) {
            mod.logger().info("[audit] {} -> {} [{}] {}", entry.actorName, entry.targetName,
                    effect, entry.detail);
        }
        if (mod.config().auditFile.get()) {
            appendToFile(entry);
        }
    }

    public List<Entry> recent(int limit) {
        synchronized (recent) {
            List<Entry> all = new ArrayList<>(recent);
            int from = Math.max(0, all.size() - Math.max(1, limit));
            List<Entry> tail = new ArrayList<>(all.subList(from, all.size()));
            java.util.Collections.reverse(tail);
            return tail;
        }
    }

    private void appendToFile(Entry entry) {
        String day = LocalDateTime.now().format(DAY);
        try {
            if (writer == null || !day.equals(currentDay)) {
                openFor(day);
            }
            if (writer == null) {
                return;
            }
            writer.write(entry.jsonLine());
            writer.newLine();
            // Flushed line by line on purpose: the whole value of this file is that it
            // survives a crash. Losing the last prank to a power cut is not acceptable.
            writer.flush();
        } catch (IOException e) {
            mod.logger().warn("Could not write the audit log: {}", e.getMessage());
            close();
        }
    }

    private void openFor(String day) throws IOException {
        close();
        Path file = mod.auditDirectory().resolve("audit-" + day + ".log");
        currentDay = day;
        Path parent = file.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            Files.createDirectories(parent);
        }
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    }

    public void close() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // Nothing useful to do while shutting down.
            }
            writer = null;
        }
    }

    /** Convenience for command code, which may be run from the console (actor id {@code null}). */
    public void recordFrom(Object sender, ServerPlayer target, String effect, String detail) {
        record(actorId(sender), target == null ? null : target.getUUID(), effect, detail);
    }

    private static UUID actorId(Object sender) {
        return sender instanceof ServerPlayer ? ((ServerPlayer) sender).getUUID() : null;
    }
}
