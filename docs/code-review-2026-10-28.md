# Streamy — Ponytail Audit + Comprehensive Review (2026-10-28)

Scope: full `app/` (audio, net, JNI/CMake, services, data/Room, storage, manifest, build,
Compose UI/UX). Prior reviews: `code-review-2026-08-03.md`, `-08-31.md`, `-09-08.md` —
items fixed there are not repeated. Review itself was read-only; fixes are tracked in §7.

## Validation

| Check | Result |
|---|---|
| `:app:testDebugUnitTest`, `:app:lintDebug` | **Not run** — this host has no Android SDK (`sdk.dir=/opt/android-sdk` missing; JDK only at `~/.local/share/jdk-21`). Re-run on a dev box before acting on fixes. |
| Method | Line-by-line read of every file in scope. ✔ = I re-traced the code path myself; others were traced by a reviewer pass and are marked *plausible* where the reviewer was not certain. |

---

## 1. Ponytail audit (`ponytail:` = deliberate, tracked simplification)

| Marker | Status | Verdict |
|---|---|---|
| `StreamyViewModel.kt:48` — "persist selectedProfileId across process death (DataStore)" | **Stale** ✔ — already persisted via `streamy_ui` SharedPreferences (`:60-70`, `:180-188`). | Delete marker. |
| `AacEncoder.kt:136` — "switch ≤24 kbps mono to HE-AAC v2" | Still pending, but **premise wrong**: Parametric Stereo needs a stereo source; HE-AACv2 gives nothing for mono. Also dormant while AAC is meant to be off. | Reword: "≤24 kbps *stereo* → HE-AACv2; re-check ADTS channel config / core rate". |
| `proguard-rules.pro:15` — minify off until device-verified | Accurate (`isMinifyEnabled=false`). | Keep. |
| `MainActivity.kt:46` — type-safe nav | Accurate (string routes). | Keep; low value at 4 destinations. |
| `MainActivity.kt:48` — Hilt in "Task 14" | Accurate, but "Task 14" is a dead cross-reference to a closed plan. | Keep, drop task number. |
| `StreamService.kt:68` — no DI / media session / `:stream` process | Accurate; separate process would complicate binder+StateFlow wiring for no gain. | Keep. |
| Plan-era: ADTS-strip heuristic, deterministic jitter | **Resolved** ✔ — `AacEncoder` emits raw AUs and `UvoxClient` ADTS-wraps; `nextBackoffMs` uses real `Random`. | Nothing to do. |

**Orphaned deferrals:** the 2026-08-31 review says plaintext Room passwords and the
`START_STICKY` profile-id loss are "tracked by existing `ponytail:` markers" — no such markers
exist in code. Add them (or resolve — see S-5 below, which makes the sticky one moot).

---

## 2. Findings — P1 (crash / blocks core use)

| # | Finding | Where | Scenario → fix |
|---|---|---|---|
| A-1 ✔ | **NaN dB crashes the app mid-broadcast.** | `LevelTap.kt:17-22`, `AudioCapture.kt:80-85`, `VuMeter.kt:141-145` | Dead mic / USB unplug / audioserver restart → after 100 failed reads capture emits `ShortArray(0)` → `rmsDb` = `sqrt(0/0)` = NaN (`max(NaN,1e-7)` stays NaN, floor check is false) → `NaN.roundToInt()` throws in composition. **Fix:** `if (pcm.isEmpty()) return FLOOR_DB`; pipeline should skip encode/sink/level for empty frames. |
| S-1 ✔ | **After any terminal error, STOP doesn't reset and leaves an FGS promise unkept.** | `StreamService.kt:271-273, 282-284, 410-412`; `ServiceController.kt:28-33` | Mic grabbed by a call (`mic_busy`) or start throws → `stopAndShutdown(preserveTerminal=true)` latches `shuttingDown` → service stays alive (Activity binding). User taps STOP → `startForegroundService(ACTION_STOP)` → `stopAndShutdown()` returns immediately: state stuck at Error (no START until Activity recreate) and `startForeground` never called → `ForegroundServiceDidNotStartInTimeException`/ANR. **Fix:** in `ACTION_STOP`, if already shut down, set `_state = Idle`; send STOP with plain `startService` (or promote before teardown). Add a service-level test, not just `resolveTeardownState`. |
| U-1 ✔ | **Denying notifications blocks streaming forever.** | `StreamyViewModel.kt:420-431` | API 33+: grant mic, deny POST_NOTIFICATIONS → `results.values.all{it}` false → "Microphone permission required"; after "don't ask again" it auto-denies every time. FGS runs fine without it. **Fix:** gate on RECORD_AUDIO only; add settings deep-link for permanent mic denial. |
| N-1 ✔ | **AAC is selectable in the UI** — contradicts AGENTS.md/README ("UI-disabled"). | `BitrateChooser.kt:66,89-97`; callers `HomeScreen.kt:157`, `EditProfileScreen.kt:278` | `allowAac` defaults `true`, no caller passes `false` → AAC reachable on Home and in the form (incl. DJ mode, which the comment says is MP3-only). Makes A-2/A-4/AAC-encoder issues live. **Fix:** pass `allowAac = false` (or remove pill) until re-verified on DNAS2. |

