# 1.0.4-Alpha Rebase: Conflict Handling Strategy

**Baseline:** glucodroid @ 2e26bfeb0 (v0.3.0.6-alpha)  
**Target:** upstream/1.0.4-Alpha  
**Expected conflicts:** ~120 (add/add due to 3-branch merge structure)  
**Strategy:** Manual 3-way merge for Type C, selective merge for Type A/B

---

## Conflict Categories

### Type A: Upstream-Only (TAKE UPSTREAM)

These are new features or improvements in 1.0.4 that don't conflict with local patches.

```
Action: git checkout --theirs <file>
```

**New files (new upstream features):**
- `Common/src/main/cpp/exchangetrend.hpp` — new trend velocity calculation
- `Common/src/main/java/tk/glucodata/BroadcastTrendRate.java` — trend broadcast helper (new)
- `Common/src/main/java/tk/glucodata/GlucoseDelta.java` — delta calculation (new)
- `Common/src/main/java/tk/glucodata/IobCobRisk.java` — IOB/COB risk model (new)
- `Common/src/main/java/tk/glucodata/JournalIobAccess.java` — journal access (new)
- `Common/src/main/java/tk/glucodata/TrendArrowAngle.java` — arrow angle calculation (new)
- `Common/src/main/java/tk/glucodata/TrendProjection.java` — trend projection (new)
- `Common/src/main/java/tk/glucodata/TrendVelocityProvider.kt` — velocity provider (new)
- `Common/src/main/java/tk/glucodata/alerts/AlertDisplayText.kt` — alert text formatting (new)
- `Common/src/main/java/tk/glucodata/alerts/DeltaAlarmState.kt` — delta alarm state (new)

**Modified files (upstream-only changes):**
- `Common/build.gradle` — version/dependency updates (check for our package name / app_name)
- `Common/proguard-rules.my` — obfuscation rules (check for new rules)
- `Common/src/main/cpp/SensorGlucoseData.hpp` — data structure updates
- `Common/src/main/cpp/g.cpp` — core glucose processing
- `Common/src/main/cpp/net/watchserver/common.hpp` — watchserver updates
- `Common/src/main/cpp/net/watchserver/iob.cpp` — IOB calculation
- `Common/src/main/cpp/net/watchserver/uploader.cpp` — uploader logic
- `Common/src/main/cpp/net/watchserver/watchserver.cpp` — watchserver core
- `Common/src/main/cpp/sensoren.hpp` — sensor definitions

**Pattern:** If upstream changes are isolated to new data structures or calculations, take upstream.

---

### Type B: Local-Only (KEEP LOCAL)

These are GlucoDroid-specific branding, documentation, or configurations.

```
Action: git checkout --ours <file>
```

