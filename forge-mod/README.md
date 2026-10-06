# PrankCraft for Forge — Minecraft 1.16.5

> ## ⚠ NOT COMPILED — the Forge toolchain could not be resolved in the build environment. Run `gradlew build` on a machine with network access.
>
> This tree has **never been through `javac`**. It is complete, self-consistent, ready-to-compile
> source, and every Minecraft/Forge API it calls was verified by hand against Mojang's official
> 1.16.5 mappings (see "Verification performed" below) — but it is **not** a built jar, and it is
> **not** claimed to be. The exact blocker is recorded in `BUILD-STATUS.md`.
>
> **Before you trust this mod on a live server, build it and read the compiler output.** Expect to
> fix a small number of errors on the first build; see "Verification performed" for the specific
> places most likely to need a touch-up.

A **server-side only** Forge mod that ports the server-side subset of the PrankCraft Paper plugin
(`../core`). Players need **no client mod**: every effect is delivered to a vanilla client as a
vanilla packet.

This is a **separate build**. It is deliberately not part of the parent Maven reactor — it has its
own `build.gradle`, its own versioning and its own Gradle configuration, so a broken Forge
dependency can never turn the Paper plugin red, and vice versa.

---

## Build status

| Question | Answer |
| --- | --- |
| Does it compile? | **Unknown — never compiled.** |
| Did a build ever complete here? | **No.** The Gradle/MCP pipeline reaches the final AccessTransformers step and dies there. |
| Is the source complete? | Yes — 20 Java files plus `build.gradle`, `gradle.properties`, `settings.gradle`, `mods.toml`, `pack.mcmeta`. |
| Is the API usage verified? | Yes, symbol by symbol, against the official 1.16.5 mappings. See below. |

### Verification performed

There was no compiler to lean on, so the API surface was checked by reading Mojang's own 1.16.5
mapping files (`client.txt` / `server.txt`, downloaded from `piston-data.mojang.com`) and matching
every class, field and method this mod references against them. The mapping-sensitive facts that
were confirmed, and that a reviewer should re-check first, are:

* `ServerPlayer`, and the public `ServerPlayer.connection` field of type
  `ServerGamePacketListenerImpl` with its public `send(Packet)` method;
* `ClientboundSetTitlesPacket` with `Type.TITLE` / `Type.SUBTITLE` / `Type.TIMES` / `Type.CLEAR`
  (note `TITLE`, not `SET_TITLE`) and the two constructors used here;
* `ClientboundGameEventPacket(Type, **float**)` with `START_RAINING`, `STOP_RAINING`,
  `RAIN_LEVEL_CHANGE`, `THUNDER_LEVEL_CHANGE`;
* `ClientboundBlockUpdatePacket(BlockPos, BlockState)`,
  `ClientboundLevelParticlesPacket(ParticleOptions, boolean, double×3, float×4, int)`,
  `ClientboundSoundPacket(SoundEvent, SoundSource, double×3, float, float)`,
  `ClientboundLevelEventPacket(int, BlockPos, int, boolean)`,
  `ClientboundChatPacket(Component, ChatType, UUID)`,
  `ClientboundPlayerInfoPacket(Action, ServerPlayer[])`;
* `PrimedTnt` with `setFuse(int)`, `setNoGravity(boolean)`, `setDeltaMovement(Vec3)`,
  `setInvulnerable(boolean)`, `EntityType.TNT.create(Level)`;
* `ServerLevel.addFreshEntity`, `getAllLevels`, `getEntity`, `isLoaded`, `getBlockState`;
* `BlockPos.offset/getX/getY/getZ/below/immutable`, `Material.AIR/WATER/LAVA/SNOW/isReplaceable`;
* `TextComponent(String)`, `ChatType.CHAT`/`SYSTEM`, `Util.NIL_UUID`;
* `CommandSourceStack.hasPermission(int)`, `CommandSourceStack.sendSuccess(Component, boolean)`,
  `Commands.literal`/`Commands.argument`;
* `Registry.SOUND_EVENT.getOptional(ResourceLocation)` and `ResourceLocation.tryParse`.

**Not** verified by a compiler, and therefore the most likely places for a first-build error:

