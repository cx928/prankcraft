# dist

`PrankCraft-1.0.0.jar` — the built Paper plugin. Drop it into a server's `plugins/` directory
and restart.

This copy is committed so you can grab a working jar without a JDK. It was built with:

```bash
mvn clean install
```

and verified on Paper 1.21.11 build 132 — see the "验证状态 / Verification" section of the
[root README](../README.md) for the exact test output, including the live-client run that proves
the fake TNT deals no damage.

Rebuilding from source produces a jar that differs in bytes (build timestamps) but is
functionally identical. Prefer the source build if you are making changes.

**The Forge build in `../forge-mod/` is not compiled** — see `../forge-mod/BUILD-STATUS.md`.
