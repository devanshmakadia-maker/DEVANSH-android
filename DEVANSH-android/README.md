# DEVANSH — Voice-Controlled Android Assistant (MVP)

This is a working source project implementing the architecture you described:

**Wake word → Speech-to-text → AI planning (Claude) → Accessibility Service actions →
Screen re-observation/verification → Text-to-speech response**

It is a **functional MVP**, not the full production system described in the original spec
(per-app Instagram/WhatsApp heuristics, a dedicated low-power hotword engine, OCR/vision
fallback, etc.). Those are real extensions you can build on top of this skeleton — the
architecture is designed to support them. Read "What's genuinely implemented" below before
you rely on this for anything sensitive.

## Why I can't hand you a finished `DEVANSH.apk` directly

Building, signing, and testing an Android APK requires the Android SDK, build tools, and
(for real testing) a device or emulator — none of which are available in the environment I
run in. What I *can* give you, and did:

1. The complete, compilable source project (this repo).
2. A GitHub Actions workflow (`.github/workflows/build-apk.yml`) that builds and signs a real
   `DEVANSH.apk` automatically using GitHub's own Android build environment — this is the closest
   thing to "push a button, get an APK" without a local Android Studio setup.

## Fastest path to an installed APK: GitHub Actions

1. Create a new GitHub repository and push this project to it.
2. Generate a signing key (one-time, needs a local JDK):
   ```
   keytool -genkeypair -v -keystore release.keystore -alias dev -keyalg RSA -keysize 2048 -validity 10000
   ```
3. In your repo's **Settings → Secrets and variables → Actions**, add:
   - `KEYSTORE_BASE64` — output of `base64 -w0 release.keystore`
   - `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`dev` if you used the command above), `KEY_PASSWORD`
4. Push to `main` (or run the workflow manually from the Actions tab).
5. Download the signed `DEVANSH.apk` from the workflow run's **Artifacts** section and install it
   (enable "Install unknown apps" for your file manager/browser first).

## Building locally in Android Studio instead

1. Open this folder in Android Studio (Giraffe or newer). It will auto-generate the Gradle
   wrapper (`gradlew`/`gradle-wrapper.jar`) on first sync — this repo intentionally omits the
   binary wrapper jar since I can't fetch it in my environment.
2. Build → Generate Signed Bundle/APK → APK, using a keystore you create in the wizard.
3. Install the resulting APK on your device (USB debugging, or copy the file over).

## First-time setup on your phone

1. Install `DEVANSH.apk`. If Android blocks it, enable "Install unknown apps" for the source
   (browser/file manager) you used, then retry.
2. Open DEVANSH. On the **DEVANSH** tab, grant microphone access when prompted.
3. Tap **Enable Accessibility Service** → find "DEVANSH" in the list → turn it on. This is what
   lets DEVANSH read screen content and tap/type/scroll/go-back in other apps.
4. Go to the **Settings** tab and paste in your own free Google AI (Gemini) API key (get one at
   aistudio.google.com). DEVANSH calls the API directly from your phone; the key is stored only
   in the app's local settings and is sent solely to `generativelanguage.googleapis.com`.
5. Back on the **DEVANSH** tab, tap **Start listening**. Say "Hey DEVANSH" and wait for it to respond
   "Yes?", then give a command, e.g. "Hey DEVANSH, open Chrome."

## What's genuinely implemented

- **Voice loop**: continuous wake-word spotting via Android's built-in `SpeechRecognizer`
  (restarted in a loop checking partial results for "hey devansh"), command capture, and spoken
  responses via `TextToSpeech`.
- **Screen understanding**: `ScreenObserver` walks the live Accessibility node tree into a
  numbered list of clickable/editable elements with their text — this is what gets sent to
  the AI instead of raw pixels.
- **AI planning loop**: `AiAgent` sends the command + current screen to Claude with a defined
  tool set (`open_app`, `click_element`, `type_text`, `scroll_up/down`, `go_back`, `open_url`,
  `make_call`, `open_settings`, `ask_user`, `task_complete`, `task_failed`), executes one tool
  call at a time, re-observes the screen, and feeds the result back — this is the
  observe/plan/act/verify cycle from the spec.
- **Action execution**: `DevAccessibilityService` performs real taps (via
  `ACTION_CLICK`/gesture dispatch), text entry, scrolling, and back/home navigation.
- **Safety rails**: password fields are never written to (`isPasswordField` check); actions
  classified high-risk (deleting, sending, calling, paying) surface a confirmation prompt
  instead of executing immediately; the model is instructed to call `ask_user` rather than
  push through login/OTP/CAPTOCHA screens.
- **Activity log** and a **Settings** screen (API key, wake-word toggle, confirmation toggle).

## What is NOT implemented yet (real work remains)

- **True low-power hotword detection.** Android's `SpeechRecognizer` isn't a dedicated
  always-on hotword engine and will drain battery faster than a real one; swap in something
  like Picovoice Porcupine behind `SpeechRecognizerManager` for production use.
- **Per-app heuristics** (e.g. reliably finding Instagram's specific Follow button across
  its UI variations) — the generic element-index approach works for simple, clearly-labeled
  UI but will need app-specific tuning for complex apps.
- **OCR/vision fallback** for elements with no accessible text (e.g. icon-only buttons,
  custom-rendered UI, games). The spec's "AI vision" step (sending a screenshot to a
  vision-capable model call) is a natural next addition to `ScreenObserver`.
- **Multi-turn `ask_user` resume** — `resumeWithUserAnswer` is stubbed; wiring it fully into
  `WakeWordService` so a clarifying question truly continues the same task is the next thing
  to build.
- **Full activity-log persistence** (currently in-memory, cleared on process death).
- Real device testing across Android versions/OEM skins — I have not run this on hardware.

## Safety and scope, unchanged from your spec

- DEVANSH never enters passwords, OTPs, or attempts to defeat CAPTCHA/biometric/2FA — it pauses
  and asks you to complete those steps.
- High-risk actions (deletions, payments, calls, sending messages) require your confirmation
  by default (toggle in Settings).
- Only the permissions actually used are requested: microphone, notifications (for the
  foreground-service indicator), the Accessibility Service, internet (for the AI calls), and
  phone/contacts (only if you use call features).
