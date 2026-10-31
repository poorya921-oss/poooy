# POOOY: lightweight Xray VPN client for Android

Native Kotlin / Jetpack Compose / Material 3 app. Imports VLESS, VMess, Shadowsocks, Trojan, SOCKS and HTTP configs, runs them through **Xray-core** and routes the whole device via Android `VpnService`.

> ## Verification status (please read)
> This source tree was written without an Android SDK, Kotlin compiler, Gradle or NDK available, so **it has never been compiled, run or tested**. What *was* checked: all XML is well-formed, every `R.string` / `R.drawable` reference resolves, brace balance, and project-internal imports. A JVM unit-test file is included (`ConfigParserTest`) but **has not been run**. Expect to fix small compile errors on first build. The places most likely to need adjustment are listed under *Known risks*.
> No APK is included, because it could not be built here.

## How it works

```
apps -> TUN (VpnService) -> hev-socks5-tunnel (native) -> 127.0.0.1 SOCKS5 (Xray inbound) -> Xray outbound -> your server
```

* **Xray-core** comes from `libv2ray.aar` (gomobile build, 2dust/AndroidLibXrayLite). Only `core/XrayCore.kt` touches it.
* **hev-socks5-tunnel** (C, built by the NDK during the Gradle build) turns TUN packets into SOCKS5 TCP/UDP. This is the same split v2rayNG uses.
* POOOY excludes **itself** from the VPN, so Xray's sockets reach your server directly (no routing loop, no `protect()` hooks).
* The loopback SOCKS port is random per connection and protected with a random per-session username/password, so other apps on the device cannot use it.
* Private/LAN ranges are routed `direct`; everything else uses the selected server. DNS: the VPN hands out 1.1.1.1 / 8.8.8.8 (queried *through* the tunnel); Xray resolves server hostnames with the system resolver outside the VPN. IPv6 is routed into the tunnel so it cannot leak around it.
* Nothing runs until you tap CONNECT. Disconnect tears down tunnel, core and foreground service. Traffic counters are polled only while the screen is visible (the core keeps counting meanwhile).

## Build

Requirements: JDK 17, Android Studio (Ladybug or newer) with **SDK Platform 35** and **NDK** (SDK Manager -> SDK Tools -> NDK (Side by side), r27+ recommended), `git`, `curl`.

1. Fetch the two external pieces:
   * Linux/macOS: `./scripts/fetch-deps.sh`
   * Windows: `powershell -ExecutionPolicy Bypass -File scripts/fetch-deps.ps1`

   This places **`app/libs/libv2ray.aar`** and clones **`app/src/main/jni/hev-socks5-tunnel/`** (with submodules). If the AAR download fails, download `libv2ray.aar` manually from the AndroidLibXrayLite releases page and put it at exactly that path. A Gradle pre-build check stops with a clear message if either is missing.
2. Open the folder in Android Studio and let it sync (it reads `gradle/wrapper/gradle-wrapper.properties`; the wrapper JAR is not included). Or on the command line: `gradle wrapper --gradle-version 8.9` once, then:
   ```
   ./gradlew assembleRelease -Ppooy.debugSign=true     # APK signed with the debug key, personal use
   ./gradlew assembleDebug
   ```
   Output: `app/build/outputs/apk/`. For a properly signed release, build without `pooy.debugSign` and sign the unsigned APK with `zipalign` + `apksigner`.
3. ABIs: default is `arm64-v8a` only (smallest). Add more with `-Ppooy.abis=arm64-v8a,armeabi-v7a` (use `x86_64` for an emulator).

Unit tests (parser): `./gradlew testDebugUnitTest`.

## Using the app

**+ menu:** paste from clipboard, scan QR, import from URL, import from file, add manually.
**Per config (⋮):** test latency, edit (name + raw link/JSON), rename, duplicate, delete. Tap a card to select it; selecting while connected switches the live tunnel.
**⋮ menu (top):** test all, update subscriptions, manage subscriptions.

Supported inputs:

