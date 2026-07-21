# Plan: Issue Triage `issue-triage-1`

## Context

- All 37 source issues from `robster7674/glucodroid` were cloned into `GlucoDroid/app` (issues #9–#45) one-for-one with original labels, assignees, state, and attribution markers.
- The `glucodroid` branch of `GlucoDroid/app` has diverged substantially from the legacy `robster7674/glucodroid` source branch:
  - The Juggluco-side feature surface has grown (AiDex, ICanHealth, Outbound/Telegram, Nightscout Follower) while legacy surfaces (`SaveLog` -> zero-byte SAF pipe write, the Mirror menu, Food-entry model, a standalone Juggluco server background service) have been removed or replaced.
  - Several previously-closed "verified" issues (decimal TTS, host name on Add Connection, Nightscout Follower http test, Mirror crash on property change, debug-log contrast) are at high risk of being re-broken by recent changes and should be spot-checked.
- This plan produces a single triage deliverable that lives in a new worktree on a new branch `issue-triage-1` (off `glucodroid`), and post-comment triage notes back to each migrated issue.

## Goal

For each of the 37 migrated issues, determine whether it is **Still Applicable / Already Resolved / Not Applicable (feature removed)** against current `glucodroid` HEAD, post a triage comment to the destination issue, and for issues that are still applicable either:
- (a) record a one-line fix plan in the triage comment, or
- (b) link to an existing fix work (e.g. the `fix/debug-log-share` worktree, the Outbound/Telegram WIP) when one already exists.

## Triage outcome (preliminary — to be confirmed in worktree)

| Dest # | Src # | Title | Verdict | Reason |
|---|---|---|---|---|
| 45 | 52 | food entry must have label or title | Not Applicable — closed | No `Food.kt` in `glucodroid`; food data model removed in upstream merge. Will close with `not planned` and link history. |
| 44 | 51 | Tapping full history makes notes disappear | Still Applicable | Notes/food overlay code still in graph panel; needs verification. |
| 43 | 50 | BLE logging | Still Applicable | Log-sharing infra lives in `fix/debug-log-share` worktree; needs in-app toggle behind Developer Mode. |
| 42 | 49 | week and month view in main graph | Still Applicable | Graph panel is still single-window; compose UI exists. |
| 41 | 34 | Changing speak-readings interval retains old interval | Still Applicable | `DisplayValueResolver` and TTS pipeline exist; cancellation/debounce review needed. |
| 40 | 33 | Allow longer interval for spoken readings | Still Applicable | Same area as #34. |
| 39 | 32 | "Missed reading" repeats every minute | Still Applicable | `AlertRuntimeManager.evaluateMissedReadingLocked` exists. |
| 38 | 31 | 1416 lint warnings across 11 categories | Still Applicable | Run `./gradlew lintMobileLibre3SiDexNogoogleRelease`; capture baseline. |
| 37 | 30 | Security: Implicit intent matches non-exported component | Still Applicable | `getBroadcast`/`PendingIntent` calls in `ICanHealthBleManager`, `AiDexSensor`, `SnoozeManager`. |
| 36 | 29 | Correctness: Missing Permissions (146 instances) | Still Applicable | Lint fix per category. |
| 35 | 28 | Correctness: Invalid format string (164 instances) | Still Applicable | Lint fix per category. |
| 34 | 27 | Test Alert preview sound isn't working in silent mode | Still Applicable | `AlertConfig`/`AlertRuntimeManager` exist. |
| 33 | 26 | Missed Reading alert spoken when disabled | Still Applicable | `AlertRuntimeManager` exists. |
| 32 | 25 | Save log produces zero-byte file | Not Applicable — closed | No `SaveLog`/SAF pipe code in `glucodroid`; UI removed. |
| 31 | 23 | [QA] AiDexHistoryPolicy test failure | Verify-Resolved | Branch has heavily-edited `AiDexSensor.kt`; rerun the test before confirming. |
| 30 | 22 | Test of spoken readings must show stop icon | Still Applicable | TTS test UI area needs review. |
| 29 | 21 | Mirror menu shows Sync: make it consistent | Not Applicable — closed | `Mirror*.kt` removed in upstream merge; UI gone. |
| 28 | 20 | Review and improve debug logging / log sharing | Still Applicable | Log sharing exists in `fix/debug-log-share`; fold under this issue. |
| 27 | 19 | Refactoring (help wanted) | Still Applicable | Meta-issue; tag with `good first issue` for sub-tasks. |
| 26 | 18 | Crash after modifying mirror connection | Verify-Resolved | Mirror logic removed; verify no regression path remains. |
| 25 | 17 | Add hostname to "Add Connection" dialog | Verify-Resolved | Add-connection flow may still exist in Nightscout OutboundApi; spot-check. |
| 24 | 16 | List of voices raw strings | Still Applicable | TTS voice list rendering needs human-readable labels. |
| 23 | 15 | Broadcast on Network cleanup | Still Applicable | Outbound/Telegram code path; review `OutboundApiWorker`. |
| 22 | 14 | Nightscout Follower http connection test | Verify-Resolved | `NightscoutFollowerManager.kt` now has reachability check; spot-check. |
| 21 | 13 | Nightscout Follower stops working | Still Applicable | `NightscoutFollowerManager`; reconnect/back-off review. |
| 20 | 12 | Alert spoken "15 7" instead of "15.7" | Already Resolved — keep closed | `DisplayValueResolver` enforces `Locale.US` decimal — exactly the fix. |
| 19 | 11 | Test Sound playback not working for all alarms | Still Applicable | Alert runtime exists. |
| 18 | 10 | Silent Mode override isn't working for alarms | Still Applicable | Alert runtime exists. |
| 17 | 9 | Silent Mode override isn't working for spoken readings | Still Applicable | TTS pipeline. |
| 16 | 8 | Vertical scaling of graph panel unpredictable | Still Applicable | Graph/Compose scale code exists. |
| 15 | 7 | Debug logging very low contrast | Verify-Resolved | `fix/debug-log-share` worktree already changes log formatting; spot-check. |
| 14 | 6 | App closes each time connection is updated | Still Applicable | Connection update path. |
| 13 | 5 | Background service for jugglucose server | Not Applicable — closed | Juggluco standalone server not in this codebase. |
| 12 | 4 | Schedule for spoken readings / alerts | Still Applicable | `alerts/SnoozeManager.kt` exists; `AlarmManager` scheduling in `AiDex*`, `ICanHealthBleManager`, `SnoozeManager`. |
| 11 | 3 | Speaking of readings isn't working | Verify-Resolved | TTS pipeline present; spot-check. |
| 10 | 2 | Nightscout Follower mode not intuitive | Verify-Resolved | `NightscoutFollowerManager` UI exists; spot-check. |
| 9 | 1 | iCan i6 support | Still Applicable | `drivers/icanhealth/` (Constants, BleManager, Registry, Parser, CeCalibration) all present. |

**Rollup:** 25 Still Applicable, 7 Verify-Resolved, 5 Not Applicable (will close as `not planned`).

## Worktree & branch setup

- Branch: `issue-triage-1`, created off `origin/glucodroid` (the active branch per `CLAUDE.md`).
- Worktree path: `.kilo/worktrees/issue-triage-1` (existing pattern already used by `debug-share` and `veil-piper`).
- Working tree must be clean before checking out the new branch — the parent repo (`glucodroid`) is already clean at commit `20df10a8d` per `git status`.
- After cut, worktree starts at the same commit as `glucodroid`. No build artifacts from the parent worktree are shared.

## Task list

1. **Create worktree and branch**
   - From the main worktree: `git worktree add .kilo/worktrees/issue-triage-1 -b issue-triage-1 origin/glucodroid`.
   - Confirm `git -C .kilo/worktrees/issue-triage-1 rev-parse --abbrev-ref HEAD` returns `issue-triage-1`.
   - Confirm `git -C .kilo/worktrees/issue-triage-1 status` is clean.

2. **Capture current code signals in the worktree** (no source edits — read-only)
   - Run `rg -n 'Food\.|foodLabel|Mirror\.|SaveLog|savelog' Common/src` to confirm absence.
   - Run `rg -n 'TextToSpeech|tts|TTS|VOICE' Common/src` to map TTS code surface.
   - Run `rg -n 'AlertRuntimeManager|MISSED_READING|missedReading' Common/src` for the alert path.
   - Run `rg -n 'SnoozeManager|setExactAndAllowWhileIdle' Common/src` for scheduling.
   - Run `rg -n 'NightscoutFollowerManager|disableFollowerSensor' Common/src` for follower.
   - Run `rg -n 'DisplayValueResolver|TTS_SAFE_LOCALE' Common/src` to confirm #12 fix.
   - Save findings to `docs/issue-triage-2026-07-17.md` inside the worktree (this is the only doc-edit, plus it documents the plan).
   - This file documents the table above with the exact paths/lines that justify each verdict.

3. **Confirm triages still stand** (read-only verification)
   - For each "Verify-Resolved" row (#23, #18, #17, #14, #7, #3, #2), grep for the exact fix code/UI; record the file path & first relevant line in the triage doc.
   - For each "Still Applicable" row, locate the entry point file and capture the line range so the implementation step can start there.

4. **Post a triage comment to each migrated issue** (via `gh issue comment`)
   - One comment per issue, with this body:
     - The verdict (`Still Applicable` / `Verify-Resolved — keeping closed` / `Not Applicable — closing as not planned`).
     - For Still Applicable: 1–2 sentence implementation outline + the verified file path from step 2.
     - For Verify-Resolved: file path + one-line quote of the canonical fix site.
     - For Not Applicable: which feature was removed and a pointer to the commit/merge that did so.
     - Footer: `Triaged 2026-07-17 from worktree \`issue-triage-1\` against \`glucodroid\` @ \`20df10a8d\`.`
   - Use `gh issue comment <num> --repo GlucoDroid/app --body-file <file>`; bodies live in `docs/triage-comments/` so the doc and the comment stay in sync (one file per issue, named `GlucoDroid-app-<dest>.md`).
   - Order: Not-Applicable first (so closures happen early), then Verify-Resolved, then Still Applicable. Close Not-Applicable issues with `gh issue close <num> --repo GlucoDroid/app --reason "not planned"`.

5. **For "Still Applicable" issues only — record fix plans**
   - In the worktree, append to `docs/issue-triage-2026-07-17.md` a "## Fix Plans" section with one numbered subsection per still-applicable issue. Each subsection has:
     - Issue link.
     - Entry-point file path + key class names.
     - 3–6 bullet sketch of the change (no source writes from this plan).
     - Risk / verification approach (e.g. "rerun AiDexHistoryPolicy unit test", "lint diff line count").
   - Issues #31/#29/#28 (large lint categories) get a sub-bullet per lint category.

6. **Open a single tracking PR** off `issue-triage-1` against `glucodroid`
   - `gh pr create --base glucodroid --head issue-triage-1 --title "triage: review 37 migrated issues against current glucodroid" --body-file docs/triage-pr-body.md`.
   - PR body lists the triaged-rollup counts and links the triage doc.
   - Body explicitly opts out of code changes: "No source changes; doc + per-issue comments only."
   - Do NOT auto-merge; wait for review (per `CLAUDE.md`, merges go through a squash PR + manual `gh pr merge`).

## Affected boundaries

- New worktree only (`.kilo/worktrees/issue-triage-1`), entirely read-only with respect to source code.
- New local branch `issue-triage-1` only. No edits to `glucodroid`, no edits to the existing `fix/debug-log-share` or `fix/outbound-telegram-1` worktrees.
- Documentation file `docs/issue-triage-2026-07-17.md` and triage-comment bodies under `docs/triage-comments/` (both inside the new worktree — not in the main worktree).
- 37 issue comments on `GlucoDroid/app`; 5 of them closed as `not planned`.

## Risks

- Risk: `feature/juggluco-server` was reintroduced or partially reintroduced since the upstream merge — counter-checked by step 2's grep of `SaveLog|savelog|JugglucoseService|jugglucoserver`.
- Risk: Source issues 4–5 had lightweight comments documenting expected behaviour that may not match the new TTS / schedule code; the one-line fix plan must surface any divergence.
- Risk: `Mirror*.kt` may have re-entered `glucodroid` via a recent merge — counter-checked with `rg -l 'Mirror' Common/src`.
- Risk: Closed issues labeled `verified` (e.g. #12) can regress. The triage doc records the canonical fix site so a regression would surface during a future lint/QA pass.

## Validation

- `git -C .kilo/worktrees/issue-triage-1 status` reports clean after step 1 and after step 5.
- `docs/issue-triage-2026-07-17.md` exists, and the 37 verdicts match the table above.
- `gh issue list --repo GlucoDroid/app --state all --json number,state` shows:
  - 5 issues moved to CLOSED with reason `not planned` (#45/#32/#29/#13 — note #13 numberings differ between source/destination; use destination numbers).
  - 32 issues still in OPEN state, each with one new comment from this triage pass.
- PR `issue-triage-1` is open and references the triage doc; merge is left to the user (per `CLAUDE.md`).
- Re-run `gh pr checks <pr-number>` to confirm no CI is broken (the PR only adds docs, so it should be green).

## Out of scope

- Implementing any fix plans listed under "Still Applicable". The PR is triage-only; fixes happen in follow-up PRs off `glucodroid`.
- Re-cloning issues or modifying GitHub labels beyond what the migration script already produced.
- Editing the legacy `upstream/feat/sustained-low-alert` branch (covered by the previously-saved cleanup plan).
