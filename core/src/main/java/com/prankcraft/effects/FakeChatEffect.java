package com.prankcraft.effects;

import com.prankcraft.PrankCraftPlugin;
import com.prankcraft.prank.PrankEffect;
import com.prankcraft.util.ChatGuard;
import com.prankcraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Random;

/**
 * Prints a line in chat that looks like the target said it.
 *
 * <p>This is the only effect here that can genuinely mislead a third party, so it is fenced in:
 * <ul>
 *   <li>the lines come from your config, so they are always your words, not the player's;</li>
 *   <li>they are cleaned of colour codes and capped in length, so nobody can forge a
 *       {@code [Admin]} prefix or a fake plugin message;</li>
 *   <li>the target is excluded from the broadcast and is told it happened, so they are never
 *       gaslit about their own chat log.</li>
 * </ul>
 * Keep the list silly. A prank line should make people laugh at the sender, never at a person
 * who is not in the room.
 */
public final class FakeChatEffect implements PrankEffect {

    private final PrankCraftPlugin plugin;
    private final Random random = new Random();

    public FakeChatEffect(PrankCraftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        // Must match the "fake-chat" key in config.yml and in DEFAULT_EFFECT_ORDER.
        return "fake-chat";
    }

    @Override
    public String description() {
        return "Someone appears to say a silly configured line. The target is told, so they are never gaslit.";
    }

    @Override
    public String permission() {
        return "prankcraft.effect.fakechat";
    }

    @Override
    public int defaultDurationSeconds() {
        return 0;
    }

    @Override
    public String fire(Player target) {
        // Lines come from the cached snapshot rather than a config read per fire.
        List<String> lines = plugin.config().fakeChatLines;
        if (lines.isEmpty()) {
            return null;
        }
        String raw = lines.get(random.nextInt(lines.size()));
        // ChatGuard strips anything that could impersonate the server or a staff member. The
        // end-to-end suite asserts this against a live client as well.
        String body = ChatGuard.sanitise(Text.color(raw));
        if (body.isEmpty()) {
            return null;
        }
        if (ChatGuard.isSuspicious(raw)) {
            // Tell the operator their line had to be changed, rather than silently altering it.
            plugin.getLogger().warning("Fake-chat line needed sanitising before broadcast: \""
                    + Text.strip(raw) + "\" -> \"" + body + "\"");
        }
        String rendered = "&f<" + target.getName() + "> &r" + body;

        int radius = plugin.getConfig().getInt("pranks.fake-chat.radius", 0);
        int shown = 0;
        Location origin = target.getLocation();
        World targetWorld = target.getWorld();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(target)) {
                continue;
            }
            if (radius > 0 && viewer.getWorld().equals(targetWorld)
                    && viewer.getLocation().distanceSquared(origin) > (double) radius * radius) {
                continue;
            }
            Text.msg(viewer, rendered);
            shown++;
        }

        Text.prefixed(target, "&7A prank line was just shown in chat as you: &f" + body);
        return "said=\"" + body + "\" shown-to=" + shown;
    }
}
