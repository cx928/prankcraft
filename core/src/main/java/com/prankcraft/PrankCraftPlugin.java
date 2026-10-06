package com.prankcraft;

import com.prankcraft.audit.AuditLog;
import com.prankcraft.commands.ConsentCommand;
import com.prankcraft.commands.PrankAdminCommand;
import com.prankcraft.commands.PrankCommand;
import com.prankcraft.config.Cfg;
import com.prankcraft.consent.ConsentManager;
import com.prankcraft.effects.ArrowRainEffect;
import com.prankcraft.effects.FakeChatEffect;
import com.prankcraft.effects.FakeDeathEffect;
import com.prankcraft.effects.FakeLoginEffect;
import com.prankcraft.effects.FakeTntEffect;
import com.prankcraft.effects.FakeWeatherEffect;
import com.prankcraft.effects.HotbarShuffleEffect;
import com.prankcraft.effects.JumpscareEffect;
import com.prankcraft.effects.PhantomFootstepsEffect;
import com.prankcraft.effects.ScreenShakeEffect;
import com.prankcraft.effects.WrongBlockEffect;
import com.prankcraft.fx.FakeTntManager;
import com.prankcraft.listeners.PrankSafetyListener;
import com.prankcraft.prank.PrankEngine;
import com.prankcraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PrankCraft - a consent-gated, audit-logged prank toolkit for server operators.
 *
 * <p>Design rules baked into the code, not just the docs:
 * <ul>
 *   <li>every effect is cosmetic: packets, particles, sounds, titles and fake entities only;</li>
 *   <li>no effect ever changes another player's position, inventory, health or session;</li>
 *   <li>consent is checked by {@link PrankEngine} immediately before an effect fires, not when
 *       the command is typed, so a target can revoke mid-event;</li>
 *   <li>everything is written to an audit log so the operator can answer for it.</li>
 * </ul>
 */
public final class PrankCraftPlugin extends JavaPlugin {

    /**
     * The effect ids the config ships with. Kept in one place so that
     * {@link #registerEffects()} can prove every configured id has an implementation - a
     * mismatch between a config key and an effect id would otherwise mean an effect that
     * silently never fires.
     */
    private static final String[] DEFAULT_EFFECT_ORDER = {
            "jumpscare", "fake-tnt", "phantom-footsteps", "screen-shake", "fake-chat",
            "fake-death", "fake-login", "fake-weather", "arrow-rain", "hotbar-shuffle", "wrong-block"
    };

    private ConsentManager consentManager;
    private PrankEngine prankEngine;
    private FakeTntManager fakeTntManager;
    private AuditLog auditLog;
    private Cfg cfg;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        // Resolve every hot-path setting once. The fake-TNT scheduler reads these every couple of
        // ticks, and Bukkit's getConfig() does a path walk plus typed conversion per call.
        this.cfg = new Cfg(this);

        this.auditLog = new AuditLog(this);
        this.consentManager = new ConsentManager(this);
        this.fakeTntManager = new FakeTntManager(this);
        this.fakeTntManager.start();
        this.prankEngine = new PrankEngine(this);
        registerEffects();

        getServer().getPluginManager().registerEvents(new PrankSafetyListener(this), this);
        wireCommands();

        getLogger().info("PrankCraft enabled - " + prankEngine.effects().size() + " prank effect(s) loaded; consent gate "
                + (config().requireConsent ? "ON" : "OFF")
                + ", audit log " + (config().auditEnabled ? "ON" : "OFF"));
    }

    /**
     * Re-reads config.yml, consent.yml and the cached settings, then restarts the fake-TNT
     * scheduler so a changed tick period takes effect.
     *
     * <p>Both {@code /prankcraft reload} and {@code /prank reload} call this, so the two commands
     * cannot drift apart - which they previously did, one of them skipping the cache entirely.
     */
    public void reloadEverything() {
        reloadConfig();
        if (cfg == null) {
            cfg = new Cfg(this);
        } else {
            cfg.reload();
        }
        if (consentManager != null) {
            consentManager.load();
        }
        if (prankEngine != null) {
            prankEngine.refresh();
        }
        if (fakeTntManager != null) {
            fakeTntManager.shutdown();
            fakeTntManager.start();
        }
    }

    @Override
    public void onDisable() {
        if (fakeTntManager != null) {
            fakeTntManager.shutdown();
        }
        if (consentManager != null) {
            consentManager.save();
        }
        // Static tracking sets must not survive a reload.
        ArrowRainEffect.forgetAll();
        getServer().getScheduler().cancelTasks(this);
    }

    private void registerEffects() {
        prankEngine.register(new JumpscareEffect(this));
        prankEngine.register(new FakeTntEffect(this));
        prankEngine.register(new PhantomFootstepsEffect(this));
        prankEngine.register(new ScreenShakeEffect(this));
        prankEngine.register(new FakeChatEffect(this));
        prankEngine.register(new FakeDeathEffect(this));
        prankEngine.register(new FakeLoginEffect(this));
        prankEngine.register(new FakeWeatherEffect(this));
        prankEngine.register(new ArrowRainEffect(this));
        prankEngine.register(new HotbarShuffleEffect(this));
        prankEngine.register(new WrongBlockEffect(this));

        for (String id : DEFAULT_EFFECT_ORDER) {
            if (prankEngine.effect(id) == null) {
                getLogger().warning("No implementation found for configured prank effect '" + id + "'");
            }
        }
    }

    private void wireCommands() {
        bind("prank", new PrankCommand(this));
        bind("prankcraft", new PrankAdminCommand(this));
        bind("prankconsent", new ConsentCommand(this));
    }

    private void bind(String name, org.bukkit.command.CommandExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Command /" + name + " is missing from plugin.yml");
            return;
        }
        command.setExecutor(executor);
        // Plain instanceof cast rather than a pattern variable: this source is shared with the
        // legacy builds that must run on Java 8 servers.
        if (executor instanceof org.bukkit.command.TabCompleter) {
            command.setTabCompleter((org.bukkit.command.TabCompleter) executor);
        }
    }

    /** Convenience for effects that want to message everyone with a permission. */
    public void announce(String raw) {
        if (cfg != null && cfg.broadcastToStaff) {
            Text.broadcast("prankcraft.notify", raw);
        }
    }

    /** Cached configuration snapshot. Never read config.yml directly from a tick loop. */
    public Cfg config() {
        return cfg;
    }

    public ConsentManager consent() {
        return consentManager;
    }

    public PrankEngine pranks() {
        return prankEngine;
    }

    public FakeTntManager tnt() {
        return fakeTntManager;
    }

    public AuditLog audit() {
        return auditLog;
    }

    /** Safe wrapper: never let one bad effect take the scheduler down. */
    public void runSync(Runnable task, long delayTicks) {
        if (!isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTaskLater(this, task, Math.max(0L, delayTicks));
        } catch (IllegalStateException e) {
            getLogger().fine("Skipped delayed task during shutdown: " + e.getMessage());
        }
    }
}