**Files (always keep local):**
- `README.md` — GlucoDroid fork intro + glucodroid.cloud reference
- `docs/outbound-api.md` — GlucoDroid outbound API documentation (PR #57)
- `Common/build.gradle` — MUST verify `applicationId "cloud.glucodroid"` and GlucoDroid app_name in all release build types
- `Common/src/main/AndroidManifest.xml` — Check for GlucoDroid-specific permissions/metadata

**Strings (merge carefully — see below):**
- `Common/src/main/res/values/strings.xml` — GlucoDroid strings + upstream translations
- `Common/src/main/res/values-*/strings.xml` — Locale-specific

**Pattern:** If upstream changes strings we've added (like `about_cloud`, `nightscout_*`, `outbound_api_preset_glucodroid_*`), merge manually to keep both.

---

### Type C: True Merges (MANUAL 3-WAY)

These files have **both** upstream changes AND local patches. Requires manual review and merge.

#### **CRITICAL — Very High Priority**

| File | Local Patches | Upstream Changes | Risk Level |
|---|---|---|---|
| `ICanHealthBleManager.kt` | i6 firmware support (PR none yet) | Hardening (commit 739ec11) | 🔴 CRITICAL |

**Strategy for iCan:**
1. Get 3 versions:
   - Base (1.0.3-Alpha): `git show 1.0.3-Alpha:...ICanHealthBleManager.kt`
   - Ours (HEAD): current
   - Theirs (1.0.4-Alpha): upstream new
2. **DO NOT auto-take upstream** — 1.0.3 rebase broke i6, don't repeat
3. Merge upstream's new identity hardening ONLY if it doesn't re-break i6
4. Preserve:
   - Device name matching (iCGM-, Sinocare CGM, ICN- prefixes)
   - Serial resolution logic (DIS serial → SerialNumber)
   - No strict prefix-matching that excludes i6 (01OR03MS00070101 vs ZA1OR03MSE50)
5. Test: Pair i6 sensor after merge, verify connection & readings work
6. **Reference:** See `rebase/i6-identity-fix-reference.txt` (saved from commit 60742b797)

#### **Alert & Notification System — High Priority**

| File | Local Patches | Upstream Changes | Risk Level |
|---|---|---|---|
| `Notify.java` | Multi-sensor refresh (dataChangedGlucoseRefreshRunnable) | Sound delay (soundDelaySeconds, delayedSoundSchedule), vibration hardening, AlertDisplayText | 🟠 HIGH |
| `AlertRuntimeManager.kt` | Local patches | Sensor expiry pre-warnings, delta alarms (DeltaAlarmState) | 🟠 HIGH |
| `AlertVibrationPattern.java` | None | Vibration hardening (spans sound delay, not just alarm) | 🟡 MEDIUM |

**Strategy for Notify.java:**
1. Preserve:
   - `dataChangedGlucoseRefreshRunnable` loop (multi-sensor refresh)
   - Local alarm restart logic
   - HandlerThread + Handler for notifications
2. Merge in:
   - `soundDelaySeconds` + `delayedSoundSchedule` (upstream audio delay)
   - New vibration pattern logic from AlertVibrationPattern
   - AlertDisplayText integration
3. Verify:
   - No duplicate function declarations (recordReadingArrived, etc.)
   - Compile successful
   - Test: Trigger a LOW/HIGH alarm, verify sound/vibration timing

**Strategy for AlertRuntimeManager.kt:**
1. Preserve:
   - Local patch logic
2. Merge in:
   - SensorExpiryAlertState (new upstream alert type)
   - DeltaAlarmState (new upstream alert type)
3. Verify:
   - Compile successful
   - Test: Verify existing LOW/HIGH alerts still work
   - Test: Verify new sensor-expiry alerts appear if applicable

#### **Outbound API & Telegram — High Priority**

| File | Local Patches | Upstream Changes | Risk Level |
|---|---|---|---|
| `OutboundApiSettings.kt` | Emoji arrows template (DEFAULT_CHAT_TEMPLATE_EMOJI_ARROWS), version migration, refreshInPlaceEnabled/refreshWindowMinutes | (unknown — audit first) | 🟠 HIGH |
| `OutboundApiSettingsScreen.kt` | BubbleRefreshSection, Telegram bubble refresh (PR #57), expandedId initial state | UI refactoring | 🟠 HIGH |

**Strategy:**
1. Audit upstream changes first: `git diff 1.0.3-Alpha..1.0.4-Alpha -- OutboundApiSettings.kt`
2. If upstream changes the template system:
   - Preserve local emoji arrows migration
   - Merge upstream template changes carefully
3. If upstream doesn't touch templates:
   - Take upstream, merge local constants on top
4. For OutboundApiSettingsScreen.kt:
   - Preserve BubbleRefreshSection + Telegram logic
   - Merge UI refactoring around it
5. Verify:
   - Compile successful
   - Test: Send Telegram message with emoji arrows, verify formatting

#### **Nightscout Integration — High Priority**

| File | Local Patches | Upstream Changes | Risk Level |
|---|---|---|---|
| `NightscoutFollowerManager.kt` | mirrorToNative guard (PR #45 — CRITICAL), 3-phase test connection | (unknown — audit first) | 🟠 HIGH |
| `NightscoutSettingsScreen.kt` | 3-phase test connection (server reachability, entries with auth, upload status), OFF mode enum | (unknown — audit first) | 🟠 HIGH |

**Strategy:**
1. Audit upstream changes: `git diff 1.0.3-Alpha..1.0.4-Alpha -- NightscoutFollowerManager.kt`
2. **DO NOT LOSE** `mirrorToNative()` and `nativeMirroredUpToMs` guard
   - This prevents follower from overwriting native sensor DB
   - Silent loss = Nightscout follower writes junk to native DB
3. Merge upstream changes carefully
4. Verify:
   - Compile successful
   - Test: Enable Nightscout follower, verify BG reads from Nightscout
   - Test: Verify native DB not overwritten

#### **Display & Locale — Medium Priority**

| File | Local Patches | Upstream Changes | Risk Level |
|---|---|---|---|
| `DisplayValueResolver.kt` | Locale.US for TTS (de/fr: "15.7" not "15,7") | (unknown — audit first) | 🟡 MEDIUM |
| `DebugSettingsScreen.kt` | LogSanitizer (clipboard, streaming sanitization, PR #35) | (unknown — audit first) | 🟡 MEDIUM |
| `Talker.java` | Audio focus, TTS improvements (PR #41), ttsWakeLock from 1.0.3 | (unknown — audit first) | 🟡 MEDIUM |

**Strategy:**
1. Preserve local patches
2. Merge upstream changes around them
3. Verify:
   - DisplayValueResolver: TTS reads "15 point 7" not "15,7" in de/fr locales
   - DebugSettingsScreen: Log export still sanitized
   - Talker: TTS still speaks BG values with audio focus

#### **Sensor & Driver Logic — Medium Priority**

| File | Local Patches | Upstream Changes | Risk Level |
|---|---|---|---|
| `SuperGattCallback.java` | (audit needed) | BT recovery, iCan matching, uploader battery broadcast | 🟡 MEDIUM |
| `SensorBluetooth.java` | BT recovery logic (from 1.0.3?) | Managed sensor lifecycle, Sibionics improvements | 🟡 MEDIUM |
| `RealtimeReadingPolicy.java` | (audit needed) | (unknown) | 🟡 MEDIUM |
| `TrendAccess.kt` | (audit needed) | Trend velocity provider integration | 🟡 MEDIUM |

**Strategy:**
1. Merge carefully to avoid losing local recovery logic
2. Verify:
   - BT toggle recovery still works (disable/enable BT, sensor reconnects)
   - Compile successful

#### **Strings Merge Strategy (All Locales)**

**Files:** `values/strings.xml`, `values-de/strings.xml`, `values-fr/strings.xml`, etc.

**Approach:** Automated 3-way merge (see script below)

**Why:** 120+ string entries, manual merge is error-prone. Automated approach:
1. Extract base (1.0.3), ours (HEAD), theirs (1.0.4)
2. Merge with conflict resolution priority: ours (GlucoDroid) > theirs (upstream) > base (1.0.3)
3. Verify no duplicates, all GlucoDroid strings present

**Script** (provided in Phase 2):
```python
# Merge strings 3-way: take local additions, merge upstream additions
# Result: ~200-230 strings (1.0.3 baseline + GlucoDroid + 1.0.4 additions)
```

---

## Audit Checklist (Before Merging Each File)

For each Type C conflict, verify:

- [ ] **Base version** (1.0.3-Alpha) obtained: `git show 1.0.3-Alpha:...`
- [ ] **Local version** (HEAD) examined: what did we add?
- [ ] **Upstream version** (1.0.4-Alpha) examined: what changed?
- [ ] **3-way merge** completed: local + upstream combined
- [ ] **No duplicate declarations** (grep for function/class names)
- [ ] **No marker strings left** (<<<<<<, ======, >>>>>>)
- [ ] **Compile test** passes: `./gradlew compileKotlin`
- [ ] **File compiles** individually (if Kotlin): `kotlinc <file>`

---

## If Auto-Resolution Detected → ABORT & Fallback

If git reports:
```
Auto-merging <file>
CONFLICT (content): Merge conflict in <file>
```

**For >50 files auto-merged with no conflict listing:**
1. `git merge --abort`
2. Fall back to Phase 2C (manual cherry-pick strategy)
3. This is slower but prevents silent patch loss

---

## Reference Commits

For context during conflict resolution:

| Commit | Purpose |
|---|---|
| `c100893c7` | Initial 1.0.3 rebase (what went wrong) |
| `695311364` | Emergency patch restore (what we fixed) |
| `60742b797` | iCan i6 regression fix (what to watch for 1.0.4) |
| `87574e093` | Pre-rebase i6 firmware fix (baseline) |

Access with: `git show <sha1>:<file>`

---

## Post-Merge Verification

After all conflicts resolved:

1. **Compile:** `./gradlew compileKotlin compileJava --scan`
2. **Lint:** `./gradlew lintAnalyzeDebug`
3. **Tests:** `./gradlew test --scan`
4. **APK build:** `./gradlew assembleMobileLibre3SiDexNogoogleRelease -Pno_x86 -Pno_x86_64`
5. **Patch verification** (grep for critical markers — see Phase 2E in main plan)
6. **Regression checklist** (see main plan Phase 5)

---

## Questions During Merge?

Refer to:
- Main rebase plan (Phase 2-7)
- Audit findings (AUDIT_FINDINGS.md)
- Commit history (see "Reference Commits" above)