## 3. Findings — P2 (real functional bugs)

### Services / data
| # | Finding | Where | Notes / fix |
|---|---|---|---|
| S-2 ✔ | Transport `Failed` (auth NAK / gave up) sets Error but never tears down — mic indicator, wake lock, wifi lock and FGS stay held until the user taps Stop. | `StreamService.kt:378`, `StreamTransport.kt:88,94` | On `Failed` launch `stopAndShutdown(preserveTerminal=true)` — only after S-1 is fixed. |
| S-4 | Process death mid-recording leaves the MediaStore item `IS_PENDING=1` → invisible; *plausibly* auto-purged after ~7 days on API 30+. | `RecordingStorage.kt:42,51-59` | Sweep own pending items at app/service start and publish them (MP3 resyncs, truncated file still plays). |
| S-5 | Null-intent sticky restart calls `startForeground(MICROPHONE)` unnecessarily (not an fgRequired start); *plausible* SecurityException / background-start crash loop on 31+/34+ when mic was revoked. | `StreamService.kt:131-136`, `RecordService.kt:88-93` | Return `START_NOT_STICKY`; null-intent → `stopSelf()`. Supersedes 09-08 P2-10. |

### Audio / net
| # | Finding | Where | Notes / fix |
|---|---|---|---|
| A-2 ✔ | Empty dead-mic frame is also the transcode sink's **end marker** → AAC→MP3 recording silently stops after a ~1 s dropout. | `StreamSink.kt:112`, `AudioPipeline.kt:68` | Compare `frame === END_MARKER`; don't forward empty frames. |
| A-3 ✔ | Mic-busy detection takes ~51 s, not ~1 s: 100×10 ms errors per empty frame × `SHORT_READ_LIMIT` 50. | `AudioCapture.kt:80`, `AudioPipeline.kt:58` | Single threshold. |
| A-4 ✔ | `Mp3TranscodeSink.flush` uses `offer(END_MARKER)` — fails when queue full (exactly when behind) → join times out → `encoder.close()` while transcode thread may be inside LAME (native UAF) or thread parks forever. Existing test already hits this path. | `StreamSink.kt:95-105` | `clear()` then `put` marker; close encoder only if thread dead. |
| N-2 ✔ | Stop during handshake leaves an **authenticated source connected**: `close()` sees `socket == null` (Icecast assigns after 200 OK), handshake completes, transport sees `stopped` and exits without closing → mount stays occupied, immediate re-Start NAKs "in use" (fatal). | `IcecastSourceClient.kt:46-88`, `UvoxClient.kt:61-68`, `StreamTransport.kt:75-84` | `@Volatile closed` in clients, assign socket before connect; transport closes client if stopped after `connect()`. |
| N-3 ✔ | Every NAK is fatal **even on reconnect** — after a network blip Icecast returns 403 "mountpoint in use" until it reaps the old source → stream ends instead of backing off. | `StreamTransport.kt:87` | Fail fast only before first Live, or classify NAK reason. |
| N-4 | `UvoxClient` writes from three threads without a lock (`send`, META coroutine, close-Terminate thread); `out`/`socket` not volatile, `out` published mid-handshake → interleaved frames under congestion break Ultravox framing. | `UvoxClient.kt:69,127,145,172` | One write lock; publish `out` after Standby ACK. |
| A-5 | Record OFF→ON within one frame: `armRecord` resets `stopRequested` → old sink never flushed/closed (fd, LAME handle, thread leak, tail lost). Same-shape race in service `stopRecording` outside the monitor. | `AudioPipeline.kt:50,87`; `StreamService.kt:325-339` | Swap via `getAndSet`/lock and close previous sink. |
| A-6 | After capture join timeout (500 ms) the encoder can be flushed/closed while the audio thread still encodes; uncaught exception on `streamy-audio` kills the process. | `AudioCapture.kt:108`, `AudioPipeline.kt:101-103`, `Mp3Encoder.kt` | try/catch around `onFrame`; `@Volatile closed` checked in `flush()`. |
| B-1 | **16 KB page size**: NDK 27 + targetSdk 35 without `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` → `libstreamy_lame.so` *likely* 4 KB-aligned; fails to load on 16 KB devices and is rejected by Play for new uploads. | `build.gradle.kts:13,28`, `cpp/CMakeLists.txt` | Add the CMake arg (or NDK r28+); verify with `zipalign -c -P 16` / `readelf -l`. |

