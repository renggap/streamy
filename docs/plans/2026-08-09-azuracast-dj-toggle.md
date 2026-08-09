# AzuraCast DJ protocol toggle — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the flaky AUTO DJ-protocol sniff with an explicit "AzuraCast server" checkbox (Ultravox default, Icecast when checked).

**Architecture:** One field (`ProfileEntity.djProtocol`) now holds two values (`ULTRAVOX`/`ICECAST`); the UI checkbox writes one or the other. The `ICECAST` transport path already exists and is live-verified, so this is deletion (detector + AUTO) + a data migration to normalize old AUTO rows + a UI checkbox. Design rationale: `docs/plans/2026-08-09-azuracast-dj-toggle-design.md`.

**Tech Stack:** Kotlin, Jetpack Compose, Room (v4→v5), JUnit.

---

### Task 1: Centralize DJ-protocol parse + drop AUTO from the enum

**Files:**
- Modify: `app/src/main/java/com/streamy/app/data/Models.kt:12-17`
- Create test: `app/src/test/java/com/streamy/app/data/DjProtocolParseTest.kt`

**Step 1: Write the failing test.**

```kotlin
package com.streamy.app.data

import org.junit.Test
import kotlin.test.assertEquals

class DjProtocolParseTest {
    @Test fun parses_ultravox() = assertEquals(DjProtocol.ULTRAVOX, DjProtocol.parse("ULTRAVOX"))
    @Test fun parses_icecast() = assertEquals(DjProtocol.ICECAST, DjProtocol.parse("ICECAST"))
    @Test fun case_insensitive() = assertEquals(DjProtocol.ICECAST, DjProtocol.parse("icecast"))
    @Test fun old_auto_normalizes_to_ultravox() = assertEquals(DjProtocol.ULTRAVOX, DjProtocol.parse("AUTO"))
    @Test fun blank_defaults_ultravox() = assertEquals(DjProtocol.ULTRAVOX, DjProtocol.parse(""))
    @Test fun garbage_defaults_ultravox() = assertEquals(DjProtocol.ULTRAVOX, DjProtocol.parse("nope"))
}
```

**Step 2: Run, expect FAIL** (`DjProtocol.parse` unresolved).

Run: `./gradlew :app:testDebugUnitTest --tests "com.streamy.app.data.DjProtocolParseTest"`

**Step 3: Edit `Models.kt`.** Replace L12-17:

```kotlin
/**
 * DJ sub-protocol (only meaningful when [BroadcastMode] is DJ). ULTRAVOX = sc_trans / sc_serv
 * (binary Ultravox, djlogin in the auth UID). ICECAST = Icecast / AzuraCast / Liquidsoap harbor
 * HTTP source (`SOURCE <mount> ICE/1.0` + HTTP Basic auth). Selected explicitly per profile.
 */
enum class DjProtocol { ULTRAVOX, ICECAST } {
    /** Parse a stored value; any unrecognized/blank/legacy-AUTO string → [ULTRAVOX] (the default). */
    fun toStored(): String = name
    companion object {
        fun parse(s: String): DjProtocol =
            runCatching { valueOf(s.trim().uppercase()) }.getOrDefault(ULTRAVOX)
    }
}
```

**Step 4: Run, expect PASS.**

---

### Task 2: ProfileEntity default → ULTRAVOX

**Files:** Modify `app/src/main/java/com/streamy/app/data/db/ProfileEntity.kt:24`

Change:
```kotlin
    val djProtocol: String = "AUTO",   // DJ sub-protocol: AUTO | ULTRAVOX | ICECAST
```
to:
```kotlin
    val djProtocol: String = "ULTRAVOX",   // DJ sub-protocol: ULTRAVOX (sc_trans) | ICECAST (AzuraCast/Icecast)
```

---

### Task 3: Room migration v4 → v5 (normalize AUTO → ULTRAVOX)

**Files:** Modify `app/src/main/java/com/streamy/app/data/db/AppDatabase.kt:8,46`

Bump version and append migration:
```kotlin
@Database(entities = [ProfileEntity::class], version = 5, exportSchema = false)
```
After `MIGRATION_3_4` (before the closing `}` of the companion at L47):
```kotlin
        /**
         * v4 → v5: retire AUTO DJ-protocol sniff. Normalize any AUTO (or null) rows to ULTRAVOX
         * so they keep working under the explicit protocol model. No schema change — the column
         * already exists from v3→v4.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE profiles SET djProtocol='ULTRAVOX' WHERE djProtocol='AUTO' OR djProtocol IS NULL")
            }
        }
```

---

