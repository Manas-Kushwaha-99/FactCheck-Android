# FactCheck (Android) v2.6

Floating, non-intrusive, on-screen fact checker powered by **Gemini Flash-Lite** or **Groq** (`qwen/qwen3.8-27b`).

Tap the slim side-handle glued to the right edge of any screen → the app takes
**one screenshot using the accessibility screen-capture capability**, sends it
to the Gemini API with your own key, and shows the verdicts in a small floating
panel that doesn't block your touch anywhere else.

## How screen access works (one-shot, no recording)

- The floating handle and result panel are drawn as `TYPE_ACCESSIBILITY_OVERLAY`
  windows by the accessibility service — this needs **no `draw over apps`**
  permission.
- Each tap of the handle calls `AccessibilityService.takeScreenshot()` **once**.
  No `MediaProjection`, no virtual display, no background recording.
- The app's own windows are hidden ~180 ms around the capture so they never end
  up in the screenshot.
- The service declares only `canTakeScreenshot`; it does **not** read window
  content, key events, or notifications.

## Fields the permissions boil down to

1. **Accessibility service** (Settings → Accessibility → *FactCheck — one-shot
   screen check*) → brings the side handle to life and grants the screenshot
   capability.
2. Nothing else. No overlay-permission, no notification permission, no
   screen-recording consent dialog, no persistent status-bar notification.

## What the app does

1. **Hidden sidebar trigger** — a translucent pill on the right edge (draggable).
   - **Tap** → one-shot fact-check.
   - **Long-press** → open the app.
2. **Provider** — pick **Gemini** or **Groq** in the app (top card); each has
   its own encrypted key + model.
   - **Gemini** — default model **`gemini-3.5-flash-lite`**. If that id is ever
     retired/renamed, the app re-resolves the newest *Flash-Lite* model from
     `ListModels` and saves it automatically.
     - Fast path: native **Google Search tool** grounding (1 request).
     - Fallback: free pipeline — model extracts claims+queries → the app
       fetches DuckDuckGo itself → model verifies against the snippets.
       Memory-only answers are always warned in the panel.
   - **Groq** — default model **`qwen/qwen3.8-27b`** (~450+ tokens/sec,
     multimodal: reads the screenshot). Groq has no built-in web search, so
     FactCheck always runs its own DuckDuckGo pipeline:
     claims+queries → DuckDuckGo → verify against snippets.
     - Panel shows `Searched:` queries and `Sources:` domains so you can
       always tell the verdict was anchored on the live web.
3. **Floating panel** — compact card (`FLAG_NOT_TOUCH_MODAL` — touches outside
   pass through), auto-capped height, verdict chips colored (✓ green / ✕ red /
   ⚠ amber / ? grey), Copy / Re-check / Open-app buttons. Hint messages
   auto-dismiss after 7 s; errors and results stay until closed.
4. **Rate limit aware** — accessibility screenshots have a minimum interval;
   a too-quick re-tap shows a friendly "wait a second" message instead of
   failing mysteriously.

## Security

- Your API keys are stored on device in **Android Keystore-encrypted** storage
  (AES-256/GCM). They are not hardcoded and leave nothing plaintext behind.
- HTTPS only: Gemini = `generativelanguage.googleapis.com`, Groq =
  `api.groq.com`, plus DuckDuckGo search pages fetched by the app itself.
- Screenshots are never stored: captured → compressed → sent → recycled.

## Build

```powershell
cd android-app
.\build-apk.ps1                    # signed release APK
.\build-apk.ps1 -Task assembleDebug
.\build-apk.ps1 -Install           # build + adb install -r on a connected phone
```

Direct Gradle: `.\gradlew.bat assembleRelease` →
`app\build\outputs\apk\release\app-release.apk`

## First run (on the phone)

1. `adb install -r android-app\app\build\outputs\apk\release\app-release.apk`
2. Open **FactCheck**:
   - Paste your **Gemini API key** → *Save* → *Test key* (validates the key by
     listing models, saves the best Flash-Lite model id).
   - And/or paste your **Groq API key** → *Save* → *Test key*.
   - Pick the active provider in the **AI provider** card (default Gemini).
3. Tap *Enable accessibility service* → Settings → Accessibility →
   **FactCheck — one-shot screen check** → toggle ON → back.
   (Requires Android 11+ — the app itself is compiled for API 30+ minimum.)
4. The side handle appears at the right edge. Open any screen with content
   worth checking and **tap the handle**.

## If something does not work

- Handle doesn't appear → the accessibility service isn't enabled; check
  Settings → Accessibility.
- *"Screenshot permission missing"* → re-enable the service; some OEMs drop
  accessibility after updates.
- *"Screenshots are rate-limited"* → you tapped twice too fast; wait a beat.
- *Key problem / 401/403* → paste a valid key from Google AI Studio.
- *404 model* → the app auto-switches to the newest Flash-Lite; check the
  Model row in the app afterwards.

## Toolchain (already installed on this machine)

| Tool | Version |
|---|---|
| Android SDK cmdline-tools / platform-tools | 16111833 / 37.0.1 |
| platforms | android-36 |
| build-tools | 36.0.0 |
| Gradle | 8.14.3 (wrapper) |
| AGP / Kotlin | 8.13.2 / 2.2.20 |
| Release signing | `keystore/factcheck-release.jks` (alias `factcheck`, validity 10 y) |

Note: with `minSdk = 30` the release APK carries its `classes.dex` uncompressed
(larger download, faster install/startup). Enabling R8 minification later would
shrink the download if you want.
