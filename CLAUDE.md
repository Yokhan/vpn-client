# YokhanVPN

Fork of NekoBox for Android with anti-detection hardening for Russian users.

## What this is

Modified NekoBox (sing-box Android client) with:

1. SOCKS5 inbound OFF by default (prevents localhost proxy scanning)
2. exclude_package from sing-box JSON config (not just UI)
3. Auto-update subscriptions via WorkManager
4. Built-in Russian apps bypass list

## Stack

- Kotlin 74% + Java 16% + Go 9% (sing-box core via libcore)
- Android (min SDK TBD)
- Gradle + NDK + Go toolchain
- GPL-3.0 (inherited from NekoBox)

## Build

```bash
# Prerequisites: Android Studio, NDK, Go 1.21+
./gradlew assembleRelease
```

## Upstream

- Fork of: https://github.com/MatsuriDayo/NekoBoxForAndroid
- Upstream branch: `upstream/main`
- Sync: `git fetch upstream && git merge upstream/main`

## Related

- Server/bot project: ../YokhanIndependentVPN/
- Bot generates sing-box configs that this client consumes
