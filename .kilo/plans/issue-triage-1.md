# Plan: Issue Triage `issue-triage-1`

## Context

- All 37 source issues from `robster7674/glucodroid` were cloned into `GlucoDroid/app` (destination issues #9–#45) one-for-one with original labels, assignees, state, and attribution markers.
- The `glucodroid` branch of `GlucoDroid/app` has diverged substantially from the legacy `robster7674/glucodroid` source branch:
  - The Juggluco-side feature surface has grown (AiDex, ICanHealth, Outbound/Telegram, Nightscout Follower) while legacy surfaces (`SaveLog` zero-byte SAF pipe write, the Mirror menu, Food-entry model, a standalone Juggluco server background service) have been removed or replaced.
  - Several previously-closed "verified" issues (decimal TTS, host name on Add Connection, Nightscout Follower http test, Mirror crash on property change, debug-log contrast) are at risk of regressing after recent changes and should be spot-checked.
- This plan produces a single triage deliverable that lives in a new worktree on a new branch `issue-triage-1` (off `glucodroid`), and post-comment triage notes back to each migrated issue.

## Goal

For each of the 37 migrated issues, determine whether it is **Still Applicable / Verify-Resolved / Not Applicable (feature removed)** against current `glucodroid` HEAD, post a triage comment to the destination issue, and for issues that are still applicable either (a) record a one-line fix plan in the triage comment, or (b) link to an existing fix work (e.g. the `fix/debug-log-share` worktree, the Outbound/Telegram WIP) when one already exists.

## Triage outcome (preliminary)

| Dest # | Src # | Title | Verdict | Reason |
|---|---|---|---|---|
| 45 | 52 | food entry must have label or title | Not Applicable — close | No `Food.kt` in `glucodroid`; food data model removed in upstream merge. |
| 44 | 51 | Tapping full history makes notes disappear | Still Applicable | Notes/food overlay code still in graph panel. |
| 43 | 50 | BLE logging | Still Applicable | Log-sharing infra lives in `fix/debug-log-share` worktree; needs in-app toggle behind Developer Mode. |
| 42 | 49 | week and month view in main graph | Still Applicable | Graph panel is still single-window; compose UI exists. |
| 41 | 34 | Changing speak-readings interval retains old interval | Still Applicable | `DisplayValueResolver` and TTS pipeline exist; cancellation/debounce review needed. |
| 40 | 33 | Allow longer interval for spoken readings | Still Applicable | Same area as #34. |
| 39 | 32 | "Missed reading" repeats every minute | Still Applicable | `AlertRuntimeManager.evaluateMissedReadingLocked` exists. |
| 38 | 31 | 1416 lint warnings across 11 categories | Still Applicable | Run `./gradlew lintMobileLibre3SiDexNogoogleRelease`. |
| 37 | 30 | Security: Implicit intent matches non-exported component | Still Applicable | `getBroadcast`/`PendingIntent` calls in `ICanHealthBleManager`, `AiDexSensor`, `SnoozeManager`. |
| 36 | 29 | Correctness: Missing Permissions (146 instances) | Still Applicable | Lint fix per category. |
| 35 | 28 | Correctness: Invalid format string (164 instances) | Still Applicable | Lint fix per category. |
| 34 | 27 | Test Alert preview sound isn't working in silent mode | Still Applicable | `AlertConfig`/`AlertRuntimeManager` exist. |
| 33 | 26 | Missed Reading alert spoken when disabled | Still Applicable | `AlertRuntimeManager` exists. |
| 32 | 25 | Save log produces zero-byte file | Not Applicable — close | No `SaveLog`/SAF pipe code in `glucodroid`; UI removed. |
| 31 | 23 | [QA] AiDexHistoryPolicy test failure | Verify-Resolved | Branch has heavily-edited `AiDexSensor.kt`; rerun the test to confirm. |
| 30 | 22 | Test of spoken readings must show stop icon | Still Applicable | TTS test UI area needs review. |
| 29 | 21 | Mirror menu shows Sync: make it consistent | Not Applicable — close | `Mirror*.kt` removed in upstream merge. |
| 28 | 20 | Review and improve debug logging / log sharing | Still Applicable | Log sharing exists in `fix/debug-log-share`. |
| 27 | 19 | Refactoring (help wanted) | Still Applicable | Meta-issue; sub-task candidates. |
| 26 | 18 | Crash after modifying mirror connection | Verify-Resolved | Mirror logic removed; confirm no regression path. |
| 25 | 17 | Add hostname to "Add Connection" dialog | Verify-Resolved | Add-connection flow may still exist in Nightscout/OutboundApi. |
| 24 | 16 | List of voices raw strings | Still Applicable | TTS voice list rendering needs human-readable labels. |
| 23 | 15 | Broadcast on Network cleanup | Still Applicable | Outbound/Telegram code path; review `OutboundApiWorker`. |
| 22 | 14 | Nightscout Follower http connection test | Verify-Resolved | `NightscoutFollowerManager.kt` has reachability check. |
| 21 | 13 | Nightscout Follower stops working | Still Applicable | `NightscoutFollowerManager`; reconnect/back-off review. |
| 20 | 12 | Alert spoken "15 7" instead of "15.7" | Verify-Resolved — keep closed | `DisplayValueResolver` enforces `Locale.US` decimal — exactly the fix. |
| 19 | 11 | Test Sound playback not working for all alarms | Still Applicable | Alert runtime exists. |
| 18 | 10 | Silent Mode override isn't working for alarms | Still Applicable | Alert runtime exists. |
| 17 | 9 | Silent Mode override isn't working for spoken readings | Still Applicable | TTS pipeline. |
| 16 | 8 | Vertical scaling of graph panel unpredictable | Still Applicable | Graph/Compose scale code exists. |
| 15 | 7 | Debug logging very low contrast | Verify-Resolved | `fix/debug-log-share` already changes log formatting. |
| 14 | 6 | App closes each time connection is updated | Still Applicable | Connection update path. |
| 13 | 5 | Background service for jugglucose server | Not Applicable — close | Juggluco standalone server not in this codebase. |
| 12 | 4 | Schedule for spoken readings / alerts | Still Applicable | `alerts/SnoozeManager.kt`; `AlarmManager` in `AiDex*`, `ICanHealthBleManager`, `SnoozeManager`. |
| 11 | 3 | Speaking of readings isn't working | Verify-Resolved | TTS pipeline present. |
| 10 | 2 | Nightscout Follower mode not intuitive | Verify-Resolved | `NightscoutFollowerManager` UI exists. |
| 9 | 1 | iCan i6 support | Still Applicable | `drivers/icanhealth/` (Constants, BleManager, Registry, Parser, CeCalibration) all present. |

Rollup: **25 Still Applicable, 7 Verify-Resolved (keep closed), 5 Not Applicable (close as `not planned`).**

## Worktree & branch setup

- Branch: `issue-triage-1`, off `origin/glucodroid` (the active branch per `CLAUDE.md`).
- Worktree path: `.kilo/worktrees/issue-triage-1` (matches the pattern of `debug-share`, `veil-piper`).
- Parent worktree is clean at commit `20df10a8d` per `git status`; no build artifacts need sharing.

## Task list

1. **Rename the plan file** (user requested). Run `mv .kilo/plans/1784207482543-feat-sustained-low-alert-cleanup.md .kilo/plans/issue-triage-1.md` in the main worktree before creating the issue-triage-1 worktree.

2. **Create worktree and branch.** From the main worktree: `git worktree add .kilo/worktrees/issue-triage-1 -b issue-triage-1 origin/glucodroid`. Verify `git -C .kilo/worktrees/issue-triage-1 status` is clean and `rev-parse --abbrev-ref HEAD` returns `issue-triage-1`.

3. **Capture current code signals in the worktree** (read-only). Run the following rg queries and record findings in `docs/issue-triage-2026-07-17.md` (the only doc written):
   - `rg -n 'Food\.|foodLabel|Mirror\.|SaveLog|savelog' Common/src` — confirms feature absences.
   - `rg -n 'TextToSpeech|tts|TTS|VOICE' Common/src` — TTS surface.
   - `rg -n 'AlertRuntimeManager|MISSED_READING|missedReading' Common/src` — alert path.
   - `rg -n 'SnoozeManager|setExactAndAllowWhileIdle' Common/src` — scheduling.
   - `rg -n 'NightscoutFollowerManager|disableFollowerSensor' Common/src` — follower.
   - `rg -n 'DisplayValueResolver|TTS_SAFE_LOCALE' Common/src` — confirms #12 fix.
   - `rg -n 'JugglucoseService|jugglucoserver|jugglucoseserver' Common/src` — confirms #5 absence.
   - `rg -n 'ICanHealth|iCanHealth' Common/src/drivers/icanhealth` — confirms #1 area.

4. **Confirm triages still stand** (read-only). For each Verify-Resolved row, grep for the canonical fix code and capture file path + first relevant line in the triage doc.

5. **Post a triage comment to each migrated issue** (via `gh issue comment`). One comment per destination issue (#9–#45) with body:
   - Verdict line (`Still Applicable` / `Verify-Resolved — keeping closed` / `Not Applicable — closing as not planned`).
   - For Still Applicable: 1–2 sentence implementation outline + verified file path.
   - For Verify-Resolved: file path + one-line quote of the canonical fix site.
   - For Not Applicable: which feature was removed + pointer to where it was removed.
   - Footer: `Triaged 2026-07-17 from worktree \`issue-triage-1\` against \`glucodroid\` @ \`20df10a8d\`.`
   - Bodies live in `docs/triage-comments/GlucoDroid-app-<dest>.md` so the doc and the comments stay in sync.
   - Order: Not-Applicable first, then Verify-Resolved, then Still Applicable.
   - Close Not-Applicable issues: `gh issue close <num> --repo GlucoDroid/app --reason "not planned"` for destination numbers #45, #32, #29, #13, #9-n/a rows (per the table).

6. **Append "## Fix Plans" to the triage doc**, one numbered subsection per Still-Applicable issue:
   - Issue link.
   - Entry-point file path + key class names.
   - 3–6 bullet sketch of the change.
   - Risk / verification approach.
   - Issues #38/#36/#35 (large lint categories) get a sub-bullet per category.

7. **Open a single tracking PR** off `issue-triage-1` against `glucodroid`. `gh pr create --base glucodroid --head issue-triage-1 --title "triage: review 37 migrated issues against current glucodroid" --body-file docs/triage-pr-body.md`. Body is triaged-rollup counts + link to `docs/issue-triage-2026-07-17.md` and explicitly opts out of source changes: "No source changes; doc + per-issue comments only." Do NOT auto-merge (per `CLAUDE.md`, merges go through a squash PR + manual `gh pr merge`).

## Affected boundaries

- New worktree only (`.kilo/worktrees/issue-triage-1`), entirely read-only with respect to source code.
- New local branch `issue-triage-1` only. No edits to `glucodroid`, no edits to the existing `fix/debug-log-share` or other worktrees.
- Documentation files inside the new worktree: `docs/issue-triage-2026-07-17.md`, `docs/triage-comments/*.md`, `docs/triage-pr-body.md`.
- 37 issue comments on `GlucoDroid/app`; 5 of them closed as `not planned`.

## Risks

- The legacy features removed in upstream merge (`SaveLog`, Mirror menu, Food model, Juggluco standalone server) might have been reintroduced since. Counter-checked with the rg list in step 3.
- Source #4–#5 had lightweight comments whose expected behaviour may not match the new TTS/schedule code; one-line fix plan must surface any divergence.
- Mirror UI may have re-entered `glucodroid` via a recent merge; counter-checked via the `Mirror` grep in step 3.

## Validation

- `git -C .kilo/worktrees/issue-triage-1 status` clean after steps 2 and 6.
- `docs/issue-triage-2026-07-17.md` exists and the 37 verdicts match the table above.
- `gh issue list --repo GlucoDroid/app --state all --json number,state` shows:
  - 5 issues moved to CLOSED with reason `not planned`.
  - 32 issues still OPEN, each with one new comment from this triage pass.
- The tracking PR is open with the triage rollup in the body; merge is left to the user.

## Out of scope

- Implementing any of the Still-Applicable fix plans. Triage-only PR; fixes happen in separate follow-up PRs off `glucodroid`.
- Re-running the issue migration or modifying GitHub labels beyond what the migration script already produced.
- Touching the `upstream/feat/sustained-low-alert` branch (covered by an earlier cleanup plan, now superseded by this triage plan).