### UI / UX
| # | Finding | Where | Fix |
|---|---|---|---|
| U-2 | Profile form loses edits on rotation / dark-mode switch (`remember(profileId)`). | `EditProfileScreen.kt:65-88` | `rememberSaveable`. |
| U-3 | Double-tap Save inserts duplicate profile and pops back twice. | `EditProfileScreen.kt:322-345`, `StreamyViewModel.kt:301-307` | `isSaving` flag. |
| U-4 | Long profile list pushes "Add profile" to 0 px. | `ServerProfilesScreen.kt:81-105` | `Modifier.weight(1f, fill=false)` on LazyColumn. |
| U-5 | Profiles tab stays usable while Live: can select/delete the on-air profile → Home shows wrong host, `needsRestartToApply` silent, Now-playing hidden and refused. | `ServerProfilesScreen.kt:89`, `HomeScreen.kt:109`, `StreamyViewModel.kt:170,406` | Lock live profile while Live; key metadata on running stream. |
| U-6 | Ending a live show is one unconfirmed tap. | `HomeScreen.kt:267-274` | Confirm dialog / long-press when Live. |
| U-7 | Errors are raw exception text upper-cased ("GAVE UP AFTER 10 ATTEMPTS"); Error state's only action says STOP. | `HomeScreen.kt:229-233` | Map known causes to plain-language + Retry. |
| U-8 | WCAG contrast fails: white on lime-600 `#65A30D` 3.09:1 (14sp buttons); lime-on-lime-tint ~2.67; amber `#D97706` hints 3.19; slate-400 placeholder 2.56. | `Theme.kt`, `JokoUI.kt` | lime-700 `#4D7C0F` (4.99), amber-700/800, slate-500+. |
| U-9 | Forced-light app + default `enableEdgeToEdge()` → white status-bar icons on slate-50 when system is dark. | `MainActivity.kt:103` | `SystemBarStyle.light(...)`. |
| U-10 | On-air state is a small 11sp pill that scrolls away, green "ON AIR" vs red Stop, no `liveRegion` for TalkBack. | `HomeScreen.kt:199-216` | Sticky red on-air bar / nav badge; `liveRegion = Polite`. |
| U-11 | Form a11y/validation: labels/errors not attached to fields, "Required" shown before typing, AAC+AzuraCast silently disables Save while hint says MP3 will be used, host/mount not normalised. | `EditProfileScreen.kt:117-127,306,436-477` | Use `label`/`supportingText`; touched-state errors; coerce instead of disable. |

## 4. P3 (polish / minor)

- **Net:** trailing encoder flush never sent (`AudioPipeline.kt:103` vs `StreamTransport.kt:80`); give-up counter resets after any successful handshake → endless ~1 s loop on immediate-drop servers; Now-playing lost after auto-reconnect (cache + replay title); non-ASCII titles/names sent as `?` and `UvoxClient.kt:166` uses `take()` instead of `WireSafety.truncate`.
- **Audio:** MediaCodec leaked if `configure` throws (`AacEncoder.kt:44`); LAME handle leaked on bad sample rate (`Mp3Encoder.kt:34`); CODEC_CONFIG buffer not filtered; mic-busy teardown self-joins audio thread for 500 ms under the service monitor; `record.stop()` ISE on uninitialised AudioRecord aborts sink flush; negative `nativeEncode` and sink write failures swallowed (full disk truncates silently).
- **Services:** `profile_missing` Error erased by non-preserving teardown; *plausible* orphan notification re-posted after stop (`nm.cancel` at end of teardown); teardown does LAME flush + ContentResolver on main thread; fd/pending stub leaked if `Mp3TranscodeSink` ctor throws (`RecordService.kt:128-137`); stale `_recordingPath` after bail; drop-counter comment at `StreamService.kt:96` wrong.
- **Data/build:** Room migrations 1→5 correct but `exportSchema=false` and no `MigrationTestHelper`; dead `djProtocol IS NULL` clause in 4→5; signing assumes `keyPassword == storePassword`; debug `TEST_PASSWORD` not escaped in `buildConfigField`.
- **UI:** "Saving to:" lingers after stop; mic slider reads 50% to TalkBack at 100%; VU scope has no semantics and afterglow draws current frame; `require()` reachable from a UI event (`StreamyViewModel.kt:274`); pills/rows lack selected semantics; Back + bottom nav duplicated; `MainActivity.kt:71-79` collectors not `repeatOnLifecycle`; no `imePadding` (*plausible* keyboard covers Save); dark scheme unused and its primary is red; no keep-screen-on option.
- **i18n:** `strings.xml` holds only `app_name`; all UI text hardcoded English while the site is Bahasa Indonesia → extract to resources, add `values-in`.
- **Dead code:** `JokoTextField`, `JokoFormSection`, `JokoStat`, `StreamyBackground`, `isRecorderActive`; stale `safeDrawingPadding` comment (`MainActivity.kt:101`).

