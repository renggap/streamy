# AzuraCast DJ protocol toggle — design

Date: 2026-08-09. Status: approved.

## Problem

DJ login to AzuraCast is unreliable. AzuraCast accepts DJ feeds over the
**Icecast HTTP source** protocol, even when it relays to a SHOUTcast DNAS for
listeners. Streamy's DJ mode currently runs `djProtocol = AUTO`, which probes
Ultravox `0x1009` then Icecast SOURCE at connect (`DjProtocolDetector`). That
sniff is flaky and gives the user no manual override — the profile editor
hardcodes `AUTO` on every save (`EditProfileScreen.kt:335`), so there is no UI
to force Icecast.

## Decision

Surface a single **"AzuraCast server" checkbox** in the DJ profile. Binary:

- Unchecked → `ULTRAVOX` (SHOUTcast / sc_trans — the default).
- Checked → `ICECAST` (AzuraCast speaks Icecast HTTP source for DJ).

"AzuraCast" is the user-facing label for the existing `ICECAST` protocol value.
No new boolean column — that would duplicate `djProtocol` as a second source of
truth. The Icecast transport (`IcecastSourceClient`) is already live-verified
against an AzuraCast host; reuse `djMount` / `djUser` / `password`. **Zero
transport code changes.**

Per the user's choice, the AUTO auto-detect is **removed entirely** (it was the
likely cause of the login trouble and the manual checkbox replaces its purpose).

## Data migration (DB v4 → v5)

Removing `AUTO` from the enum means old rows storing `'AUTO'` would crash
`valueOf()`. Data-only migration (no schema change — column already exists):

```kotlin
MIGRATION_4_5: UPDATE profiles SET djProtocol='ULTRAVOX'
               WHERE djProtocol='AUTO' OR djProtocol IS NULL
```

`@Database(version = 5)`. `ProfileEntity.djProtocol` default flips
`"AUTO"` → `"ULTRAVOX"`.

## Code changes

| File | Change |
|---|---|
| `data/Models.kt` | `enum class DjProtocol { ULTRAVOX, ICECAST }` (drop `AUTO`) |
| `data/db/ProfileEntity.kt` | default `djProtocol = "ULTRAVOX"` |
| `data/db/AppDatabase.kt` | `version = 5`; add `MIGRATION_4_5` (data-only UPDATE) |
| `service/StreamService.kt` | register migration in both builders; drop `AUTO` arm in the `when`; `resolveDjProtocol` becomes safe-parse `?: ULTRAVOX` (no detector call) |
| `ui/StreamyViewModel.kt` | register migration; delete `DjProtocolDetector.invalidate(...)` call in `saveProfile` |
| `ui/EditProfileScreen.kt` | seed `djProtocol` state from `p.djProtocol`; add checkbox in `if (isDj)` block; write `ULTRAVOX`/`ICECAST` on save (stop hardcoding `AUTO`) |
| `net/DjProtocolDetector.kt` | **delete** |
| `DjProtocolProbeTest.kt` | **delete** (subject gone) |

## UI

Inside the `if (isDj)` block, beside DJ mount / DJ user: a Compose `Checkbox`
row — "AzuraCast server" + helper "Uses Icecast DJ protocol instead of
SHOUTcast Ultravox." Only in DJ mode (SOURCE mode is always Ultravox to DNAS).

## Safety / testing

`resolveDjProtocol` returns `ULTRAVOX` for any unrecognized stored value, so a
row escaping migration cannot crash connect. One unit test on
`resolveDjProtocol`: `"ICECAST" → ICECAST`, `"ULTRAVOX" → ULTRAVOX`,
garbage/blank → `ULTRAVOX`. No new live-server test (Icecast path covered).

## Out of scope (YAGNI)

AzuraCast admin API, station-URL field, per-codec AzuraCast quirks. Add only if
a real connect failure points at them.
