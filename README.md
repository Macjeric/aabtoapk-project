# AAB to APK

Android app that converts an Android App Bundle (`.aab`) into **one installable universal APK**,
signed on the phone. It re-implements the part of Google's `bundletool` + `aapt2` that is needed
for `build-apks --mode=universal`, in plain Kotlin (no third-party libraries).

## Build the APK (GitHub Actions)
1. Create a repo and upload the **contents** of this folder (so `build.gradle.kts` is at the repo root).
2. Open **Actions → Build APK → Run workflow** (it also runs on every push).
3. When it is green, download **AabToApk-debug-apk** from *Artifacts*, unzip, install `app-debug.apk`.

## Use
1. **Choose .aab file** → 2. **Convert** → 3. **Install APK** (or **Save APK to Downloads**).
   The first time, Android asks you to allow this app to install apps.
   "Smaller APK" keeps only your phone's CPU types in native libraries.

## What it does
- `manifest/AndroidManifest.xml` and every proto XML resource → binary XML
- `resources.pb` → `resources.arsc`
- merges `dex/`, `lib/`, `assets/`, `root/` of the **base** module, removes split-only flags
- zip-aligns (4 B for resources.arsc, 16 KB for `.so`) and signs with APK Signature Scheme v2

## Limits
- Base module only (dynamic-feature modules and Play Asset Delivery packs are skipped, with a warning).
- Signed with a key generated on the phone (stored in the app's private storage), **not** the
  original developer's key. If the app is already installed (e.g. from Play) uninstall it first.
- A converted APK is for your own bundles/testing; the result is not identical to what Play serves.

## How it was checked
On a PC, the same conversion code was run on real apps (NewPipe, Termux) and compared with
Google's `aapt2`: identical `dump resources` output, identical `dump xmltree` for every XML
file (1,100+ files), identical contents for all other files, and the signature verified with
Google's `apksig` verifier. It has not yet been run on a physical phone.
