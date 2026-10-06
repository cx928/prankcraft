# PrankCraft legacy target — Spigot 1.12.2 on Java 8

> **Status: the source is Java 8 clean, but this target does not yet compile.** The remaining
> work is listed below with the exact compiler errors. Run it yourself with
> `mvn -Plegacy-check compile` from the repository root.

## What this module is for

It compiles the **same sources** as the modern Paper build (`../core/src/main/java`) against the
**oldest API we support**, with `--release 8`. Building one source tree against two very different
APIs is what actually proves the code is portable — a claim that is otherwise just an intention.

Because it shares the source, it is also the guard rail that keeps the legacy targets honest:

* a `record` in shared code fails here (Java 16 feature, Java 8 servers cannot load it);
* a `List.of(...)` fails here (Java 9+ standard library);
* a `String.strip()` fails here (Java 11+);
* `org.bukkit.block.data.*` fails here (does not exist before 1.13).

Verified working: **the shared source now compiles under `--release 8`** against the modern Paper
API. That refactor removed 2 records, 4 switch expressions, 11 pattern variables, 9 `List.of` /
`Set.of` / `List.copyOf` calls, one `String.strip()`, and one `Stream` in a status message. The
modern build was re-verified afterwards (21 unit tests, 14 smoke assertions, 23 live-client
assertions — all green).

## What still blocks the 1.12.2 target

These are real API gaps, not mechanical fixes. Each needs an adapter with a reflected fallback,
because the modern call simply does not exist on the old server.

| Missing on 1.12.2 | Used by | Notes |
| --- | --- | --- |
| `org.bukkit.block.data.*` (whole package, added in 1.13) | `Cfg`, `FakeTntManager`, `WrongBlockEffect`, `PrankSafetyListener` | `sendBlockChange(Location, BlockData)` must become `sendBlockChange(Location, Material, byte)` on 1.12.2 |
| `AbstractArrow` (added in 1.14) | `ArrowRainEffect`, `PrankSafetyListener` | on 1.12.2 the type is `Arrow`; `setPickupStatus`/`PickupStatus` does not exist either |
| `Player#sendActionBar` (added in 1.16) | `Text`, `HotbarShuffleEffect` | needs the old `ChatMessageType.ACTION_BAR` packet path |
| `HumanEntity#openAnvil` (added in 1.14) | `HotbarShuffleEffect` | the "fake window" trick needs a version check |
| `EntityPickupItemEvent` shape, `ChunkUnloadEvent#getChunk` | `PrankSafetyListener` | event signatures differ on 1.12.2 |

The clean shape for this is a small `LegacyAdapter` in `core` that the effects call instead of the
modern API directly, resolving the version once at startup. The `Fx` class already does exactly
this for particles and sounds — the block-state and action-bar paths are the ones still missing.

## Why is it not in the default build

An opt-in profile (`-Plegacy-check`) rather than a default module, so that work-in-progress on the
oldest target can never turn the normal build red. The parent `pom.xml` documents this too.

## Version facts this target depends on

* **Paper's API repository starts at 1.17.1.** Everything older must compile against Spigot —
  verified: `paper-api:1.16.5-R0.1-SNAPSHOT` returns HTTP 404, `1.17.1` returns 200.
* Spigot publishes `spigot-api:1.12.2-R0.1-SNAPSHOT` from `hub.spigotmc.org` — verified reachable.
* **NeoForge exists only from Minecraft 1.20.2 onwards** (`neoforged:neoforge:20.2.x`). 1.20.1 and
  earlier are Forge only. Verified against `maven.neoforged.net`.