1. `net.minecraft.world.level.block.LevelEvent.PARTICLE_LARGE_SMOKE` — the `LevelEvent` constants
   are not present in Mojang's mapping files at all (the class has no renamed members), so the
   constant *name* comes from Forge/MCP documentation rather than from a mapping check.
2. Forge event class names and signatures — `ExplosionEvent.Start`/`Detonate`, `ChunkEvent.Unload`,
   `TickEvent.ServerTickEvent`, `RegisterCommandsEvent`, `ServerAboutToStartEvent`,
   `ServerStartedEvent`, `ServerStoppingEvent`, `WorldEvent.Unload`, `FMLDedicatedServerSetupEvent`.
   These are Forge classes and are not in Mojang's mappings; they were written from the Forge
   1.16.5 API and are the second most likely source of errors.
3. `ForgeConfigSpec.Builder.comment(...)` being called twice in a row on one builder in `Config`
   (it returns the builder, so chaining is fine, but if a particular 36.2.x signature differs this
   is where it will show).
4. `Type.TIMES` being sent with a `null` text component in `ScreenShakeEffect`.

None of these are architectural. Each is a one-line fix once a compiler can speak.

---

## What this mod will not do

These are the project's hard rules. They are enforced in code, not just documented:

| Rule | How it is enforced |
| --- | --- |
| Never modify another player's inventory, position, health, gamemode or session | No effect class can reach those APIs. There is no `setPos`, no `addItem`, no `setHealth`, no `setGameMode`, no container packet anywhere in this mod. Effects can only send `Clientbound*` packets to one connection. |
| No packets that move or control another player's client camera or movement | No `ClientboundPlayerPositionPacket`, no `ClientboundMoveEntityPacket`, no `ClientboundSetCameraPacket`. `screen-shake` is the closest thing, and it is explicitly *not* camera control — see below. |
| No unrestricted impersonation | `fake-chat` and `fake-login` are the only effects that put words in front of other players. Both are gated: `fake-chat` only ever sends lines from your own config, and records the exact text in the audit log. |
| Never change a player's name, skin or tab-list entry to look like someone else | No `ClientboundPlayerInfoPacket` with `UPDATE_DISPLAY_NAME` or `ADD_PLAYER` is ever sent. The target's real profile is never touched. |
| No block may ever actually be destroyed | Three independent rails — see below. |

**On screen shake.** Actually tilting or spinning a player's view means sending look/movement
packets the client will obey. That is the line where a prank becomes taking control of somebody's
client, and this mod does not cross it. Screen shake is delivered as a run of empty offset
*titles*, which the target's own renderer draws. The target keeps full control of their camera at
every moment.

**On fake chat and fake login.** These two effects tell other players something untrue. That is
what makes them pranks, and it is also why they are the only effects that are individually
recorded in the audit log with the exact text shown. If somebody asks "did the server make it look
like I said that?", `/prankcraft audit` answers with a timestamp, an actor and the line itself.
Keep the configured lines silly. A prank line should make people laugh at the sender, never at a
person who is not in the room.

---

## Building

### Requirements

* **JDK 8–17** to run Gradle. ForgeGradle 5 needs Gradle 7.x, and Gradle 7.x cannot run on a JDK
  newer than 19. JDK 17 is the recommended choice.
* Network access for the first build (Forge/Mojang/Maven Central artifacts).

### Commands

The Gradle wrapper scripts are **not** committed: `gradle/wrapper/gradle-wrapper.jar` could not be
downloaded (see below), so shipping `gradlew`/`gradlew.bat` would have produced a broken build.
Generate them once on a machine with network access, then use the wrapper as normal:

```bat
:: one-time, with any Gradle 7.x on PATH
gradle wrapper --gradle-version 7.6.4 --distribution-type bin

:: thereafter
gradlew.bat build
```

If you would rather not regenerate the wrapper, build with Gradle 7.6.4 directly:

```bat
gradle build
```

### Why the wrapper scripts are missing

`gradle/wrapper/gradle-wrapper.jar` is fetched from `raw.githubusercontent.com`, which is
unreachable from the environment this module was written in — connections time out, they do not
404. The `gradle/wrapper/gradle-wrapper.properties` file **is** present and correct
(`distributionUrl` points at Gradle 7.6.4), so regenerating the wrapper is a one-command job.

