# NOVA Files

The file manager for **NOVA OS** — a very advanced and smooth file manager
for Android 10+.

- **Home** — storage cards (internal + every removable volume, e.g. USB/OTG
  drives), categories (Images, Videos, Audio, Documents, APKs, Archives),
  Recent files, storage analyzer
- **Browser** — folder navigation, sort and grid/list view (persisted),
  hidden files toggle, long-press menu (Open, Share, Copy, Move, Rename,
  Compress, Extract, Delete, Details, Select)
- **Recycle Bin** — deleted files restore to their original location;
  items older than 30 days are purged
- **Storage Analyzer** — folder sizes, duplicate finder (with a cancellable
  scan; deleting a duplicate asks first and goes to the Trash)
- **Zip** — create and extract ZIP / 7z / RAR / TAR / TAR.GZ (with a
  ZIP-slip guard and an "Extract to…" picker)
- **PIN Vault** — a locked folder with a PIN and fingerprint unlock
  (no default PIN — the first entry asks you to set one); Change PIN,
  Remove PIN, and "Forgot PIN?" via fingerprint
- **Viewers** — image viewer with pinch-zoom, share and prev/next;
  text viewer
- **App Manager** — every installed app with its size, APK backup to
  Downloads/NOVA/Apps, and uninstall
- **Dark / light theme**, persisted
- **In-app Error log** — every crash and failed operation is recorded with
  its time, so problems can be reported with the exact error

## Build

This project does **not** use Gradle. It builds with a manual pipeline
(`aapt2` + `kotlinc` + `d8` + `apksigner`), which is what `tools/build-apk.sh` runs.

### Prerequisites

- Android SDK with `platforms/android-35` and `build-tools/35.0.0`
  (set `ANDROID_HOME`, e.g. `~/android-sdk`)
- JDK 17 (set `JAVA_HOME`, e.g. `tools/jdk-17.0.21+9`)

### Steps

```bash
# 1. Fetch the dependencies into deps/ (not committed — AARs/JARs)
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

- **v1.17** — Final audit fixes: share uses `ACTION_SEND` for one file (the
  old code always sent `ACTION_SEND_MULTIPLE`) with `ClipData` so the URI
  permission actually reaches the app you pick, and reports failure instead
  of doing nothing silently; the Vault PIN is now salted (`v2:<salt>:<hash>`,
  old hashes migrate on unlock) and `allowBackup` is off so the vault and
  the PIN hash never leave the device in a backup; the viewer no longer
  offers Share for vault files; copy/move/Trash/restore now verify the copy
  and the delete, so a failed step can no longer leave duplicates or report
  success; Trash names only strip a 13-digit timestamp prefix (a file called
  `123_report.pdf` no longer restores as `report.pdf`); duplicate scan
  skips `Android/` and hidden folders, and Stop works during hashing;
  search runs as you type; RAR5 is detected and refused honestly (junrar
  reads RAR4 only); extract folders keep the full base name (`my.backup.zip`
  → `my.backup`); size formatting no longer shows `1000.0 KB`; `.gitignore`
  is no longer treated as extension `GITIGNORE`; empty folders survive a
  ZIP round-trip; permission is re-checked on resume; the Analyzer compares
  used space against the userdata partition; New Folder validates its name;
  double-tap paste can't paste twice; all lists use stable keys.
- **v1.16** — Second-audit fixes: Vault Import no longer crashes (classic
  `startActivityForResult` instead of the Activity Result API, which
  FragmentActivity rejects); tapping an APK can now install it
  (`REQUEST_INSTALL_PACKAGES`); Vault import is a MOVE with the real
  filename; text viewer streams the first 200 KB instead of loading the
  whole file; Trash Delete/Empty-all ask first and Restore reports failure;
  plain `.gz` files actually extract; Trash shows real names; sort works in
  category views; rename rejects `/`; moving into its own folder is a no-op.
- **v1.15** — Audit bug fixes: copying/moving a folder into itself is now
  refused (it used to recurse forever and fill storage); Trash restore no
  longer overwrites a same-named file at the original location
  (auto-rename to "name (1).ext"); delete now reports failure instead of
  claiming "Moved to Trash" when the move failed; files on OTG/USB volumes
  can finally be opened and shared (the FileProvider config had no
  root-path); the App Manager now lists every installed app on Android 11+
  (`QUERY_ALL_PACKAGES`) and APK backup copies all split APKs, so backed-up
  apps actually install; the headline "used" storage is now the userdata
  used, matching Settings (it used to include the system partition, reading
  ~14 GB high).
- **v1.14** — Advanced: App Manager — a full list of installed apps
  (user apps first, system apps behind a toggle) with search, APK size,
  Open, Back up APK (saved to Downloads/NOVA/Apps) and Uninstall.
  Reach it from the App manager row on Home; the back button returns Home.
- **v1.13** — Full audit fixes: the category lists and Recent files used
  `LIMIT` in the MediaStore sort order, which the provider rejects —
  categories fell back to a slow whole-storage walk and Recent files
  showed nothing at all. Copying into a folder with a same-named file no
  longer silently overwrites it (auto-rename to "name (1).ext"). RAR
  extraction now goes through the same zip-slip guard as ZIP/7z/TAR
  instead of junrar's unchecked extractor.
- **v1.12** — The system back button now steps back — from Analyzer,
  Trash or Vault to Home, and the error log closes — instead of exiting
  the app. Also: on Android 10 the permission request now uses the classic
  `requestPermissions` API, because the Activity Result API generates
  request codes the FragmentActivity (needed for the Vault fingerprint)
  rejects.
- **v1.11** — Fingerprint hardening: the fingerprint path catches
  `Throwable` in the availability check, the prompt launch, and inside
  both BiometricPrompt callbacks; failures show the exception's class name
  on screen and are logged.
- **v1.10** — "Remove PIN" in the unlocked Vault: confirms with the
  current PIN, then clears the stored PIN hash and locks the vault, so the
  next entry shows the setup screen. Vault files are untouched.
- **v1.9** — Change PIN in the unlocked Vault; setup confirms the PIN and
  states there is NO default PIN; "Forgot PIN?" resets via fingerprint.
- **v1.8** — Vault-crash fix (the biometric AAR's permissions were not
  merged by the manual build, so they were added to the app manifest);
  file-type recognition (`fileKind()`); in-app Error log.
- **v1.7** — Trash restore to original path, Vault move-out/delete +
  fingerprint, extract ZIP/7z/RAR/TAR/TAR.GZ, viewer pinch-zoom/share/
  prev-next, folder item counts, persisted sort/grid/hidden, light theme,
  duplicate delete asks first, cancellable duplicate scan, progressive
  Analyzer, TalkBack labels.
- **v1.6** — Removable-volume discovery (OTG/USB drives) with one Home
  card per volume and live plug/unplug refresh.
- **v1.5** — MediaStore category lists, recursive search, Home Recent files.
- **v1.4** — Storage total via /proc/mounts StatFs sum, new launcher icon.
- **v1.3** — "Extract to…" picker, BackHandler, delete-confirm, 30-day
  trash purge, rename validation, async folder size/count in Details.
- **v1.2** — Long-press bottom-sheet menu.
- **v1.1** — Storage-math and category-count fixes.
- **v1.0** — Home/categories, browser, Recycle Bin, Storage Analyzer +
  duplicate finder, Zip, PIN Vault, image/text viewer.

## License

MIT — see [LICENSE](LICENSE).

