package com.prankcraft.effects;

import com.prankcraft.PrankCraftPlugin;
import com.prankcraft.fx.Fx;
import com.prankcraft.prank.PrankEffect;
import com.prankcraft.util.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;

import java.util.Arrays;
import java.util.List;

/**
 * A pure theatre prank: for a few seconds the target's hotbar genuinely looks scrambled.
 *
 * <p>Nothing is moved. Not one item, not one slot, no packet that claims otherwise - the
 * "shuffle" is a fake window title, a couple of clicks and a line of action-bar text. If a prank
 * made somebody drop their gear, or desync their inventory, it stopped being funny and became a
 * rollback; this effect is designed so that a rollback can never be needed.
 */
public final class HotbarShuffleEffect implements PrankEffect {

    // Arrays.asList, not List.of: Java 9+ only, and this source is shared with the legacy builds
    // that must run on Java 8 servers.
    private static final List<String> FAKE_TITLES = Arrays.asList(
            "Inventory sorted!",
            "Hotbar shuffled",
            "Sorting... 12/36",
            "Auto-organise complete",
            "Oops, wrong window"
    );

    private final PrankCraftPlugin plugin;

    public HotbarShuffleEffect(PrankCraftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "hotbar-shuffle";
    }

    @Override
    public String description() {
        return "Makes the hotbar look shuffled for a few seconds. Your real inventory is never touched.";
    }

    @Override
    public String permission() {
        return "prankcraft.effect.shuffle";
    }

    @Override
    public int defaultDurationSeconds() {
        return 4;
    }

    @Override
    public String fire(Player target) {
        String title = FAKE_TITLES.get((int) (Math.random() * FAKE_TITLES.size()));
        boolean toast = plugin.getConfig().getBoolean("pranks.hotbar-shuffle.show-toast", true);

        if (toast) {
            showFakeWindow(target, title);
        }
        target.sendActionBar(Text.color("&e" + title));

        String soundName = plugin.getConfig().getString("pranks.hotbar-shuffle.sound", "UI_BUTTON_CLICK");
        Sound sound = soundName == null ? null : Fx.sound(soundName);
        if (sound == null) {
            sound = Fx.clickSound();
        }
        if (sound != null) {
            target.playSound(target.getLocation(), sound, 0.8f, 1.4f);
        }
        return "title=\"" + title + "\" toast=" + toast;
    }

    /**
     * Opens a throwaway anvil view purely to borrow its rename text box as a "toast" - the same
     * trick vanilla uses for a ghost window. The view is closed on the next tick and contains
     * nothing, so nothing can be taken from it.
     */
    private void showFakeWindow(Player target, String title) {
        try {
            InventoryView view = target.openAnvil(null, true);
            if (view == null) {
                return;
            }
            view.setTitle(title);
            plugin.runSync(() -> {
                if (target.isOnline() && target.getOpenInventory().equals(view)) {
                    target.closeInventory();
                }
            }, 30L);
        } catch (Throwable throwable) {
            plugin.getLogger().fine("Fake shuffle window unavailable on this version: " + throwable.getMessage());
        }
    }
}
