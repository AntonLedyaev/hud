# Confirmation and restriction domain

Pure Kotlin: no Android, camera, database or LiteRT imports. Immutable `val` data
models and read-only lists represent observations, confirmed batches, config and
state. Callers must not retain mutable aliases to lists passed into events.

`Contracts.kt` mirrors v1 shared JSON field names. `EventCodec` uses strict JSON
and a `kind` discriminator for sealed payloads; unknown fields/versions fail.
The complete effective config travels with each event. Canonical class IDs/names
are loaded from generated labels, never maintained in a second enum/list.

`TemporalConfirmation` is session-scoped mutable processing state. It associates
same-class boxes by IoU, counts distinct increasing frame IDs within the time
window, emits one encounter per continuously visible sign and rearms after an
absence interval. Duplicate candidates in one frame cannot add hits. It limits
tracks to 128 by default and retains at most the configured minimum-hit count per
track. When full, unmatched new candidates are ignored until tracks expire.
Low-confidence/unsupported observations cannot update the state. Reset increments
the generation and clears pending tracks; old-generation frames are ignored.

`RestrictionReducer.reduce(previous, event)` is pure: it returns a new state and
does not read the wall clock. Explicit ticks drive expiry, including when no sign
is visible. It rejects mismatched session/model/hash/config/generation, repeated
sequences, decreasing timestamps and repeated confirmed frame IDs. The coordinator
must serialize these inputs and reset both confirmation and reducer together.

Speed signs replace speed; end-of-80 clears only an estimated 80. End-of-all clears
speed and overtaking. Overtaking cancellation is independent of speed. STOP is
transient and does not alter restrictions. Conflicting changes within a batch
produce unknown for the affected field. Unknown IDs have no sign semantics.
Expiry marks estimates stale while retaining their value/source for diagnostics;
the HUD must use `Estimate.current`, which hides stale values. No cancellation
infers a default legal speed. Evidence gaps and recognition errors invalidate
current estimates explicitly.

## Replay without camera or model

From `android/`, after setting JAVA_HOME and ANDROID_HOME:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*DomainTest'
.\gradlew.bat :app:testDebugUnitTest --tests '*DomainTest.debugReplayWithoutCameraIsDeterministic' --rerun-tasks --info
```

The test build derives `app/build/generated/canonical/labels.json` from the shared
YAML using the ML Python environment. Override its executable with
`-PlabelsPython=ABSOLUTE_PATH` if needed. It does not require export model assets.

The debug replay prints a synthetic 80-limit event, overtaking event, expiry tick
and reset; it writes a JSONL event stream to ignored
`app/build/reports/domain/events.jsonl`. Replaying encoded/decoded events must
produce identical intermediate states, source sequences and reasons. Test reports
are under `app/build/reports/tests/testDebugUnitTest/`.

Test timing/threshold settings are synthetic fixtures, not calibrated driving
settings. No UI inference or camera recognition is enabled by this domain work.
