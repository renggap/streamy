# Validation findings 2026-10-29 (auto-update + targetSdk 36)

## Verified working

- AGP 8.9.3 + compileSdk/targetSdk 36: clean build (debug+release), 79 unit tests x3, lintDebug/lintRelease, androidTest compile, 16 KB zipalign check pass, apksigner verify (same cert), Room schema 5.json unchanged, actionlint clean on workflow.
- Merged release APK manifest: REQUEST_INSTALL_PACKAGES present, androidx.core.content.FileProvider merged (authority com.streamy.app.fileprovider, exported=false, grantUriPermissions=true), file_paths.xml compiled in (cache-path/updates), UpdateChecker + FileProvider classes present in DEX.
- Strings: values-in parity exact (176/176 translatable keys).
- update.json dry-run: versionCode/versionName extraction + heredoc produce JSON matching UpdateChecker.parse contract.

## Blockers found (repo state, not code)

1. **Zero Actions secrets.** Release job requires STREAMY_KEYSTORE_BASE64 (+ optional PASSWORD/ALIAS/KEY_PASSWORD). Without them CI logs "Skip (no signing secrets)" and never publishes a Release or update.json. Set via: Repo Settings → Secrets and variables → Actions, then push a v* tag.
2. **Repo is PRIVATE.** GitHub release assets on private repos are not downloadable without auth: `releases/latest/download/update.json` returns 404 for the app's unauthenticated HttpURLConnection, so UpdateChecker.fetchUpdate() will always see null even after CI publishes. Options:
   - make repo public (simplest; feed + APK then just work), or
   - serve update.json + APK from a public host (e.g. Cloudflare Pages: the web/ site already exists), or
   - add a small public "releases feed" repo holding only update.json + APK.
   The app itself needs no code change for option 1/2 beyond UPDATE_FEED_URL (build.gradle.kts defaultConfig).

## Untestable here

- On-device Android 16 run (host has no KVM; no attached device).
- Live CI tag -> Release flow (blocked on secrets above).