### Task 4: StreamService — register migration, inline parse, drop AUTO arm

**Files:** Modify `app/src/main/java/com/streamy/app/service/StreamService.kt:92,186-216,343-354`

- L92 — add `MIGRATION_4_5`:
```kotlin
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
```
- L186-188 comment — update to remove AUTO mention:
```kotlin
                // Resolve the source client. SOURCE → Ultravox to DNAS. DJ → Ultravox (sc_trans)
                // OR Icecast HTTP source (AzuraCast / Icecast / Liquidsoap harbor), per the profile's
                // explicit djProtocol.
```
- L199-200 — drop the AUTO arm; the `when` now switches on `DjProtocol.parse(...)`:
```kotlin
                    when (DjProtocol.parse(profile.djProtocol)) {
                        DjProtocol.ULTRAVOX -> UvoxClient(
```
  (Delete the line `DjProtocol.AUTO -> error("AUTO resolved by resolveDjProtocol")  // unreachable`.)
- L343-354 — delete `resolveDjProtocol` entirely (parse is now inlined at L199; the `djPort` arg was only for the deleted detector).

---

### Task 5: StreamyViewModel — register migration, drop invalidate

**Files:** Modify `app/src/main/java/com/streamy/app/ui/StreamyViewModel.kt:53,290-294`

- L53 — add `MIGRATION_4_5`:
```kotlin
    ).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5).build()
```
- L290-294 — delete the invalidate block:
```kotlin
            // DJ protocol was possibly re-sniffed; drop the cached detection so the next AUTO
            // connect re-probes the (possibly changed) server.
            if (p.mode.equals("DJ", ignoreCase = true)) {
                com.streamy.app.net.DjProtocolDetector.invalidate(p.host, p.djPort ?: p.port)
            }
```
  Leave `saveProfile`'s save/select/message logic intact.

---

### Task 6: EditProfileScreen — the AzuraCast checkbox

**Files:** Modify `app/src/main/java/com/streamy/app/ui/EditProfileScreen.kt` (state ~L79, load ~L109, UI L184-198, save L335) + imports.

- Add state (beside L79 `djMount`):
```kotlin
    var isAzuracast by remember(profileId) { mutableStateOf(false) }
```
- In `LaunchedEffect` (after L109 `djMount = ...`):
```kotlin
                isAzuracast = p.djProtocol.trim().equals("ICECAST", ignoreCase = true)
```
- Replace the L184-198 `if (isDj) { Text(...); LabeledTextField(DJ mount) }` block with:
```kotlin
            if (isDj) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isAzuracast, onCheckedChange = { isAzuracast = it })
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("AzuraCast server", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Uses Icecast DJ protocol instead of SHOUTcast Ultravox. " +
                                "Leave unchecked for sc_trans / DNAS.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                LabeledTextField(
                    label = "DJ mount",
                    value = djMount,
                    onValueChange = { djMount = it },
                    placeholder = "/",
                    keyboardType = KeyboardType.Uri,
                )
            }
```
- L335 — write the chosen protocol:
```kotlin
                        djProtocol = if (isDj && isAzuracast) DjProtocol.ICECAST.name else DjProtocol.ULTRAVOX.name,
```
- Imports — add as needed: `androidx.compose.foundation.layout.Row`, `androidx.compose.foundation.layout.Column`, `androidx.compose.foundation.layout.Arrangement` (Row/Arrangement already used at L214 — verify), `androidx.compose.ui.Alignment`, `androidx.compose.material3.Checkbox`. `DjProtocol` import already present (L335 used `DjProtocol.AUTO`).

---

### Task 7: Delete the detector + its tests

**Files (delete):**
- `app/src/main/java/com/streamy/app/net/DjProtocolDetector.kt`
- `app/src/test/java/com/streamy/app/net/DjProtocolDetectorTest.kt`
- `app/src/test/java/com/streamy/app/net/DjProtocolProbeTest.kt`

Confirm no remaining references: `grep -rn "DjProtocolDetector\|DjProtocol.AUTO\|DjProtocol\.AUTO" app/src` should return nothing.

---

### Task 8: Build + unit-test gate

Run:
```
./gradlew :app:assembleDebug :app:testDebugUnitTest
```
Expected: GREEN. `DjProtocolParseTest` passes; no compile references to deleted symbols; Room v5 migration registered in both DB builders.

If `assembleDebug` fails on `Column`/`Row` name clashes (Compose has many `Column`), qualify or fix imports. If migration test framework complains, it is not configured here — rely on the data-only UPDATE + `exportSchema=false`.

No instrumented test added (Icecast transport already live-verified; the migration is a single UPDATE).
