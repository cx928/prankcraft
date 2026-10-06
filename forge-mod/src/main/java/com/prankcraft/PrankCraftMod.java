package com.prankcraft;

import com.mojang.authlib.GameProfile;
import com.prankcraft.audit.AuditLog;
import com.prankcraft.commands.PrankAdminCommand;
import com.prankcraft.commands.PrankCommand;
import com.prankcraft.config.Config;
import com.prankcraft.consent.ConsentManager;
import com.prankcraft.effects.FakeChatEffect;
import com.prankcraft.effects.FakeLoginEffect;
import com.prankcraft.effects.FakeTntEffect;
import com.prankcraft.effects.FakeWeatherEffect;
import com.prankcraft.effects.JumpscareEffect;
import com.prankcraft.effects.PhantomFootstepsEffect;
import com.prankcraft.effects.ScreenShakeEffect;
import com.prankcraft.fx.FakeTntManager;
import com.prankcraft.listeners.PrankSafetyListener;
import com.prankcraft.prank.PrankEffect;
import com.prankcraft.prank.PrankEngine;
import com.prankcraft.util.Text;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.network.FMLNetworkConstants;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PrankCraft for Forge 1.16.5 - a consent-gated, audit-logged prank toolkit for server operators.
 * Port of the Paper plugin's {@code com.prankcraft.PrankCraftPlugin}, server-side subset only.
 *
 * <p>Design rules baked into the code, not just the docs:
 * <ul>
 *   <li>every effect is cosmetic: packets, particles, sounds, titles and fake entities only;</li>
 *   <li><b>no effect ever changes another player's position, inventory, health, gamemode or
 *       session.</b> There is no method anywhere in this mod that calls into those APIs for a
 *       player other than the one who asked;</li>
 *   <li>consent is checked by {@link PrankEngine} immediately before an effect fires, not when the
 *       command is typed, so a target can revoke mid-event;</li>
 *   <li>everything is written to an audit log so the operator can answer for it.</li>
 * </ul>
 *
 * <p><b>Obligatory disclaimer.</b> Nothing in this mod listens to or retains any packet a client
 * sends beyond what vanilla itself does. There is no telemetry, no reporting, no external
 * connection of any kind.
 *
 * <p><b>Where the files live.</b> Forge resolves {@code FMLPaths.CONFIGDIR} to
 * {@code <game>/config} and writes this mod's config there. The consent record and the audit log
 * go to {@code <config>/prankcraft/} - the same directory the config file names in its comment, so
 * "who opted in" and "what was fired" sit next to the settings that produced them. On a dedicated
 * server that is {@code <server>/config/prankcraft/}, which is inside the server directory and
 * therefore backed up with the world.
 */
@Mod(PrankCraftMod.MOD_ID)
public final class PrankCraftMod {

    public static final String MOD_ID = "prankcraft";
    public static final String VERSION = "1.0.0";

    private static final Logger LOGGER = LogManager.getLogger("PrankCraft");

    /**
     * The effect order {@code /prank list} uses. Kept identical to the Paper module's
     * {@code DEFAULT_EFFECT_ORDER} so a diff between the two modules is meaningful: anything
     * present there and missing here is a feature this port does not have.
     */
    private static final String[] DEFAULT_EFFECT_ORDER = {
            "jumpscare", "fake-tnt", "phantom-footsteps", "screen-shake", "fake-chat",
            "fake-login", "fake-weather"
    };

    private final Config config;
    private final ConsentManager consentManager;
    private final AuditLog auditLog;
    private final FakeTntManager fakeTntManager;
    private final PrankEngine prankEngine;

    private final JumpscareEffect jumpscare;
    private final ScreenShakeEffect screenShake;
    private final PhantomFootstepsEffect phantomFootsteps;
    private final FakeLoginEffect fakeLogin;

    private final PrankSafetyListener safetyListener;
    private final Path dataDir;

    /** Delayed work, drained by {@link #onServerTickDrain}. Only ever touched on the server thread. */
    private final List<PendingTask> pendingTasks = new ArrayList<>();

    private MinecraftServer server;
    private boolean serverReady;

    public PrankCraftMod() {
        this.dataDir = FMLPaths.CONFIGDIR.get().resolve(MOD_ID);

        // ------------------------------------------------------------------ config
        // A SERVER-type config means Forge writes it to <game>/config/prankcraft-server.toml and
        // reloads it on /reload. Effect code reads every value through this spec at the moment it
        // needs it (see Config's field comments), so a config edit takes effect without a restart
        // and no effect can work from a stale copy.
        Pair<Config, net.minecraftforge.common.ForgeConfigSpec> configured =
                new net.minecraftforge.common.ForgeConfigSpec.Builder().configure(Config::new);
        this.config = configured.getLeft();
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, configured.getRight(),
                MOD_ID + "-server.toml");

