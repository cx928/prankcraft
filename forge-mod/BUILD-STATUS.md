# Build status — PrankCraft for Forge 1.16.5

**Result: NOT COMPILED.** The Forge toolchain could not be resolved in this build environment.
Run `gradlew build` on a machine with network access.

This file records exactly how far the build got and where it stopped, so the next person does not
have to repeat the investigation.

---

## Environment

| Fact | Value |
| --- | --- |
| OS | Windows 11 amd64 |
| JDK on `PATH` | 26 (cannot run Gradle 7.x at all — Gradle 7.x supports at most JDK 19) |
| JDK used for Gradle | Temurin 17.0.2 (downloaded during this investigation) |
| JDK used for the MCP tools | Zulu 8.0.412 (downloaded during this investigation) |
| Gradle | 7.6.4 and 7.4.2 (both downloaded during this investigation) |
| Network | working for Maven Central, maven.minecraftforge.net, piston-data.mojang.com, services.gradle.org, repo.huaweicloud.com. **`github.com` and `raw.githubusercontent.com` time out** (TCP connect timeout, not a 404). |

Everything ForgeGradle needs was reachable except GitHub. That turned out to matter twice.

---

## What was tried, in order

| # | Attempt | Outcome |
| --- | --- | --- |
| 1 | `gradle wrapper` / `gradle build` on the JDK 26 that is on `PATH` | Gradle 7.6.4 refuses to start on JDK 26. Fixed by fetching JDK 17. |
| 2 | Gradle 7.6.4 + JDK 17 | Gradle died with `Could not initialize native services` — it extracts a native helper into `GRADLE_USER_HOME/native/` and that path was not usable. Fixed by pointing `GRADLE_USER_HOME` at a writable directory inside the workspace. |
| 3 | ForgeGradle 5.1.+ (= 5.1.77) + Gradle 7.6.4 | Gradle started, ForgeGradle resolved, the MCP config and the Minecraft client/server jars downloaded, `merge` and `rename` ran. Then: `Could not find net.minecraftforge:forge:1.16.5-36.2.34_mapped_official_1.16.5`. |
| 4 | Same, with `--stacktrace` | The mapped artifact is produced lazily by ForgeGradle's Artifactural repository, and its provider threw while generating it. Cause visible only with `--info`. |
| 5 | ForgeGradle 5.1.15 + Gradle 7.6.4 | `AbstractArtifactRepository.<init>` `NoSuchMethodError` — 5.1.15 predates Gradle 7.5. Not a fix. |
| 6 | ForgeGradle 5.1.15 + Gradle 7.4.2 | Same mapped-artifact failure. So the problem is not the Gradle or ForgeGradle version. |
| 7 | Removed the `runs { }` block from `build.gradle` | Real progress: this removed a *second*, independent blocker (`RunConfigGenerator` resolves the runtime classpath during project configuration, with no task dependency on the pipeline that produces it). Build now reaches `:compileJava`. |
| 8 | Tried `mappings channel: 'snapshot'` instead of `official` | Identical failure. Not a licensing/mappings-channel effect. |
| 9 | Ran the AccessTransformers tool standalone, outside Gradle, on both JDK 17 and JDK 8 | **Reproduced.** See below. |

---

## The actual blocker

With `--info`, the real error is visible:

```
> Task :compileJava FAILED
Exception in thread "main" java.nio.file.ReadOnlyFileSystemException
        at jdk.zipfs/jdk.nio.zipfs.ZipFileSystem.checkWritable(ZipFileSystem.java:370)
        at jdk.zipfs/jdk.nio.zipfs.ZipFileSystem.createDirectory(ZipFileSystem.java:708)
        at jdk.zipfs/jdk.nio.zipfs.ZipPath.createDirectory(ZipPath.java:738)
        at jdk.zipfs/jdk.nio.zipfs.ZipPath.copyToTarget(ZipPath.java:965)
        at jdk.zipfs/jdk.nio.zipfs.ZipPath.copy(ZipPath.java:932)
        at jdk.zipfs/jdk.nio.zipfs.ZipFileSystemProvider.copy(ZipFileSystemProvider.java:179)
        at java.base/java.nio.file.Files.copy(Files.java:1305)
        at net.minecraftforge.accesstransformer.TransformerProcessor.lambda$processJar$3(TransformerProcessor.java:127)
Error getting artifact: net.minecraftforge:forge:1.16.5-36.2.34_mapped_snapshot_20210309-1.16.5:null@jar from MinecraftUserRepo
```

and the last line of the tool's own log explains the state it left behind:

```
[INFO]: Writing to ...\1.16.5-36.2.34_mapped_official_1.16.5\forge-...-_mapped_official_1.16.5.jar
[WARN]: Found existing output jar ... overwriting
Exception in thread "main" java.nio.file.ReadOnlyFileSystemException
```

