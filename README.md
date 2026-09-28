# SHILLGRAM for Android

SHILLGRAM is a Telegram client with a built-in SHILLVPN. This is the Android version, a fork of [Nekogram](https://github.com/Nekogram/Nekogram) (itself based on Telegram for Android). Desktop version: [audit0/SHILLGRAM](https://github.com/audit0/SHILLGRAM). Channel: [@SHILGRAM](https://t.me/SHILGRAM).

## Download

APK files are in [Releases](https://github.com/audit0/SHILLGRAM-Android/releases/latest):

- `arm64-v8a` — almost every phone from the last 7 years (recommended);
- `universal` — if unsure;
- `armeabi-v7a`, `x86`, `x86_64` — old phones and emulators. The SHILLVPN core exists only for arm64 and x86_64; on the other builds Telegram works without it.

Allow installing from this source when Android asks. Updates are installed the same way on top of the old version.

## What differs from Nekogram

- package `io.github.audit0.shillgram`, SHILLGRAM name and icon, own API keys;
- built-in SHILLVPN: subscription link or a 3-day trial, local Xray core (`libxray.so`) serving a SOCKS5 proxy for Telegram only;
- no Firebase Analytics, no Sentry, no Nekogram helper bot, no Google push (messages arrive while the app keeps its connection).

## Build

1. `git clone --recursive https://github.com/audit0/SHILLGRAM-Android.git`
2. JDK 21, Android SDK with build-tools 37.0.0, NDK 27.3.13750724, CMake 3.22.1.
3. `local.properties`: `sdk.dir`, your own `apiId`/`apiHash` from my.telegram.org, and `storeFile`/`storePassword`/`keyAlias`/`keyPassword` of your own keystore.
4. Put your certificate's DER size and CRC-32 into `TMessagesProj/jni/colorado/colorado.h` (the app refuses to start with a different signature), or remove the check.
5. `./gradlew assembleRelease` — `scripts/fetch_xray.sh` downloads and verifies the Xray core automatically.

## License

GPLv2, as Telegram for Android and Nekogram. Xray-core is MPL-2.0. The SHILLGRAM name and icon are not licensed for other builds.