        // ------------------------------------------------------------------ core services
        this.auditLog = new AuditLog(this);
        this.consentManager = new ConsentManager(this, dataDir.resolve("consent.json"));
        this.fakeTntManager = new FakeTntManager(this);
        this.prankEngine = new PrankEngine(this);

        // ------------------------------------------------------------------ effects
        this.jumpscare = new JumpscareEffect(this);
        this.screenShake = new ScreenShakeEffect(this);
        this.phantomFootsteps = new PhantomFootstepsEffect(this);
        this.fakeLogin = new FakeLoginEffect(this);

        prankEngine.register(jumpscare);
        prankEngine.register(new FakeTntEffect(this));
        prankEngine.register(screenShake);
        prankEngine.register(phantomFootsteps);
        prankEngine.register(new FakeChatEffect(this));
        prankEngine.register(fakeLogin);
        prankEngine.register(new FakeWeatherEffect(this));

        for (String id : DEFAULT_EFFECT_ORDER) {
            if (prankEngine.effect(id) == null) {
                LOGGER.warn("No implementation found for configured prank effect '{}'", id);
            }
        }

        // ------------------------------------------------------------------ events
        // The mod object itself is the event listener for lifecycle and tick events; the two
        // safety rails live in their own class so "what could possibly break the world" is one
        // file a reviewer can read in a minute.
        this.safetyListener = new PrankSafetyListener(this);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(safetyListener);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onServerSetup);

        // Server-side only. A client that has this mod installed and joins a server will run
        // nothing from it, and a server logs a friendly warning rather than refusing the
        // connection - which is what extensionPoint DISPLAYTEST is for.
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST,
                () -> Pair.of(() -> FMLNetworkConstants.IGNORESERVERONLY, (remote, isServer) -> true));
    }

    // ------------------------------------------------------------------ lifecycle

    private void onServerSetup(FMLDedicatedServerSetupEvent event) {
        LOGGER.info("PrankCraft dedicated-server setup: effects are server-side only, "
                + "no client mod is required.");
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        this.server = event.getServer();
        this.consentManager.attachServer(server);
        ensureDataDirectory();
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        this.server = event.getServer();
        this.consentManager.attachServer(server);
        this.serverReady = true;

        int enabled = 0;
        for (PrankEffect effect : prankEngine.effects()) {
            if (effect.enabled()) {
                enabled++;
            }
        }
        LOGGER.info("PrankCraft enabled - {} prank effect(s) loaded ({} enabled); consent gate {}"
                        + " (per-target {}), audit log {}; data directory {}",
                prankEngine.effects().size(), enabled,
                config.requireConsent.get() ? "ON" : "OFF",
                config.requirePerTargetConsent.get() ? "ON" : "OFF",
                config.auditEnabled.get() ? "ON" : "OFF",
                dataDir.toAbsolutePath());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        serverReady = false;
        fakeTntManager.shutdown();
        consentManager.save();
        auditLog.close();
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld() instanceof ServerLevel) {
            safetyListener.onLevelUnload((ServerLevel) event.getWorld());
        }
    }

    // ------------------------------------------------------------------ ticking

    /**
     * The one place the per-tick work of this mod is driven from.
     *
     * <p>One handler, called in a fixed order, rather than a handler per subsystem. Ordering is
     * safety-relevant and is therefore explicit:
     * <ol>
     *   <li>the safety sweep, which removes any display entity that lost its session;</li>
     *   <li>the fake-TNT fuse guard and session ticker;</li>
     *   <li>the cosmetic effects, which have no world state to keep safe;</li>
     *   <li>delayed work, such as the re-send of real blocks after a detonation.</li>
     * </ol>
     * Two handlers on the same event would make that order depend on registration, which is not a
     * thing a safety rail should do.
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !serverReady || server == null) {
            return;
        }
        safetyListener.sweepOrphans();
        fakeTntManager.onServerTick();
        screenShake.onServerTick();
        phantomFootsteps.onServerTick();
        drainDelayedTasks();
    }

    /**
     * Runs whatever {@link #schedule} queued and is now due.
     *
     * <p>Never lets a delayed task take the tick handler down with it: a prank that fails to
     * re-send a block is a cosmetic bug, and must not become a broken server.
     */
    private void drainDelayedTasks() {
        if (pendingTasks.isEmpty()) {
            return;
        }
        int now = server.getTickCount();
        pendingTasks.removeIf(pending -> {
            if (pending.dueTick > now) {
                return false;
            }
            try {
                pending.task.run();
            } catch (Throwable throwable) {
                LOGGER.warn("Delayed PrankCraft task failed: {}", throwable.toString());
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ commands

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        new PrankCommand(this).register(event.getDispatcher());
        new PrankAdminCommand(this).register(event.getDispatcher());
        LOGGER.info("Registered /prank and /prankcraft (both need operator level 2 for anything "
                + "that fires an effect).");
    }

    // ------------------------------------------------------------------ services

    /** Schedules work on the next server tick after {@code delayTicks}. */
    public void schedule(int delayTicks, Runnable task) {
        if (server == null) {
            return;
        }
        if (delayTicks <= 0) {
            server.execute(task);
            return;
        }
        server.execute(() -> pendingTasks.add(new PendingTask(server.getTickCount() + delayTicks, task)));
    }

    private final List<PendingTask> pendingTasks = new ArrayList<>();

    private static final class PendingTask {
        final int dueTick;
        final Runnable task;

        PendingTask(int dueTick, Runnable task) {
            this.dueTick = dueTick;
            this.task = task;
        }
    }

    /** Re-reads the consent store. The config itself is reloaded by Forge on {@code /reload}. */
    public void reloadConfig() {
        consentManager.load();
        LOGGER.info("PrankCraft reloaded: consent records re-read from {}",
                dataDir.resolve("consent.json").toAbsolutePath());
    }

    /**
     * Best-effort display name for a UUID: online player first, then the server's profile cache,
     * then this mod's own consent record, then a short id. Never invents a name.
     */
    public String nameOf(UUID id) {
        ServerPlayer online = player(id);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        if (server != null && server.getProfileCache() != null) {
            GameProfile cached = server.getProfileCache().get(id);
            if (cached != null && cached.getName() != null) {
                return cached.getName();
            }
        }
        return id == null ? "console" : id.toString().substring(0, 8);
    }

    /** Resolves a name to a UUID without requiring the player to be online. */
    public UUID lookupOfflineId(String name) {
        if (server == null || name == null || name.isEmpty()) {
            return null;
        }
        ServerPlayer online = playerByName(name);
        if (online != null) {
            return online.getUUID();
        }
        if (server.getProfileCache() != null) {
            GameProfile cached = server.getProfileCache().get(name);
            if (cached != null) {
                return cached.getId();
            }
        }
        return null;
    }

    public ServerPlayer player(UUID id) {
        return server == null || id == null ? null : server.getPlayerList().getPlayer(id);
    }

    public ServerPlayer playerByName(String name) {
        if (server == null || name == null) {
            return null;
        }
        for (ServerPlayer candidate : server.getPlayerList().getPlayers()) {
            if (candidate.getGameProfile().getName().equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        return null;
    }

    public List<ServerPlayer> onlinePlayers() {
        return server == null ? new ArrayList<>() : new ArrayList<>(server.getPlayerList().getPlayers());
    }

    /** Tells staff (level 2 and above) that a prank fired. Never tells the target anything false. */
    public void announce(String raw) {
        if (!config.broadcastToStaff.get() || server == null) {
            return;
        }
        for (ServerPlayer staff : server.getPlayerList().getPlayers()) {
            if (staff.hasPermissions(2)) {
                Text.prefixed(staff, raw);
            }
        }
        LOGGER.info(Text.strip(raw));
    }

    // ------------------------------------------------------------------ wiring

    private void ensureDataDirectory() {
        try {
            if (!Files.isDirectory(dataDir)) {
                Files.createDirectories(dataDir);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not create the PrankCraft data directory {}: {}",
                    dataDir.toAbsolutePath(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ accessors

    public Config config() {
        return config;
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

    public JumpscareEffect jumpscare() {
        return jumpscare;
    }

    public ScreenShakeEffect shake() {
        return screenShake;
    }

    public PhantomFootstepsEffect footsteps() {
        return phantomFootsteps;
    }

    public FakeLoginEffect login() {
        return fakeLogin;
    }

    public PrankSafetyListener safety() {
        return safetyListener;
    }

    public MinecraftServer server() {
        return server;
    }

    public Path auditDirectory() {
        return dataDir;
    }

    /** How many players have a consent record on disk, for {@code /prankcraft status}. */
    public int consentRecordCount() {
        return consentManager.recordCount();
    }

    public String modVersion() {
        return VERSION;
    }

    public Logger logger() {
        return LOGGER;
    }
}
