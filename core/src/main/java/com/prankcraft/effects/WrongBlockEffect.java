package com.prankcraft.effects;

import com.prankcraft.PrankCraftPlugin;
import com.prankcraft.prank.PrankEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replaces the world around the target with nonsense - in their client only.
 *
 * <p>The server's blocks are never modified. Each chosen position is sent to that one player as
 * a block-change packet, refreshed while the effect runs, and reverted to the real block when it
 * ends. Nobody else sees a thing, and a restart mid-effect cannot leave a phantom block behind.
 */
public final class WrongBlockEffect implements PrankEffect {

    /**
     * One immutable block state per material, shared by every illusion and every revert.
     * {@code Material#createBlockData()} is cheap but not free, and the refresh task used to call
     * it once per block per tick.
     */
    private static final Map<Material, BlockData> STATES = new ConcurrentHashMap<>();

    private final PrankCraftPlugin plugin;
    private final Random random = new Random();
    private final Map<UUID, BukkitTask> tasks = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Location, BlockData>> illusions = new ConcurrentHashMap<>();

    public WrongBlockEffect(PrankCraftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "wrong-block";
    }

    @Override
    public String description() {
        return "Nearby blocks turn into random ones in the target's client. Server world untouched.";
    }

    @Override
    public String permission() {
        return "prankcraft.effect.wrongblock";
    }

    @Override
    public int defaultDurationSeconds() {
        return 6;
    }

    @Override
    public String fire(Player target) {
        cancel(target);

        // Palette and block states come from the cached snapshot: Material.matchMaterial() walks
        // the registry, and createBlockData() allocates. Neither belongs in a repeat task.
        List<Material> palette = plugin.config().wrongBlockPalette;
        if (palette.isEmpty()) {
            return null;
        }
        int radius = Math.max(1, plugin.getConfig().getInt("pranks.wrong-block.radius", 2));
        long interval = Math.max(1L, plugin.getConfig().getLong("pranks.wrong-block.interval-ticks", 20L));

        Map<Location, BlockData> chosen = new LinkedHashMap<>();
        World world = target.getWorld();
        Location origin = target.getLocation();
        int baseX = origin.getBlockX();
        int baseY = origin.getBlockY();
        int baseZ = origin.getBlockZ();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                // The chunk is decided by (x,z) alone, so test it once per column rather than
                // once per block - three times fewer lookups.
                if (!world.isChunkLoaded((baseX + dx) >> 4, (baseZ + dz) >> 4)) {
                    continue;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    Block block = world.getBlockAt(baseX + dx, baseY + dy, baseZ + dz);
                    Material type = block.getType();
                    if (type.isAir() || !type.isSolid() || random.nextDouble() > 0.55) {
                        continue;
                    }
                    chosen.put(block.getLocation(), stateOf(palette.get(random.nextInt(palette.size()))));
                }
            }
        }
        if (chosen.isEmpty()) {
            return null;
        }

        illusions.put(target.getUniqueId(), chosen);
        apply(target, chosen);

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!target.isOnline()) {
                cancel(target);
                return;
            }
            apply(target, chosen);
        }, interval, interval);
        tasks.put(target.getUniqueId(), task);

        return "blocks=" + chosen.size() + " radius=" + radius;
    }

    /**
     * Block states are immutable and shared between the repeat task and every revert, so one
     * instance per material is built the first time it is used and reused from then on.
     */
    private BlockData stateOf(Material material) {
        return STATES.computeIfAbsent(material, Material::createBlockData);
    }

    private void apply(Player viewer, Map<Location, BlockData> chosen) {
        chosen.forEach((location, state) -> {
            try {
                viewer.sendBlockChange(location, state);
            } catch (Throwable ignored) {
                // position unloaded or client gone; the revert pass will tidy up
            }
        });
    }

    @Override
    public void cancel(Player target) {
        BukkitTask task = tasks.remove(target.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        Map<Location, BlockData> chosen = illusions.remove(target.getUniqueId());
        if (chosen == null || target == null || !target.isOnline()) {
            return;
        }
        chosen.keySet().forEach(location -> {
            try {
                if (location.getWorld() != null && location.getChunk().isLoaded()) {
                    target.sendBlockChange(location, location.getBlock().getBlockData());
                }
            } catch (Throwable ignored) {
                // nothing to revert for an unloaded chunk: the client reloads it from the server
            }
        });
    }
}