The only artifact to ship is **`build/libs/prankcraft-forge-1.16.5-1.0.0.jar`**. `gradlew build`
runs ForgeGradle's `reobfJar` through the normal assemble lifecycle, so that jar has production
(SRG) names and loads on a normal Forge server.

> The other jars Gradle leaves in `build/libs/` (the `-dev` / `-sources` variants) and everything
> under `build/classes/` are **development-only**. Do not copy them to a server.

### Target toolchain

| Component | Version |
| --- | --- |
| Minecraft | 1.16.5 |
| Forge | 1.16.5-36.2.34 |
| ForgeGradle | 5.1.15, overridable with `-Pforgegradle.version=5.1.77` |
| Gradle | 7.6.4 (wrapper) |
| Mappings | `official` channel, `1.16.5` (Mojang mappings) |
| Java source/target | 8 (`--release 8`, so Java-8 API usage is enforced at compile time) |

Gradle 7.6.4 is the version this build was written against and is what the wrapper requests.
ForgeGradle 5.1.15 does **not** work on Gradle 7.6.4 (it needs Gradle 7.0–7.4); if you use
5.1.15, either run Gradle 7.4.2 or build with `-Pforgegradle.version=5.1.77`, which is the version
that is compatible with Gradle 7.6.4.

### If Gradle cannot start: `Could not initialize native services`

Gradle extracts a small native helper into `GRADLE_USER_HOME/native/` on first run. On a machine
where that directory is not writable, Gradle fails before reading any build script with:

```
Could not initialize native services.
> Failed to load native library 'native-platform.dll' for Windows ... amd64.
```

Point `GRADLE_USER_HOME` at a writable directory and it resolves itself:

```bat
set GRADLE_USER_HOME=<writable-path>
gradle build
```

(On Windows, Gradle's JDK auto-provisioning is a second trap: it downloads Adoptium builds from
GitHub. If GitHub is unreachable, set
`org.gradle.java.installations.auto-download=false` in `GRADLE_USER_HOME/gradle.properties` and
either let Gradle detect a local JDK 8 or list one with
`org.gradle.java.installations.paths=<path-to-jdk8>`.)

---

## Installing

1. Install Forge 1.16.5-36.2.34 on your server.
2. Drop `prankcraft-forge-1.16.5-1.0.0.jar` into the server's `mods/` folder.
3. Start the server once. Forge writes `config/prankcraft-server.toml`.
4. Optionally edit that file and run `/reload`.

**Do not install this on a client.** It is server-side only, and it declares
`IGNORESERVERONLY`, so a server will not refuse a connection from a client that happens to have it.

---

## Where files live

| File | Path | Purpose |
| --- | --- | --- |
| Config | `<game>/config/prankcraft-server.toml` | Every knob, written and validated by Forge |
| Consent records | `<game>/config/prankcraft/consent.json` | Who opted in, who is on the never-prank list |
| Audit log | `<game>/config/prankcraft/audit-YYYY-MM-DD.log` | One JSON object per fired effect |

On a dedicated server `<game>` is the server directory, so both data files sit next to the config
and inside whatever you back up.

---

## Safety rails for fake TNT

`fake-tnt` is the one effect that spawns real entities in the world, so it gets three independent
rails. Any one of them alone would be enough. All three are present because "fake TNT that turns
out to be real TNT" is exactly the bug that ends a friendship.

1. **The fuse is pinned.** Every display `PrimedTnt` is created with
   `setFuse(Integer.MAX_VALUE)`, re-pinned on every session tick (`FakeTntManager#pinFuses`) and
   re-pinned again by an independent periodic sweep (`FakeTntManager#guardFuses`, every
   `prank-tnt.guard-period-ticks`). Vanilla's fuse comparison can never reach it.
2. **The explosion is cancelled.** `PrankSafetyListener` reacts to `ExplosionEvent.Start` and
   `ExplosionEvent.Detonate` and drops every entity this mod owns out of any explosion. On 1.16.5
   Forge does not reliably expose an explosion's source entity, so the listener is deliberately
   conservative: if a display entity is anywhere near an explosion, the block list is cleared and a
   loud warning is written to the server log.
