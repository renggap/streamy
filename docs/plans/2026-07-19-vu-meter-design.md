# VU Meter (Oscilloscope) — Design

**Date:** 2026-07-19
**Status:** Approved (brainstorm)
**Branch:** `fix/ui-polish-icons`

## Goal

A creative, real VU meter on the Home screen that shows the live microphone waveform while broadcasting. "Real" = driven by actual PCM from `AudioCapture`. "Creative" = an oscilloscope-style glowing trace in the Hallmark on-air aesthetic.

## Non-goals (YAGNI)

- No peak-hold LED ladder (the chosen style is the oscilloscope trace; a dB readout suffices).
- No stereo meters (speech = mono; single trace).
- No metering while Idle beyond a dim placeholder (mic capture only runs while streaming).
- No EQ/spectrum analysis — time-domain waveform only.

## Signal path

```
AudioCapture (reads PCM) --onSamples(ShortArray)--> AudioPipeline
   AudioPipeline: RMS(dB) + decimate to ~96 points --> throttled ~30fps --> StateFlow<LevelSnapshot?>
StreamService.levels --> MainActivity ServiceConnection --> StreamyViewModel --> HomeScreen VuMeter(Canvas)
```

- The tap is **pre-encoder** → codec-agnostic (MP3 and AAC both feed it identically; encoding untouched).
- `LevelSnapshot(samples: FloatArray, rmsDb: Float)`.
- Throttle: push at most every ~33 ms (conflate + time gate) so Compose recomposition stays bounded.

## Components

- `audio/AudioCapture.kt` — add optional `onSamples: ((ShortArray) -> Unit)?` constructor param; invoked each frame with the freshly-read PCM (before handing to the encoder).
- `audio/LevelTap.kt` (new) — pure helpers: `rmsDb(ShortArray): Float` (full-scale = 0 dB, silence → ~-90), `decimate(ShortArray, points: Int): FloatArray` (normalized -1..1). Unit-tested.
- `audio/AudioPipeline.kt` — on each frame, if `onSamples` provided, compute snapshot via `LevelTap` and push to a `LevelFeed` (a tiny class holding a `MutableStateFlow<LevelSnapshot?>` + the 33 ms gate).
- `service/StreamService.kt` — own a `LevelFeed`, pass its push callback into `AudioPipeline`, expose `val levels: StateFlow<LevelSnapshot?> = feed.flow`. Reset to null on stop.
- `ui/StreamyViewModel.kt` — mirror `levels` from the bound service (same pattern as `streamState`).
- `ui/VuMeter.kt` (new) — `Canvas` composable: dark scope panel, faint grid + center axis, glowing on-air-red trace with one-frame phosphor afterglow, `-NN dB` corner readout. Hallmark-styled.
- `ui/HomeScreen.kt` — place `VuMeter` under the state pill / Start control; visible always (dim/flat while not Live).

## Visual

Hallmark "late-night tally light": deep-ink scope panel, hairline grid, center axis, the live PCM trace in on-air red (`StreamyColors` red) with a short afterglow (previous trace at ~20% alpha). `-NN dB` mono readout in the corner; the existing `codec · sr · bitrate` line beneath. While Idle: flat dim line (no data until streaming).

## Error handling / performance

- PCM callback on the audio thread must be trivial (RMS + decimate of 1024 samples = microseconds). No I/O.
- StateFlow is `conflate`d — slow UI drops intermediate snapshots, never blocks the audio thread.
- If `onSamples` is null (no meter wired), AudioCapture/pipeline behave exactly as today — zero overhead, no behavior change for the encoder/transport.

## Testing

- **Unit (JVM):** `LevelTapTest` — `rmsDb` for silence (≈ -90), full-scale (0), known amplitude; `decimate` output length + normalization. Pure, golden.
- **Visual:** verified on a device while Live (the only environment with a real mic + the running pipeline).

## Open items

- Afterglow uses the previous snapshot held in the composable (`remember`) — no service-side state for it.
- If recompose cost shows up on low-end devices, drop to 20 fps (one constant).
