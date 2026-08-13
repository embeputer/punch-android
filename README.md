# punch-android

Android client for **Punch**. On the phone the app is named **Punch** (`com.punch.android`). It is a normal app with a launcher icon, not a home-screen replacement.

Talks to a Pi coding-agent HTTP gateway.

## Install a prebuilt APK

Current debug build: **`punch-android-v0.1.apk`**

Sideload it (USB debugging):

```bash
adb install -r punch-android-v0.1.apk
```

Or copy the APK onto the phone and open it. Enable **Install unknown apps** for the file manager if Android asks.

This is a **debug** build, not a Play Store release. Uninstall any old pincher/launcher package if it is still on the device.

Open **Punch** from the app drawer. Grant microphone when you first hold the mic in the Ask bar.

## Requirements (build from source)

- JDK 17 (`JAVA_HOME` pointing at a JDK 17 install)
- Android SDK under `$HOME/Android/Sdk` with:
  - command-line tools
  - `platforms;android-36`
  - `build-tools;36.x`
  - `platform-tools` (`adb`)
- No Android Studio required

## Environment

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home)"
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Create a machine-local (gitignored) `local.properties`:

```bash
printf 'sdk.dir=%s\n' "$HOME/Android/Sdk" > local.properties
```

## Build

```bash
./gradlew assembleDebug
```

That writes `app/build/outputs/apk/debug/app-debug.apk`. The shared snapshot is that same APK copied as `punch-android-v0.1.apk`.

## Test

```bash
./gradlew test
```

## Install a just-built APK

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Pi gateway connection

The Pi TypeScript SDK (`createAgentSession`) cannot run on Android. Punch uses the HTTP shape in front of `pi --mode rpc`:

- `GET /health`
- `POST /session`
- `POST /session/{id}/message` with `{ "parts": [{ "type": "text", "text": "..." }, ...] }`
- `POST /session/{id}/abort`

Auth is HTTP Basic. Username defaults to `opencode`; password is `OPENCODE_SERVER_PASSWORD`. Punch `pi-gateway` on **4096** is **plain HTTP** even over Tailscale — `https://…:4096` will fail with a TLS parse error. Use `https://` only if you terminated TLS (Tailscale Serve on 443).

### Pair from the phone

1. Run a Pi HTTP gateway on the host (Punch `pi-gateway` on port **4096**, or equivalent).
2. In Punch, open Settings and enter:
   - LAN: `http://<host-lan-ip>:4096`
   - Tailscale: `http://<tailscale-name-or-ip>:4096`
   - Username `opencode` (or leave blank; Punch fills that in)
   - Password = `OPENCODE_SERVER_PASSWORD`
3. Tap **Save**, then **Test**.
4. Type a prompt and send, or hold the mic in the Ask bar.

Never commit passwords. The app stores them in private app storage and does not log them.

## Bundles

Bundles are Punch’s project folders. Each has a name, custom instructions (in bundle settings), and files. Chats inside a bundle, and any other chat that **Use**s that bundle, send that memory to Pi with the prompt. Long-press a bundle or chat in the drawer to delete it.

## Architecture

```
app/src/main/java/com/punch/android/
  MainActivity.kt
  data/
    PunchModels.kt
    PunchStore.kt
    PromptComposer.kt
  input/
    PttController.kt
    PttHub.kt
    AndroidSpeechTranscriber.kt
    PttTypes.kt
  gateway/
    GatewayClient.kt
    GatewayCredentialsStore.kt
    GatewayUrl.kt
    GatewayModels.kt
  ui/
    ChatScreen.kt
    PunchDrawer.kt
    BundleScreen.kt
    SearchChatsScreen.kt
    SettingsScreen.kt
    PunchMark.kt
    theme/Theme.kt
```
