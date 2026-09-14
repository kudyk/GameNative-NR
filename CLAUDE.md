# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

GameNative is an Android app (Kotlin, Jetpack Compose) that runs Windows PC games from Steam, Epic, GOG and Amazon on Android via a bundled Wine/Box86-64/FEX translation layer (a Winlator-derived engine, package `com.winlator.*`, alongside the app's own code in `app.gamenative.*`). This fork is `GameNative-NR` (`nr/general-fixes-branch`), a set of general fixes on top of upstream GameNative.

## Build & test

This is a standard Gradle Android project — build/run/debug through Android Studio for anything interactive. From the CLI:

```
./gradlew :app:assembleLegacyDebug          # build the default dev variant (arm64-v8a + armeabi-v7a, minSdk 26)
./gradlew :app:testLegacyDebugUnitTest      # unit tests, legacy flavor (what CI runs)
./gradlew :app:testModernDebugUnitTest      # unit tests, modern flavor (minSdk 29, arm64-v8a only)
./gradlew :app:testLegacyDebugUnitTest --tests "com.winlator.core.WineUtilsTest"   # single test class
./gradlew :app:lintLegacyDebug              # Android Lint
./gradlew :app:formatKotlin                 # kotlinter format (kotlinter fails the build on lint errors, ignoreFormatFailures=false)
```

Unit tests live under `app/src/test`; there's also `app/src/androidTest` (instrumented) and `app/src/sharedTest`. CI (`.github/workflows/pluvia-pr-check.yml`) only runs `testLegacyDebugUnitTest` and `testModernDebugUnitTest` — always make sure both pass, since flavor-specific source sets (`src/legacy`, `src/modern`, `src/nonXr`) can make behavior diverge between them.

**Local setup:** `local.properties` needs `sdk.dir`. Optional secrets (`POSTHOG_API_KEY`, `POSTHOG_HOST`, `STEAMGRIDDB_API_KEY`) can also go there or as env vars — the app builds fine without them (analytics/artwork just no-op). Debug builds package the repo's root `manifest.json` (compatibility/config data) into assets automatically via the `copyDebugManifest` Gradle task; never rely on that file being present in release builds.

## Product flavors and build types

Flavor dimension `androidApi` has four flavors, each a different target device/API combo:
- `legacy` — minSdk 26, targetSdk 28, arm64-v8a + armeabi-v7a
- `legacyXr` — same as legacy, plus immersive VR bits (`XR_BUILD=true`), landscape-locked manifest
- `modern` — minSdk 29, targetSdk 36, arm64-v8a only
- `modernXr` — same as modern, plus VR (`XR_BUILD=true`, `MODERN_XR=true`) and Meta Horizon SDK deps

`legacy`/`legacyXr`/`modern` share code via `src/nonXr/java`; `modernXr` has its own `src/modernXr/java`. Non-XR-specific shared code lives in `src/main`; flavor-specific assets/jniLibs live in `src/legacy`, `src/modern`, `src/legacyXr`, `src/modernXr`.

Build types: `debug` (applicationIdSuffix `.nr`, labeled "GameNative-NR", debug-signed), `release` and `release-signed` (minified/shrunk, need `app/keystores/keystore.properties` for real signing — falls back to debug signing if absent), and `release-gold` (separate app ID suffix `.gold`, `GOLD=true`, alternate icon — a distinct storefront build, not just a signing variant).

The `buildModernXrNative`/`buildWindowsXrRuntime`/`stageWineXrBridge`/`stageOpenComposite`/`verifyModernXrPayload`/`prepareModernXrPayload` Gradle tasks only run on Windows hosts (they shell out to PowerShell scripts in `tools/`) and are for preparing the modernXr native/Wine payload — they're inert (`enabled = false`) elsewhere, including on this machine.

There's also a dynamic feature module `:ubuntufs` (on-demand delivery) declared in `settings.gradle.kts` and wired via `dynamicFeatures` — this is why `jniLibs { useLegacyPackaging = true }` is set (needed to keep native libs from going missing under on-demand delivery).

Two git submodules exist under `app/src/main/cpp/`: `extras/adrenotools` and `lsfg-vk-android`. Run `git submodule update --init --recursive` if code under those paths appears missing.

## Architecture

**DI / layering:** Hilt (`@HiltAndroidApp` on `PluviaApp`, modules in `app/gamenative/di/`) wires dependencies. UI follows a Compose + ViewModel pattern (`app/gamenative/ui/model/*ViewModel.kt`, one per major screen: Library, Home, Downloads, XServer, UserLogin, etc.) driving Composable screens under `app/gamenative/ui/screen/`. Persistence is Room (`app/gamenative/db/`, DAOs in `db/dao`, migrations in `db/migration`); schema JSON snapshots are checked in under `app/schemas/`.

**Storefront integrations:** Each supported store (Steam, Epic, GOG, Amazon) has its own service package under `app/gamenative/service/{epic,gog,amazon}` plus shared orchestration in `service/SteamService.kt`, `service/DownloadService.kt`, `service/handler/`, `service/callback/`. Steam networking goes through the external JavaSteam library (see the `localBuild` toggle in `app/build.gradle.kts` for pointing at a local JavaSteam checkout instead of the published artifact). Game-specific tweaks and known-working configs are under `app/gamenative/gamefixes/` and applied automatically based on the compatibility data in root `manifest.json` (see also `recommendations.json`, `keyvalues/`, and https://gamenative.app/compatibility).

**The Windows compatibility layer** lives in `com.winlator.*` under `app/src/main/java/com/winlator/`: `container/` (the per-game Wine "container"/prefix abstraction), `core/` (Wine/Box86-64/FEX orchestration, env vars), `xserver/` (an embedded X server implementation — `xserver/extensions`, `xserver/requests`, `xserver/events`), `xenvironment/` (assembling the runtime environment components), `renderer/` (GL/graphics), `inputcontrols/` + `widget/` (on-screen touch controls, HUD, control editor), `winhandler/`, `alsaserver/`, `steampipeserver/`. This is a large, mostly-independent subsystem — read the relevant `com.winlator` package before changing container/Wine/input behavior rather than assuming it mirrors `app.gamenative` conventions.

**Power/thermal management** is its own subsystem: `app/gamenative/powercontrol/` (profiles, autotuning, fan control, per-vendor drivers, metrics collection) — relevant for handheld/thermal-throttling behavior.

**Sync/events:** `app/gamenative/sync/` (frontend sync manager, cloud saves), `app/gamenative/events/` (an `EventDispatcher` used for cross-component signaling, referenced from `PluviaApp`).

**Mods:** `app/gamenative/mods/` handles Nexus Mods integration (auth, import) — see `service/NexusModImportService.kt`.

## Notable project conventions

- Recent commit history on this branch focuses on input remapping/throttling correctness (analog stick → WASD remapping, only sending updated input state when it actually changed, configurable throttling, and logging that can be fully disabled for performance) — when touching `com.winlator.inputcontrols` or input-handling code, check recent commits on `nr/general-fixes-branch` for the current approach before changing behavior.
- Contributions are expected to be discussed on the project Discord (`#code-changes`/`#development`) before a PR is opened, and scope is deliberately restricted to core functionality, stability, and compatibility — new features/UI changes/cosmetic changes are out of scope unless pre-approved (see `CONTRIBUTING.md`). This applies to upstream GameNative; adjust expectations for this fork as directed by the user.
- GitHub Issues are auto-closed; user-facing support/bug reports go through Discord, not this repo.
