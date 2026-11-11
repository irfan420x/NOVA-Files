# NOVA Files

The file manager for **NOVA OS** — an advanced, fast file manager for Android 10+.

- **Browse** every folder, with Home categories (Images, Videos, Audio, Documents, APKs, Archives) and a Recent files row
- **Search** recursively across storage, with MediaStore-backed category lists
- **Storage Analyzer** with folder sizes and a duplicate-file finder
- **Recycle Bin** — deleted files restore to their original location, auto-purged after 30 days
- **ZIP / 7z / RAR / TAR** extraction (with ZIP-slip protection) and ZIP creation
- **PIN Vault** — private files protected by PIN or fingerprint, with Change PIN / Remove PIN / Forgot PIN
- **OTG / USB storage** — removable volumes appear as their own Home cards, with live plug/unplug refresh
- **Image & text viewer** with pinch-zoom, share and prev/next
- **In-app Error log** — every crash and failed operation is recorded with its time, so problems can be reported with the exact error

## Screenshots

_TODO: add screenshots_

## Build

This project does **not** use Gradle. It builds with a manual pipeline
(`aapt2` + `kotlinc` + `d8` + `apksigner`), which is what `tools/build-apk.sh` runs.

### Prerequisites

- Android SDK with `platforms/android-35` and `build-tools/35.0.0`
  (set `ANDROID_HOME`, e.g. `~/android-sdk`)
- JDK 17 (set `JAVA_HOME`)
- Kotlin compiler 2.0.21 — the embeddable compiler jars
  (`kotlin-compiler-embeddable-2.0.21.jar`, the Compose compiler plugin)
  and `trove4j` on the classpath (see `tools/build-apk.sh`)

### Steps

```bash
# 1. Fetch the dependencies into deps/ (not committed — 73 MB of AARs/JARs)
python3 tools/resolve_deps.py deps/ $(cat tools/deps.txt)

# 2. Extract them into the build tree
python3 tools/prep_deps.py

# 3. Build the APK
bash tools/build-apk.sh
# -> build/apk/nova-files-debug.apk
```

The APK is signed with a debug keystore generated on first build.
Minimum SDK 29 (Android 10), target SDK 35.

## Version history

- **v1.11** — Fingerprint hardening: the fingerprint path catches `Throwable` in the availability check, the prompt launch and both BiometricPrompt callbacks; failures show the exception's class name and are logged.
- **v1.10** — "Remove PIN" in the unlocked Vault (confirms with the current PIN, then locks the vault so the next entry shows the setup screen).
- **v1.9** — Change PIN, PIN setup confirmation (no default PIN), "Forgot PIN?" via fingerprint reset, fingerprint hardening.
- **v1.8** — Vault crash fix (biometric AAR manifest permissions), file-type recognition, in-app Error log.
- **v1.7** — Vault move-out/delete + fingerprint, ZIP/7z/RAR/TAR extraction, viewer pinch-zoom/share/prev-next, folder item counts, persisted sort/grid/hidden, light theme, duplicate delete to Trash, cancellable duplicate scan, progressive Analyzer.
- **v1.6** — OTG / removable-volume support.
- **v1.5** — MediaStore category lists, recursive search, Home Recent files.
- **v1.0–v1.4** — Home/categories, browser, Recycle Bin, Storage Analyzer, Zip, PIN Vault, image/text viewer, storage-total fixes.

## License

MIT — see [LICENSE](LICENSE).
