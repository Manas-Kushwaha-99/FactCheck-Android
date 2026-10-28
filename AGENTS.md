# AGENTS.md — FactCheck (Android)

Single-module Kotlin Android app (`:app`, package `com.local.factcheck`): a floating
one-shot screenshot fact-checker. Tap a side handle → `AccessibilityService.takeScreenshot()`
→ Gemini or Groq verifies claims → floating result panel.
Deep docs: `README.md` (authoritative) and `FACTCHECK_REVERSE_PROMPT.md` (reverse-engineered
architecture; accurate but treat source code as truth).

## Environment & build (Windows, PowerShell)

- Use `gradlew.bat` (not `./gradlew`) and `build-apk.ps1`. There is no git repo, no CI,
  no pre-commit, and **no lint/format/test setup at all** — the only verification is a
  Gradle build.
- Primary commands:
  - `.\build-apk.ps1` — signed release APK (sets `ANDROID_HOME` to `%LOCALAPPDATA%\Android\Sdk` itself)
  - `.\build-apk.ps1 -Task assembleDebug` — debug build
  - `.\build-apk.ps1 -Install` — build + `adb install -r` on connected device
  - `.\build-apk.ps1 -Task bundleRelease` — AAB instead of APK
  - Direct: `.\gradlew.bat assembleRelease` / `assembleDebug` → `app\build\outputs\apk\...\app-release.apk`
- Do NOT try to run tests (`gradlew test` finds zero test sources: no `app/src/test`,
  no `app/src/androidTest`). "Does it compile" = `assembleDebug`.
- Toolchain (pinned in build files): AGP 8.13.2, Kotlin 2.2.20, Gradle 8.14.3 (wrapper),
  compileSdk/targetSdk 36, build-tools 36.0.0, Java/JVM 17. viewBinding + buildConfig enabled.
- Signing: release uses `keystore.properties` (gitignored, present on this machine).
  **If that file is ever missing, release silently falls back to debug signing** — check it
  if a "signed" build isn't signed. Keystore: `keystore/factcheck-release.jks` (also gitignored).
- `local.properties` (gitignored) holds the machine-specific `sdk.dir` and is required for builds.
- Stale artifacts to ignore: root `app-release-v2.4-backup.apk`, `.gradle/`, `.kotlin/`.

## Architecture — what filenames don't tell you

- Entrypoints: `app/src/main/java/com/local/factcheck/MainActivity.kt` (setup UI only) and
  `service/FactCheckAccessibilityService.kt` (the real "brain": overlay, screenshot, orchestration).
- Two provider clients: `gemini/GeminiClient.kt` (native Google-Search grounding fast path,
  with a free DuckDuckGo fallback pipeline and 404-triggered Flash-Lite model re-resolution)
  and `groq/GroqClient.kt` (OpenAI-compatible `chat/completions`; always runs the DuckDuckGo
  pipeline — no native grounding).
- The shared DTOs (`FactCheckResult`, `Claim`, `ClaimsPlan`, `GemSource`) live in
  `gemini/GeminiDtos.kt` but are **also used by the Groq pipeline** — add shared response
  shapes there, not in a new file. JSON is kotlinx.serialization: give every new DTO
  field a default value, matching the existing pattern (model output is decoded
  defensively and must not throw on missing fields).
- `util/WebSearch.kt` is the single DuckDuckGo scraper shared by both pipelines (regex HTML
  parsing, max 5 snippets). `util/Config.kt` is a global singleton over SharedPreferences
  (init with a Context before use); it also holds the default model ids
  (`gemini-3.5-flash-lite`, `qwen/qwen3.8-27b`). `util/SecureStore.kt` holds API keys as
  AES-256/GCM via Android Keystore with a flagged base64 fallback.
- `util/Overlay.kt` draws both always-on-top windows as `TYPE_ACCESSIBILITY_OVERLAY`
  (no "draw over apps" permission anywhere — never add one).

## Gotchas that will burn you

- **minSdk 30 is a hard floor**: `AccessibilityService.takeScreenshot()` requires Android 11+.
- **Overlay views must be inflated via a `ContextThemeWrapper` with `Theme.FactCheck`** —
  a bare Service context silently crashes Material widget inflation (see `Overlay.themedCtx`).
- Never hardcode API keys; they go through `Config`/`SecureStore` only. The app's own overlay
  windows are hidden ~180 ms (`SETTLE_MS`) before each screenshot so they don't appear in it.
- No R8/minification in any build type; `proguard-rules.pro` only keeps `@JavascriptInterface`.
- Accessibility screenshots are rate-limited by the OS; rapid re-taps are expected to fail
  with a friendly message — that's designed behavior, not a bug to "fix".
