# Pixiv Morphe Patches - Workspace Guidelines

## Git & Repository Invariants
- **No GPG Signing**: Always execute git commits with `--no-gpg-sign` (or ensure `git config commit.gpgsign false`).
- **Target Remote**: Push strictly to `origin main` (`https://github.com/Fripe070/PixivPatches`). Never push to upstream or foreign repositories.

## Research & Code Inspection Protocol
- **Shallow Clone Over Repeated Fetching**: When inspecting reference projects or external repositories, shallow clone them locally (`git clone --depth 1 <url>`) and use ripgrep/local file tools rather than making repetitive API or curl calls. Clean up temporary clones when no longer needed.

## Morphe Manager Source Compatibility
- **Metadata Bundle File**: Always maintain `patches-bundle.json` at the repository root. Morphe Manager resolves sources via `https://raw.githubusercontent.com/<owner>/<repo>/main/patches-bundle.json`.
- **Release Synchronization**: When cutting a new patch release, ensure `patches-bundle.json` has matching `version`, `download_url` (pointing to the release `.mpp`), and `page_url`.

## Emulator Testing Procedure
When testing patches on the local Android emulator:
1. **Tool Locations**:
   - ADB: `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`
   - CLI: `E:\Projects\Coding\Vibes\pixiv\tools\morphe-cli.jar`
   - Base APK: `E:\Projects\Coding\Vibes\pixiv\pixiv-base.apk` (`jp.pxv.android` v6.196.0)
   - Target Device: `emulator-5554` (`Pixel_8_API_35`)
2. **Build & Patch**:
   - Build MPP: Run `.\build-mpp.ps1` from `pixiv-morphe-repo`.
   - Patch APK: `java -jar <cli> patch -p <mpp> -o E:\Projects\Coding\Vibes\pixiv\pixiv-patched.apk <baseApk>`
3. **Deploy & Launch**:
   - Install: `& $adb -s emulator-5554 install -r -d E:\Projects\Coding\Vibes\pixiv\pixiv-patched.apk`
   - Launch: `& $adb -s emulator-5554 shell monkey -p jp.pxv.android -c android.intent.category.LAUNCHER 1`
4. **Verification & Debugging**:
   - Live Logs: `& $adb -s emulator-5554 logcat -c; & $adb -s emulator-5554 logcat -v time | Select-String "Pixiv|Morphe"`
   - Screenshot Verification: `& $adb -s emulator-5554 exec-out screencap -p > screenshot.png`

## Proper GitHub Build & Release Procedure
Whenever code, extensions, or patch definitions are updated:
1. **Local Build & Sanity Check**:
   - Compile locally using `.\build-mpp.ps1` to ensure syntax, bytecode hooks, and DEX bundling succeed without errors.
2. **Version Bump (if releasing to Morphe Manager)**:
   - Update version in `patches/build.gradle.kts` (e.g., `1.0.1`).
   - Update `patches-bundle.json` with the new version and release download URL:
     ```json
     {
       "created_at": "<ISO-Timestamp>",
       "description": "<Summary of changes>",
       "download_url": "https://github.com/Fripe070/PixivPatches/releases/download/v<version>/patches-<version>.mpp",
       "signature_download_url": "",
       "page_url": "https://github.com/Fripe070/PixivPatches/releases/tag/v<version>",
       "version": "<version>"
     }
     ```
3. **Commit & Push**:
   - `git add -A`
   - `git commit --no-gpg-sign -m "feat/fix: <description>"`
   - `git push origin main`
4. **Verify GitHub Actions**:
   - Check `https://github.com/Fripe070/PixivPatches/actions`.
   - Verify workflow `.github/workflows/build.yml` runs `./gradlew :patches:buildAndroid` and uploads artifact `pixiv-patches-bundle`.
5. **Publish GitHub Release**:
   - Create a tag matching the version (e.g. `v1.0.1`): `git tag v1.0.1; git push origin v1.0.1`.
   - On GitHub (or via `gh release create v1.0.1 patches/build/libs/pixiv-patches-1.0.1.mpp --title "v1.0.1" --notes "<notes>"`), publish a Release and attach `pixiv-patches-<version>.mpp`.
   - Morphe Manager will now automatically detect and load the updated patches.