`net.minecraftforge:accesstransformers:8.0.7`'s `TransformerProcessor.processJar` opens its output
jar as a zip filesystem and then `Files.copy`s entries into it. On this machine that copy always
fails with a read-only-filesystem error, leaving a 22-byte stub jar behind. It was reproduced
outside Gradle entirely, with a plain `java -cp accesstransformers-8.0.7-fatjar.jar ...` invocation,
on both JDK 8 and JDK 17, writing into a directory that was independently verified to be writable
(a direct `Set-Content` and a 14 MB `Copy-Item` into the same directory both succeeded).

So the failure is inside the AccessTransformers tool's zip handling, not in the workspace's
permissions, not in the Gradle or ForgeGradle version, and not in a missing download. Whether it is
a genuine bug in 8.0.7 or an interaction with this machine's filesystem could not be determined
without access to the tool's source (which is on GitHub).

**Why this blocks everything:** the AccessTransformers step is what produces the
`forge-..._mapped_official_1.16.5.jar` artifact, which is the only source of the official-mapped
Minecraft and Forge API. Without it there is no classpath to compile against, so `javac` is never
reached.

---

## What *was* accomplished

* All toolchain artifacts were fetched successfully: ForgeGradle 5.1.77 and 5.1.15, the 1.16.5
  MCPConfig, the vanilla client and server jars, Forge userdev/universal/sources, ForgeFlower,
  SpecialSource, MCInjector, and every dependency of the mapped Forge artifact.
* The MCP pipeline ran end to end through `downloadClient`, `downloadServer`, `merge`, `rename`
  and `applyBinpatches`, producing `forge-1.16.5-36.2.34-injected.jar` (15.0 MB) — the last
  artifact before the AccessTransformers step.
* The Gradle configuration blocker was removed from `build.gradle` (the `runs { }` block), which is
  a real fix that a machine with a working toolchain benefits from.
* Every Minecraft and Forge symbol used by the mod was verified by hand against Mojang's official
  1.16.5 mapping files, downloaded from `piston-data.mojang.com`. The results of that check are
  listed in `README.md` under "Verification performed", including the four places most likely to
  need a one-line fix on the first real build.

---

## What to do on a machine with network access

```bat
gradlew.bat build
:: or, if the wrapper jar is still the truncated download described in README.md:
gradle build
```

If the mapped artifact fails to generate there too, it is the same AccessTransformers bug and the
workaround is to use a Gradle/ForgeGradle pair whose dependency tree pulls a different
`accesstransformers` build — or to apply the access transformers by hand from
`config/access.txt` inside the MCPConfig zip. Neither was possible here.

---

## Addendum — exact root cause of the mapped-artifact failure

Recorded after a second investigation. The AccessTransformers failure is **not** a ForgeGradle bug
and **not** a permissions problem. It is a `zipfs` behaviour change between JDK releases.

`TransformerProcessor.processJar` creates its output jar and then writes entries **through a zip
filesystem**. On JDK 17, `ZipFileSystem.checkWritable` refuses to write into an archive that is
still empty, so the copy throws `ReadOnlyFileSystemException` and leaves the 22-byte stub seen
above. JDK 26 does not have this restriction.

Reproduce it in 30 lines — `tools/ZipFsWriteProbe.java` in this directory does exactly this:

```bat
:: JDK 17  -> RESULT: READ-ONLY (reproduces the failure)
<gradle-jdk>\bin\java tools\ZipFsWriteProbe.java some\dir

:: JDK 26  -> RESULT: SUCCESS on the same machine, same directory
java tools\ZipFsWriteProbe.java some\dir
```

**Workaround attempted here, and why it is not a fix:** pre-creating the target path as a
*structurally valid, non-empty* zip gets `checkWritable` past the empty-archive case, but it does
**not** unblock the build, for two reasons that were verified:

* If the seed is left in place, ForgeGradle's Artifactural repository treats the artifact as
  already generated and skips generation, so resolution then fails with
  `Could not find net.minecraftforge:forge:..._mapped_official_1.16.5` even though the file exists.
* If the seed is continuously re-created while the build runs (so the write always finds a valid
  archive), the generator's own output is deleted underneath it — the seed and the generator
  fight over the same file.

So there is no reliable local workaround. A JDK where the empty-archive case is writable (JDK 26
was verified to be) is what this needs, together with a Gradle/ForgeGradle pair that supports that
JDK — which 1.16.5's ForgeGradle line does not.

**Toolchain combination that gets furthest on this machine:**

| Gradle | ForgeGradle | Result |
| --- | --- | --- |
| 7.6.4 | 5.1.15 | fails during *configuration*: `AbstractArtifactRepository.<init>` NoSuchMethodError |
| 7.6.4 | 5.1.77 | same configuration failure (the `-Pforgegradle.version` override does not change the resolved buildscript classpath) |
| **7.4.2** | **5.1.15** | **gets all the way to `:compileJava`**, then fails resolving the mapped artifact |

That last row is the closest this environment got, and it is the combination to use on a machine
where the mapped artifact can be generated.

None of this affects the mod's source. It is a toolchain-version puzzle that has to be solved once,
on a machine that can run the combination, and then never again.