3. **Blocks are packets only.** The target's client is told "there is TNT here" with a
   `ClientboundBlockUpdatePacket`. Chunk data, block entities and the real world are never touched.
   When the trick ends, the real block state is sent back.

A fourth, structural rail runs every tick (`PrankSafetyListener#onServerTick`): **no owned display
entity may exist without a live session pinning its fuse.** An orphaned display entity — whatever
the reason — is removed on the spot. This is what turns "no block may ever be destroyed" from a
hope into a property.

## Consent and the audit log

Consent is the difference between a prank and harassment, so it is on by default and has two gates:

* `require-consent` — the target must have opted in at all;
* `require-per-target-consent` — and must have allowed *that specific* prankster.

Both are checked by `PrankEngine` at the moment the effect fires, not when the command is typed, so
a target can revoke mid-event.

A Forge server has no permission system, so the Paper module's `prankcraft.consent.bypass`
permission node is replaced by an explicit **never-prank list** (`/prank deny *`). That list is
checked *before* every other gate and **cannot be overridden by an operator**, not even with
`force`. It is the one promise this mod makes that no command can break.

An opt-in does not survive a logout: consent that has to be renewed is consent; consent that
silently persists for months is a footgun. Explicit allow-list entries and the never-prank list do
persist.

Every fired effect is written to `audit-YYYY-MM-DD.log`, one JSON object per line, and mirrored to
the console. On a Forge server, where there are no permission nodes, this file is the only record
of what staff did — treat it as load-bearing.

---

## Commands

Both commands require **vanilla operator level 2** (the same bar `/kick` and `/gamemode` use) for
anything that fires an effect. The consent half of `/prank` is open to everyone on purpose: a
player must always be able to say no without asking staff for permission to do it.

### `/prank` — player-facing

| Command | Who | What |
| --- | --- | --- |
| `/prank list` | everyone | Show the available effects |
| `/prank allow <player\|*>` | everyone | Let that player (or everyone) prank you |
| `/prank deny [player\|*]` | everyone | Opt out. `*` means *never again* and is permanent |
| `/prank status` | everyone | See your current settings |
| `/prank stop` | everyone | Clear an illusion you are stuck in |
| `/prank yes` / `/prank no` | everyone | Answer pending requests |
| `/prank <effect> <player>` | op level 2 | Fire an effect at somebody who agreed |
| `/prank random <player>` | op level 2 | Surprise them with any enabled effect |
| `/prank reload` | op level 2 | Re-read the config and the consent store |

### `/prankcraft` — operator

| Command | What |
| --- | --- |
| `/prankcraft status` | Version, gates, counters, paths |
| `/prankcraft tnt show <player>` | Show fake TNT without detonating it |
| `/prankcraft tnt fire <player>` | Detonate the fake TNT that is already shown |
| `/prankcraft tnt clear <player\|*>` | Drop a running fake-TNT trick |
| `/prankcraft clear <player\|*>` | Drop every running illusion |
| `/prankcraft audit [count]` | The most recent fired effects |
| `/prankcraft consent <player>` | One player's consent record |
| `/prankcraft reload` | Re-read the config and the consent store |

---

## Effects ported

| Effect id | Ported | Notes |
| --- | --- | --- |
| `jumpscare` | yes | Particle burst + scream for nearby players |
| `fake-tnt` | yes | The full three-rail fake-TNT world |
| `phantom-footsteps` | yes | Periodic step sounds + smoke around the target |
| `screen-shake` | yes | Offset empty titles, one player only |
| `fake-chat` | yes | Configured line, everyone except the target |
| `fake-login` | yes | Leave line when online, join line when offline |
| `fake-weather` | yes | Rain/thunder/clear via `ClientboundGameEventPacket`, one player |

### Not ported, and why