| Input | Notes |
|---|---|
| `vless://` | tcp/raw, ws, grpc, httpupgrade, xhttp, kcp, h2; TLS and REALITY (`pbk`, `sid`, `spx`, `fp`, `flow`) |
| `vmess://` | base64-JSON (v2rayN style) and URI style |
| `ss://` | SIP002, legacy base64, 2022 ciphers. **Plugins are rejected** (not supported) |
| `trojan://` | TLS by default; transports as VLESS |
| `socks://`, `socks5://` | optional `user:pass` or base64 userinfo |
| `http://user:pass@host:port` | HTTP proxy. A bare `http(s)://…` URL is treated as a **subscription URL** instead; `http://host:port#name` is an HTTP proxy |
| Xray JSON | full config (first proxy outbound is used) or a single outbound object. Chains (`dialerProxy`), routing and DNS from the file are not preserved |
| Subscriptions | base64 or plain list of links. Updating replaces that subscription's configs and keeps your selection if the same server is still present |

Subscription/URL downloads must be **https** (cleartext HTTP is blocked by Android's default policy on purpose; a plain-HTTP subscription could be tampered with). Downloads go out directly, not through the VPN.

## Privacy and security

* Profiles/subscriptions are stored in one file, **AES-256-GCM encrypted with a non-exportable Android Keystore key**; `allowBackup=false`.
* No analytics, ads, crash reporters or third-party network calls. The only outbound traffic is your VPN traffic, subscription URLs you add, and the latency test (`https://www.gstatic.com/generate_204`, change `XrayConfigBuilder.TEST_URL` if you prefer another).
* Permissions: `INTERNET`, `FOREGROUND_SERVICE` (+ `SYSTEM_EXEMPTED` type for VPN), `POST_NOTIFICATIONS` (asked once on first connect), `CAMERA` (asked only when you tap Scan QR).

## Footprint

R8 + resource shrinking, one ABI, no image libraries, no HTTP library, no JSON library (platform `org.json`), no DI/serialization frameworks, no `material-icons-extended`. APK size is dominated by the Xray AAR (Go runtime). **RAM:** the UI side is small, but the Go runtime inside Xray sets the floor (tens of MB while connected); this cannot go below what Xray needs. I have not measured either number.

## Known risks (things I could not verify)

1. **AAR API shape.** `XrayCore.kt` is written against `Libv2ray.initCoreEnv`, `newCoreController(handler)`, `CoreController.startLoop/stopLoop/isRunning/queryStats` and `Libv2ray.measureOutboundDelay`. `startLoop` is called reflectively so both a 1-arg and a 2-arg (with tun fd) variant work. If your AAR is an older release exposing `newV2RayPoint`, or names differ, adapt only that file.
2. **hev-socks5-tunnel build.** `Application.mk` passes `-DPKGNAME=com/pooy/vpn/service` so the native library registers `com.pooy.vpn.service.TProxyService`. If the native build or `JNI_OnLoad` fails, compare with the hev README / v2rayNG's `jni/Application.mk`. The generated YAML uses `socks5.username/password`, which needs a reasonably recent hev revision (the fetch script takes the latest; pin with `HEV_REF`).
3. **Gradle/AGP versions** (AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9, Compose BOM 2024.12.01) were chosen from memory of a compatible set; bump if Studio suggests newer ones.
4. **Foreground service type.** Uses `systemExempted` (the category Android documents for VPN apps). Untested on API 34+ devices.
5. **REALITY / allowInsecure:** `allowInsecure` is only emitted if the link asks for it; recent Xray versions deprecate it, so such links may fail on a new core.
6. UI strings are English only; the theme is dark only (deep navy, blue accent).

## Layout

```
app/src/main/java/com/pooy/vpn/
  MainActivity.kt, ScanActivity.kt, MainViewModel.kt
  core/     ConfigParser, Importer, Subscriptions, XrayConfigBuilder, XrayCore (AAR adapter)
  data/     Models, ProfileStore, Crypto (Keystore)
  service/  PooyVpnService, VpnController, TProxyService.java (JNI)
  ui/       Theme, PooyApp (Compose)
app/src/main/jni/   Android.mk, Application.mk (+ hev-socks5-tunnel/ after fetch)
scripts/            fetch-deps.sh / .ps1
```
