# PushGo for Android

PushGo for Android is the official client app for PushGo. It works with PushGo Gateway to receive notifications on Android devices.



## Project Links

- Apple platforms: https://github.com/AldenClark/pushgo
- Gateway: https://github.com/AldenClark/pushgo-gateway
- Android app (this repo): https://github.com/AldenClark/pushgo-android

## Requirements

- Android 12+ (minSdk 31)

## GitHub Release Packaging

- Pushing a tag that matches `Release-*` triggers `.github/workflows/android-release.yml`.
- The workflow rebuilds the Rust JNI libraries, signs the release APKs and AAB, verifies the signatures, and uploads them to the matching GitHub Release.
- Release asset names include both `versionName` and `versionCode`, for example `pushgo-android-v1.0.0-22-universal-release.apk`.
- Required signing secrets:
  - `PUSHGO_RELEASE_KEYSTORE_B64`
  - `PUSHGO_RELEASE_STORE_PASSWORD`
  - `PUSHGO_RELEASE_KEY_ALIAS`
  - `PUSHGO_RELEASE_KEY_PASSWORD`
- Optional runtime secret:
  - `PUSHGO_PRIVATE_CERT_PIN_SHA256`


---

# PushGo Android（中文）

PushGo Android 是 PushGo 的官方客户端应用，可配合 PushGo Gateway 在 Android 设备上接收通知。

## 项目链接

- Apple 平台：https://github.com/AldenClark/pushgo
- 网关：https://github.com/AldenClark/pushgo-gateway
- Android App（本仓库）：https://github.com/AldenClark/pushgo-android

## 环境要求

- Android 12+（minSdk 31）
