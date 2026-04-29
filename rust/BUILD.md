# Rust NDK Build Instructions

## Prerequisites

```bash
# 1. Install Rust
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env

# 2. Add Android targets
rustup target add aarch64-linux-android      # arm64 phones (most common)
rustup target add armv7-linux-androideabi    # arm32 phones (older devices)
rustup target add x86_64-linux-android       # emulators / Chromebooks

# 3. Install cargo-ndk
cargo install cargo-ndk

# 4. Install Android NDK via Android Studio:
#    SDK Manager → SDK Tools → NDK (Side by side)
#    Set ANDROID_NDK_HOME environment variable
```

## Build

```bash
cd rust
cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  --android-platform 24 \
  --output-dir ../app/src/main/jniLibs \
  build --release
```

The `.so` files land in:
- `app/src/main/jniLibs/arm64-v8a/libprivacyguard_core.so`
- `app/src/main/jniLibs/armeabi-v7a/libprivacyguard_core.so`
- `app/src/main/jniLibs/x86_64/libprivacyguard_core.so`

## Automatic build via Gradle

`app/build.gradle.kts` contains a `buildRust` task that runs automatically before
`preBuild`. If `cargo-ndk` is not on PATH, the task is silently skipped and the
app falls back to the Kotlin implementation.

## What the native library accelerates

| Feature | Kotlin | Rust | Speedup |
|---|---|---|---|
| Domain bloom filter lookup | O(n) hash | O(1) bit array | ~5× |
| JA3 TLS fingerprint | MD5 via JCA | Pure Rust MD5 | ~2× |
| Shannon entropy (DNS tunnelling) | Kotlin sum | SIMD-friendly Rust | ~3× |
| IPv4 packet classification | JVM bytecode | Zero-copy Rust | ~4× |

The app automatically detects `.so` presence at runtime via `RustBridge.isAvailable`.
All functions have Kotlin fallbacks — the app works correctly without the native library.
