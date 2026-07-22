# Alerts

GlucoDroid fires a layered set of alerts off the live glucose stream. This page documents the user-facing configuration that lives under **Settings → Alerts** and explains how each alert condition is evaluated.

> Source of truth: `Common/src/main/java/tk/glucodata/alerts/AlertRuntimeManager.kt` and the Compose UI in `Common/src/mobile/java/tk/glucodata/ui/alerts/AlertSettingsScreen.kt`.

## Threshold alerts

Six threshold conditions are evaluated on every incoming reading (and every 15 s by the monitor task):

| Type        | Default threshold (mg/dL) | Notes |
|-------------|---------------------------|-------|
| Very Low    | 54                        | Hard floor; combined with the [sustained-low gate](#sustained-low-gate) when enabled |
| Low         | 70                        | Combined with the [sustained-low gate](#sustained-low-gate) when enabled |
| Pre Low     | 80 + look-ahead           | Forecast; raises when projected to cross Low within `forecastMinutes` |
| Pre High    | 180 + look-ahead          | Forecast; raises when projected to cross High within `forecastMinutes` |
| High        | 200                       | Plain threshold |
| Very High   | 250                       | Plain threshold |

The evaluator (`StandardGlucoseAlertEvaluator`) uses an episode model: a threshold has to remain continuously active before firing, and a brief blip back above the line suppresses the alert without losing it. This avoids noise from sensor compression lows and single-sample jitter.

## Sustained-low gate

A toggle available on **Low** and **Very Low** only. When enabled, the alert is deferred until glucose has stayed below the threshold for a chosen duration (5–30 min, 5-min steps). The default seed is 10 min.

**Why it exists.** CGM sensors (Libre, Dexcom, …) periodically report false compression lows that last one or two readings. Both AAPS and Loop implement analogous "rise above / persist below" filtering on closed-loop safety decisions; LibreLink applies it implicitly through smoothing. GlucoDroid exposes the equivalent for raw notifications.

**How it works in code.** `AlertRuntimeManager` tracks `sustainedLowStartedAtMs` per eligible type. While the threshold is active, the timer is anchored to the first reading time below the line. When the reading leaves the low band, the timer is cleared, so the next qualifying reading starts a fresh window. The standard episode machinery still fires the alert once both gates have passed.

**Interaction with other alerts.**

- Independent of **Pre Low**. Pre Low stays a forecast alert with no duration gate.
- Independent of **Persistent High** / **Missed reading** / **Sensor expiry**. Those use a separate `durationMinutes` field internally; the UI now distinguishes the two surfaces on `LOW` / `VERY_LOW`.
- Stacks with snooze. A sustained low that finally fires can still be snoozed; on next entry into the threshold band the gate re-arms from zero.
- Not applied to **High / Very High**. Compressed spikes are not a known sensor artefact, so a plain threshold is sufficient.

**To enable.** Settings → Alerts → Low (or Very Low) → toggle *Require sustained below threshold* → pick a duration. Leaving the toggle off restores the original fire-on-first-reading behaviour.

**Trade-off.** A longer gate reduces false alarms but delays reaction to genuine lows. A common starting point is 10 min; tune from there based on how often sensor compression lows affect your stream.

## Other alerts

- **Missed reading** — fires if no new reading has arrived for `durationMinutes` (default 30).
- **Persistent high** — fires if glucose has stayed above the threshold continuously for `durationMinutes`. Mirrors a simplified version of the same pattern used by AAPS for high-pumping decisions.
- **Sensor expiry** — fires within 24 h of the Libre sensor's reported end-time.
- **Custom alerts** — arbitrary user-defined threshold + duration combinations (`CustomAlertManager`).

## Snooze

Each fired alert can be snoozed from the notification action. On snooze expiry, an alert remains eligible to re-fire if its condition is still active when the cooldown ends (`AlertRuntimeManager.onAlertSnoozed`).

## Delivery modes

`SYSTEM_ALARM` (default) uses Android's high-priority alarm channel so it bypasses Do Not Disturb when **Override DND** is enabled. `IN_APP` keeps the notification in the drawer without raising a heads-up.