# Multi-version support — what is verified, what is not

This file records the state of the version plan. It exists so that "supports 1.16.5" is a claim
with evidence attached, not an intention.

Order of work: **1 → NeoForge**, **2 → 1.16.5 Spigot**, **3 → ViaVersion**.

---

## The finding that shapes everything

Class files are versioned, and a server refuses to load a plugin built for a newer Java than it
runs. Demonstrated by loading the modern jar on a real Paper 1.16.5 server (Java 8):

```
org.bukkit.plugin.InvalidPluginException:
  java.lang.UnsupportedClassVersionError: com/prankcraft/PrankCraftPlugin has been compiled by a
  more recent version of the Java Runtime (class file version 61.0), this version of the Java
  Runtime only recognizes class file versions up to 52.0
```

So **one jar cannot cover the range**, regardless of how careful the source is. Each Java level
needs its own artefact. That is why the repository builds more than one jar.

| Artefact | Compiled for | Serves |
| --- | --- | --- |
| `paper/target/PrankCraft-1.0.0.jar` | Java 17 | 1.18+ servers (Java 17+) |
| `legacy-1165/target/PrankCraft-classic-1.0.0.jar` | Java 8 | 1.16.5 – 1.17.x (Java 8–16) |

Build both with:

```bash
mvn clean install                                  # modern
mvn -Plegacy-check -pl legacy-1165 package         # classic
```

---

## Step 2 — 1.16.5: DONE, verified on a real server

Tested with **Paper 1.16.5 build 794** running on **JDK 8** (1.16.5 refuses JDK 17:
`Unsupported Java detected (61.0). Only up to Java 16 is supported.`).

Verified by direct observation on the running server:

* the classic jar **loads and enables**: `PrankCraft enabled - 11 prank effect(s) loaded`
* **11 effects registered**, `config.yml` generated, no exceptions, clean shutdown
* `/prank list` prints the **full effect table** for all 11 effects
* `/help prank` resolves: `Usage: /prank help`, `Aliases: pranks`
* `/prankcraft status`, `/prankconsent info`, `/prankcraft audit`, `/prankcraft reload` all respond

Source changes this required (all in `Compat`, mirroring what `Fx` already did for particles):

| Member | Missing or renamed | Handled by |
| --- | --- | --- |
| `Player#sendActionBar(String)` | absent on 1.16.5 | reflection, skipped when absent |
| `HumanEntity#openAnvil(Location, boolean)` | named `openWorkbench` on 1.16.5 | try `openAnvil`, fall back |
| `Bukkit#getOfflinePlayerIfCached(String)` | Paper-only | reflective lookup, then plain `getOfflinePlayer` |
| `Player#sendBlockChange(Location, BlockData)` | **present** on 1.16.5 | no adapter needed — the fake TNT works as-is |

Honest gap: the automated smoke assertions for 1.16.5 are not green, because on this Paper build a
command written to a **redirected stdin** is acknowledged by the process but answered with
`Unknown command` by the console dispatcher (verified: the identical command typed with stdin
attached works, and a minimal probe plugin shows `getCommand("prank")` resolving correctly). This
is a test-harness transport problem, not a plugin problem — which is why the list above was
established by reading the server's own log after driving it directly. 1.16.5 is not yet wired
into CI.

---

## Step 3 — ViaVersion: coexist verified, client translation NOT verified

Done:

* **ViaVersion 5.12.0** and PrankCraft **load and enable together** on Paper 1.21.11
* ViaVersion reports `detected server version: 1.21.11 (774)` and completes its mapping load
* no conflict, no exception, both disable cleanly

**Not** done: no client older than 1.21.11 was actually connected. The claim "a 1.12 client sees
the fake TNT through ViaVersion" is therefore **untested**. Testing it properly needs either a real
legacy client or a bot that can speak the old protocol; the mineflayer build available here is
adapted for 1.21.11.

What is known and relevant: the effects are plain vanilla packets (`sendBlockChange`, particles,
sounds, titles, and `ClientboundGameEventPacket` for weather). Those are exactly the packets
ViaVersion translates, and clients have rendered fake blocks server-side since before 1.12 — but
"should translate" is not "was observed to translate".

---

## Step 1 — NeoForge: NOT started, blocked by the environment

NeoForge build dependencies resolve from `maven.neoforged.net`, and its Gradle distributions from
`services.gradle.org` / `github.com`. During this session those hosts were unreachable or resetting
connections for extended periods; `gradle-8.10.2-bin.zip` and a JDK 21 download both failed
mid-transfer. Two further constraints:

* NeoForge requires **Gradle 8.x+ and JDK 17+**; the only JDK on PATH is 26, and the cached
  `jdk17`/`jdk8` directories were empty shell folders (later restored from `build-cache\tools`
  archives).
* NeoForge 1.20.2 wants **JDK 17**, 1.21.1 wants **JDK 21** — each target needs its own toolchain.

Version facts gathered before the network failed (all verified against the upstream repository):

| Fact | Value |
| --- | --- |
| NeoForge 1.21.1 line, newest | `21.1.256` |
| NeoForge 1.20.2 line, newest | `20.2.93` |
| NeoForge year-based versions | `21.1.256` = MC 1.21.1; `26.3.0.x` is a newer line |
| NeoForge exists from | MC **1.20.2**; 1.20.1 and earlier are Forge only |

Next time the network is steady, the sequence is: download Gradle 8.10.2 + JDK 21, write a
Gradle project using ModDevGradle with `neoforge 21.1.256`, port the seven server-side effects
(fake TNT, jumpscare, screen shake, fake weather, fake chat, fake login, phantom footsteps), and
expect the Forge module's AccessTransformers complication to recur — see
`../forge-mod/BUILD-STATUS.md` for that root cause.
