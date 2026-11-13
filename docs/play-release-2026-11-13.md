# Google Play release — Streamy (`dev.pages.streamy`) — 2026-11-13

Plan + checklist for the first Google Play release. Repo-side work is done (see "Repo changes"
below); the rest is Play Console work that needs the developer account.

## Repo changes made (this date)

- `applicationId` → `dev.pages.streamy` (Kotlin namespace/packages stay `com.streamy.app`).
- New `play` build type (`bundlePlay` → `app/build/outputs/bundle/play/app-play.aab`):
  - `BuildConfig.IN_APP_UPDATE = false` — the startup update check in `StreamyViewModel` is
    skipped. Play delivers updates itself; its Device & Network Abuse policy forbids
    self-updating outside Play.
  - `src/play/AndroidManifest.xml` strips `REQUEST_INSTALL_PACKAGES` (restricted permission).
  - Signed with the same `streamy-release.jks` upload key as GitHub releases.
- CI release job now also builds + uploads `streamy-play-aab`.
- Privacy policy published at <https://streamy-app.pages.dev/privacy/> (web/privacy/).

GitHub (sideload) builds keep the in-app updater — unchanged behavior there.

## Building the artifact

```bash
./gradlew :app:bundlePlay        # signed AAB: app/build/outputs/bundle/play/app-play.aab
```

Needs `streamy.keystore.*` in `local.properties` (already present) — this keystore is the
**upload key**. BACK UP `streamy-release.jks` + its password; losing the upload key means
requesting a reset from Google (slow).

## Play Console steps (one-time)

1. **Developer account**: pay the one-time $25 registration at
   <https://play.google.com/console> (identity verification applies since 2023).
2. **Create app**: name `Streamy`, default language Indonesian (or English), **App** (not Game),
   **Free**. Package name `dev.pages.streamy` is claimed at first AAB upload.
3. **Play App Signing**: accept enrollment. Upload the first AAB — Play verifies
   `streamy-release.jks` as the upload key and Google keeps the app signing key. (If the account
   predates mandatory signing, enroll in Release → Setup → App integrity.)
4. **App integrity page**: note the Play App Signing key certificate — useful later for
   e.g. API key restrictions.

## Store listing (copy-paste ready)

Language: Indonesian (id). English translation optional but recommended later.

- **App name**: `Streamy` (30 char limit; "Streamy — Siaran Radio" also fits)
- **Short description** (max 80):
  `Siaran radio internet dari ponsel Anda. Kirim suara langsung ke server SHOUTcast Anda.`
- **Full description** (max 4000) — adapt from `web/android/` marketing copy:

  > Streamy mengirim suara Anda secara langsung ke server radio internet SHOUTcast/Icecast —
  > dan tetap bersiaran meski layar mati.
  >
  > • Ke server radio sungguhan — protokol sumber SHOUTcast v2 (Ultravox 2.1) dan ICE/1.0
  > (Icecast), teruji langsung terhadap DNAS 2.6.1.
  > • Kualitas audio pilihan Anda — MP3 16–192 kbps, pilih laju sampel serta mono/stereo.
  > • Tetap siaran di latar belakang — layanan latar depan menjaga mikrofon dan koneksi tetap
  > hidup, dan menyambung ulang otomatis setelah gangguan jaringan.
  > • Judul now-playing — ketik nama surat, qari, atau acara saat siaran; pendengar melihatnya
  > seketika. Tanpa kata sandi admin server.
  > • Meter VU dan osiloskop real-time dari sinyal mikrofon Anda.
  > • Profil banyak stasiun — simpan host, port, kata sandi, dan pengaturan audio per server.
  > • Perekam MP3 mandiri — simpan hafalan atau acara Anda ke penyimpanan perangkat.
  >
  > Streamy adalah alat siaran profesional yang muat di saku. Audio dikirim hanya ke server yang
  > Anda tentukan sendiri; pengembang tidak mengumpulkan data apa pun.

- **Assets** (must be provided/created):
  - App icon 512×512 PNG (32-bit, no transparency) — export from the adaptive icon design
    (`ic_launcher_foreground`/`_background` vectors), don't upscale the 192px mipmap.
  - Feature graphic 1024×500 PNG/JPG.
  - ≥ 2 phone screenshots (1080×1920 or 1080×2340 portrait, or landscape equivalents),
    unmodified. Home screen + live streaming screen with VU meter make the best pair.
    Device frames are allowed as long as they don't mislead.
- **Category**: Music & Audio. **Tags**: optional.
- **Contact email** (required), website `https://streamy-app.pages.dev/`.

## Policy questionnaires

- **Privacy policy URL** (required for mic apps):
  `https://streamy-app.pages.dev/privacy/` — deploy `web/` to Cloudflare Pages first and verify
  the URL loads publicly.
- **Data safety form** — recommended declaration:
  - "Does your app collect or share user data?" → **No.**
  - Rationale: mic audio, titles, recordings and credentials go only to the user's own
    server (user-directed destination, like an FTP/email client); nothing reaches the
    developer or third-party SDKs (none are linked). This matches the privacy policy.
  - If review pushes back, the conservative fallback is declaring *Microphone audio* as
    collected → "App functionality", not shared, encrypted in transit, deletable. Only do
    this if Play insists during review.
- **Content rating (IARC)**: category "Music & Audio". Answer honestly, expected: no UGC
  exposed to other users through the app (the stream goes to the user's own radio server,
  not a social feed), no violence/sexual themes → likely **3+/Everyone**. The result applies
  to all stores IARC covers.
- **Target audience**: 18+ (radio operators) simplifies COPPA; "age declaration" follow-ups
  then don't apply. Do NOT declare under-13.
- **Ads**: No ads.
- **App access**: all features available without restrictions (no login).
- **Account deletion**: not applicable (no accounts).
- **Background microphone**: FGS type `microphone` is declared with `foregroundServiceType`;
  Play may ask for a short video showing the mic foreground-service use case during review —
  record the app streaming with screen off. Keep it handy.

## Release

1. First upload → **Internal testing** track. Install on a real device from Play, verify:
   streaming works, background streaming works, recorder works, no update dialog appears
   (Play builds never show it).
2. Then Production rollout at 10% → 100% over a few days (or straight to 100% if confident).
3. Version management: bump `versionCode` in `app/build.gradle.kts` for every Play upload
   (same file feeds the GitHub release; one bump covers both). Never reuse a versionCode.

## Follow-ups (non-blocking)

- Add "Get it on Google Play" badge + link
  (`https://play.google.com/store/apps/details?id=dev.pages.streamy`) to `web/android/` once
  the listing is live.
- Optional: extract the listing icon/feature graphic source design into `web/` assets so they
  can be regenerated.
- Optional later: fastlane supply upload from CI (needs a Play service account JSON in repo
  secrets).
