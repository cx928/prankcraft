package com.prankcraft.effects;

import com.prankcraft.PrankCraftPlugin;
import com.prankcraft.fx.Fx;
import com.prankcraft.prank.PrankEffect;
import com.prankcraft.util.Compat;
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

        boolean windowShown = false;
        if (toast) {
            windowShown = showFakeWindow(target, title);
        }
        // The action bar is the fallback on servers that have no anvil view, and a second cue
        // everywhere else. Compat skips it silently when the server predates the API.
        Text.actionBar(target, "&e" + title);

        String soundName = plugin.getConfig().getString("pranks.hotbar-shuffle.sound", "UI_BUTTON_CLICK");
        Sound sound = soundName == null ? null : Fx.sound(soundName);
        if (sound == null) {
            sound = Fx.clickSound();
        }
        if (sound != null) {
            target.playSound(target.getLocation(), sound, 0.8f, 1.4f);
        }
        return "title=\"" + title + "\" toast=" + toast + " window=" + windowShown;
    }

    /**
     * Opens a throwaway inventory view purely to borrow its rename text box as a "toast" - the
     * same trick vanilla uses for a ghost window. The view is closed shortly afterwards and
     * contains nothing, so nothing can be taken from it.
     *
     * <p>Routed through {@link Compat} because the anvil view is {@code openWorkbench} on 1.16.5
     * and does not exist before 1.14. When the server cannot provide one, the effect carries on
     * with its action-bar line and sound rather than failing.
     *
     * @return true when a window was actually opened
     */
    private boolean showFakeWindow(Player target, String title) {
        InventoryView view = Compat.openAnvil(target);
        if (view == null) {
            return false;
        }
        Compat.title(view, title);
        plugin.runSync(() -> {
            if (target.isOnline() && target.getOpenInventory().equals(view)) {
                target.closeInventory();
            }
        }, 30L);
        return true;
    }
}