## 5. Checked and sound

MP3 out-buffer sizing (`1.25n+7200`) and JNI release modes; Ultravox u16 length bounds and
ICY meta-block length byte; XTEA/backoff/status parsing; manifest FGS type + permission;
backup exclusion (`allowBackup=false` + full `dataExtractionRules`); wake/wifi lock release;
distinct notification IDs; Room migration chain (no destructive fallback); lifecycle-aware
flow collection on screens; VU meter isolated recomposition; delete confirmations; 48dp
icon targets with content descriptions.

## 6. Suggested order

1. A-1, S-1 (+S-2), U-1, N-1 — small, high-impact, each testable in isolation.
2. A-2/A-3/A-4 (dead-mic and transcode-sink path) and N-2/N-3 (reconnect correctness).
3. B-1 before the next Play upload.
4. UX batch: U-2…U-7, then contrast/a11y (U-8…U-11) and i18n.
5. Tidy ponytail markers per §1.

---

## 7. Implementation status (2026-10-28, same day)

All findings above were implemented on branch `fix/review-2026-10-28`. Validation on a fresh
SDK (JDK 21, NDK 27.0.12077973): `:app:testDebugUnitTest` **76 tests, 0 failures, 5 env-gated
skips**; `:app:lintDebug` 0 errors; `:app:assembleDebug` OK; `zipalign -c -P 16` OK for every
`lib/*/libstreamy_lame.so`. Not verified on a device or against a live DNAS2/Icecast server.

| Area | Done |
|---|---|
| P1 | A-1 (empty frame → floor dB, pipeline skips it), S-1 (STOP after Error acknowledges → Idle; STOP sent via plain `startService`), U-1 (mic-only gate + Settings deep-link), N-1 (`allowAac = false` everywhere; VM refuses AAC) |
| Services | S-2 (`Failed` → `fail()` → full teardown), S-4 (`RecordingStorage.publishOrphans` at app start, fenced by process-start time), S-5 (`START_NOT_STICKY`, null intent → `stopSelf`), recording arm/disarm under the monitor, `nm.cancel` after teardown, sink-ctor fd leak, path set only after arm, `profile_missing` preserved, session drop counter moved into the transport |
| Audio/net | A-2 (identity end marker), A-3 (single ~1 s dead-mic threshold), A-4 (flush always delivers marker; encoder synchronized), A-5 (pending sink retired on re-arm), A-6 (onFrame guarded, `onFatal`), N-2 (socket published pre-connect + `closed` latch; transport closes on stop-after-connect), N-3 (NAK fatal only before first Live), N-4 (write lock, `out` published after Standby ACK), P3: tail flush to sink only, stable-session backoff reset, now-playing replayed after reconnect, UTF-8 titles/creds, codec/LAME leaks, CODEC_CONFIG filtered, write failures latch `failed` → `recordingFailed` / `record_write_failed` |
| Build/data | B-1 (`ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES`), Room `exportSchema` → `app/schemas/`, dead `IS NULL` clause, optional `keyPassword`, escaped debug `buildConfigField`s, empty keystore path no longer breaks configuration, GitHub Actions CI |
| UI/UX | U-2…U-11 and the UI P3 list (see PR), i18n: all UI text in `strings.xml` + `values-in` (Indonesian copy not yet reviewed by a native speaker) |
| Ponytail | stale VM marker removed, "Task 14" dropped, HE-AACv2 premise reworded, plaintext-password marker added on `ProfileEntity.password` |

Not done: `MigrationTestHelper` test (v1–v4 schemas were never exported, so only future
migrations can be tested); teardown still flushes the standalone recorder's file on the caller
thread (needed so the file is published before the service dies).
