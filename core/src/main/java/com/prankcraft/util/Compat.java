package com.prankcraft.util;

import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;

import java.lang.reflect.Method;

/**
 * Calls API members that do not exist on every supported server version.
 *
 * <p>The plugin compiles against the newest Paper API but has to load on 1.16.5 and (eventually)
 * 1.12.2. Two calls used by the effects are newer than that:
 *
 * <ul>
 *   <li>{@code Player#sendActionBar(String)} — absent on 1.16.5 (verified against the real
 *       1.16.5 API jar, which only has the title overloads);</li>
 *   <li>{@code HumanEntity#openAnvil(Location, boolean)} — named {@code openWorkbench} on 1.16.5,
 *       with no no-argument variant.</li>
 * </ul>
 *
 * <p>Both are resolved once, reflectively, and skipped when missing. Verified by compilation
 * rather than assumption: the {@code legacy-1165} module compiles this file against the 1.16.5 API
 * with Java 8, which is what makes the cross-version claim testable instead of hopeful.
 *
 * <p>This is the same pattern {@link com.prankcraft.fx.Fx} uses for particles and sounds that were
 * renamed between versions.
 */
public final class Compat {

    private static boolean actionBarResolved;
    private static Method actionBarMethod;

    private static boolean inventoryTitleResolved;
    private static Method setTitleMethod;

    private Compat() {
    }

    /**
     * Shows a line above the hotbar, or does nothing when the server is too old.
     *
     * @return true when the message was actually sent
     */
    public static boolean actionBar(Player player, String text) {
        if (player == null || text == null) {
            return false;
        }
        if (!actionBarResolved) {
            actionBarMethod = findMethod(player.getClass(), "sendActionBar", String.class);
            actionBarResolved = true;
        }
        if (actionBarMethod == null) {
            return false;
        }
        try {
            actionBarMethod.invoke(player, text);
            return true;
        } catch (Throwable ignored) {
            // Client gone, or a server build whose signature differs. Never worth an exception.
            return false;
        }
    }

    /** True when this server can show action-bar text at all. Used to pick a fallback message. */
    public static boolean supportsActionBar(Player player) {
        if (!actionBarResolved && player != null) {
            actionBarMethod = findMethod(player.getClass(), "sendActionBar", String.class);
            actionBarResolved = true;
        }
        return actionBarMethod != null;
    }

    /**
     * Opens the throwaway window the hotbar-shuffle trick borrows as a fake "toast".
     *
     * <p>Returns {@code null} when the server has no anvil view, which the caller treats as
     * "skip the window and keep the rest of the effect".
     */
    public static InventoryView openAnvil(Player player) {
        if (player == null) {
            return null;
        }
        // 1.14+: openAnvil(Location, boolean). The location is unused by the server.
        InventoryView view = invokeInventory(player, "openAnvil",
                new Class<?>[]{org.bukkit.Location.class, boolean.class},
                new Object[]{null, Boolean.TRUE});
        if (view != null) {
            return view;
        }
        // 1.13 and earlier called it openWorkbench, and it needs a real location.
        return invokeInventory(player, "openWorkbench",
                new Class<?>[]{org.bukkit.Location.class, boolean.class},
                new Object[]{player.getLocation(), Boolean.TRUE});
    }

    /** Renames an open window. Absent on the oldest servers. */
    public static boolean title(InventoryView view, String title) {
        if (view == null || title == null) {
            return false;
        }
        if (!inventoryTitleResolved) {
            setTitleMethod = findMethod(view.getClass(), "setTitle", String.class);
            inventoryTitleResolved = true;
        }
        if (setTitleMethod == null) {
            return false;
        }
        try {
            setTitleMethod.invoke(view, title);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static InventoryView invokeInventory(Player player, String name, Class<?>[] types, Object[] args) {
        Method method = findMethod(player.getClass(), name, types);
        if (method == null) {
            return null;
        }
        try {
            Object result = method.invoke(player, args);
            return result instanceof InventoryView ? (InventoryView) result : null;
        } catch (Throwable ignored) {
            // Older servers throw when the view cannot be opened; not worth surfacing.
            return null;
        }
    }

    /**
     * Looks up an offline player by name, without creating a stub record when the server can
     * avoid it.
     *
     * <p>{@code Bukkit#getOfflinePlayerIfCached} is a Paper addition and is absent from Spigot
     * 1.16.5 (verified against the real API jar). Falling back to
     * {@code Bukkit#getOfflinePlayer(String)} is correct but has a side effect worth knowing
     * about: on old servers it creates a player-data stub for a name that may not exist. That is
     * why the reflective lookup is tried first.
     */
    public static org.bukkit.OfflinePlayer lookupOfflinePlayer(String name) {
        try {
            Method method = org.bukkit.Bukkit.class.getMethod("getOfflinePlayerIfCached", String.class);
            Object result = method.invoke(null, name);
            if (result instanceof org.bukkit.OfflinePlayer) {
                return (org.bukkit.OfflinePlayer) result;
            }
        } catch (Throwable ignored) {
            // Not a Paper server, or the name is not cached. Fall through.
        }
        return org.bukkit.Bukkit.getOfflinePlayer(name);
    }

    /**
     * Finds a public method by name and parameter types, walking up the class hierarchy.
     *
     * <p>Reflection rather than a direct call because the member may not exist at all - a direct
     * call would fail to compile against 1.16.5, and a hard reference would throw
     * NoSuchMethodError on a server that lacks it.
     */
    private static Method findMethod(Class<?> from, String name, Class<?>... parameterTypes) {
        for (Class<?> type = from; type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Method method = type.getMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Try the next type up the hierarchy.
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }
}