| Paper effect | Why it is not here |
| --- | --- |
| `fake-death` | The convincing part of the Paper version is a red damage tint plus a fake death message. The tint needs a health/damage packet aimed at the target's own HUD, and the Paper implementation leaned on Bukkit's `Player#sendTitle`/damage-event plumbing that has no clean server-side equivalent here. Dropped rather than shipped in a form that would either do nothing or lie about the target's health. |
| `arrow-rain` | Needs real arrow entities the mod then has to keep harmless across their whole lifetime. The safety rails exist, but the effect is decoration with a real projectile's failure modes, so it was left out of this port. |
| `hotbar-shuffle` | Cosmetic-only in the Paper version too, but its whole illusion is a client inventory/container packet. Sending container packets is one step away from the "never modify another player's inventory" rule, so it is excluded on principle. |
| `wrong-block` | Straightforward to port, but it is a large fake-block diff (the same mechanism as `fake-tnt`'s rail three) with its own revert bookkeeping. Given the port's focus on the headline trick, it was not included. |

The Paper module also registers a `prankconsent` command for staff to inspect and override consent
records. Here that lives at `/prankcraft consent <player>`, and there is deliberately no override:
see the never-prank list above.

---

## Notes for a future maintainer

### Mapping-sensitive names

This module uses **official (Mojang) mappings**, as required. That makes some names very different
from the MCP/Yarn names you may find in old Forge 1.16.5 tutorials. The ones you will hit first:

| What you may have seen (MCP) | Official mappings (used here) |
| --- | --- |
| `ServerPlayerEntity` | `ServerPlayer` |
| `World` (server world) | `ServerLevel` |
| `EntityPlayerMP` / `PlayerEntity` | `ServerPlayer` / `Player` |
| `NetworkManager` / `PlayerConnection` | `ServerGamePacketListenerImpl`, reached through the public `ServerPlayer.connection` field |
| `SPacketTitle` | `ClientboundSetTitlesPacket` (nested `Type` enum) |
| `SPacketChangeGameState` / `SChangeGameStatePacket` | `ClientboundGameEventPacket` (nested `Type` enum) |
| `SPacketBlockChange` | `ClientboundBlockUpdatePacket` |
| `SPacketChat` | `ClientboundChatPacket` |
| `SPacketSoundEffect` | `ClientboundSoundPacket` |
| `SPacketParticles` | `ClientboundLevelParticlesPacket` |
| `SPacketPlayerListHeaderFooter` etc. | `ClientboundPlayerInfoPacket` (nested `Action` enum) |
| `EntityTNTPrimed` | `PrimedTnt` |
| `TextComponentString` | `TextComponent` |
| `BlockPos.offset` | `BlockPos.offset` (same) |

### Version-specific facts this port depends on

* `ClientboundGameEventPacket(Type, **float**)` — the data value is a **float** in 1.16.5. It became
  an `int` in 1.20.2+. The weather types are `START_RAINING`, `STOP_RAINING`,
  `RAIN_LEVEL_CHANGE` and `THUNDER_LEVEL_CHANGE` (the client reads each as a comparison against
  zero, so `1.0F` is "on" and `0.0F` is "off"). There is no `START_THUNDERING` constant in 1.16.5;
  using one is the most likely way to get fake weather wrong on this version.
* `PrimedTnt.setFuse(int)` — **int** in 1.16.5, **short** from 1.17 onwards. `FakeTntManager`
  isolates the value in the `PINNED_FUSE` constant with a comment saying exactly this; a forward
  port must change it to `Short.MAX_VALUE` or the fuse silently truncates and the TNT explodes.
* `ClientboundLevelEventPacket(int type, BlockPos pos, int data, boolean global)` — note the
  trailing `boolean`, which is easy to miss.
* `ClientboundSetTitlesPacket.Type` is named `TITLE`, **not** `SET_TITLE`.
* `Explosion#getSourceMob()` returns a `LivingEntity` and is null for a primed TNT with no owner.
  Do not build a safety rail on it — see the `PrankSafetyListener` class comment.
* `Player#hasPermissions(int)` is the vanilla operator-level check. There is no permission system on
  a Forge server; level 2 is the bar this mod uses.

### Design rules to keep

1. **Never add a method to `PrankEffect` that can reach another player's state.** The interface is
   the safety model. If an effect needs something new, add a packet, not an API.
2. **Keep the one gate.** Every effect goes through `PrankEngine#apply`. Effects re-check nothing.
   A prank that only sometimes checks consent is worse than no prank.
3. **Keep the never-prank list unoverridable.** If a future change makes `force` able to skip it,
   the mod's central promise is gone.
4. **Comment the *why* of a safety rail, not the *what*.** Every rail in this codebase exists
   because of a specific failure it prevents. Say which one.
