# Video frame-rate evidence

## Source of truth

The detector fits the **media presentation timestamps submitted to the video decoder**. It does not infer source fps from the HDMI mode, SurfaceFlinger presentation times, wall-clock decoder counters, or a codec's nominal frame-rate report.

On the tested Android 14 Codec2 implementation, native video tracing exposes `CCodecBufferChannel::queue(codec@ts=mediaTimeUs)`. The Android implementation sends both ordinary and secure input through the same input queue implementation. The timestamp is available without reading decoded frames or encrypted payloads.

References:

- [Android 14 Codec2 input queue implementation](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android14-release/media/codec2/sfplugin/CCodecBufferChannel.cpp)
- [MediaCodec presentationTimeUs contract](https://developer.android.com/reference/android/media/MediaCodec#queueInputBuffer(int,int,int,long,int))
- [Perfetto command-line interface](https://perfetto.dev/docs/quickstart/android-tracing)

## Precision criterion

`PresentationRateEstimator` matches a timestamp sequence against the supported standard cadences: 24000/1001, 24, 25, 30000/1001, 30, 50, 60000/1001 and 60 fps.

A reading requires at least 40 unique timestamps spanning at least three seconds of media time. Duplicate timestamps are discarded. Presentation order is reconstructed by sorting media timestamps, so B-frame input reordering does not become an artificial cadence change.

The fit estimates timestamp quantization, capped at one millisecond, and checks the range of phase residuals across the whole sequence. A fractional rate is accepted only when that model fits and competing standard-rate models do not. Short sequences, mixed cadences, sparse traces and competing video codec instances do not authorize a change.

This is a match to standard constant frame rates within the observed timestamp resolution. It does not claim to identify every arbitrary or variable frame rate.

## Capture and attribution

`PerfettoPtsSampler` starts its own bounded video tracing session and consumes stdout through a pipe. It does not clear the tracing buffer, stop another session, change a display mode, or enable persistent verbose logging. A reader drains the pipe concurrently, enforces a four-MiB result limit, and terminates timed-out captures.

Only video decoder queue entries belonging to the foreground process are considered. Audio is excluded. Codec instances are evaluated separately; a batch containing more than one video instance is withheld until a later unambiguous batch.

Capture is requested while the HUD or AFR controller asks for fps. Existing codec lifecycle evidence invalidates cached readings on a codec change. Captures crossing a foreground-process or known-stream change are discarded. Cached evidence expires rather than surviving indefinitely through failed captures.

## Consumers

`GET_SYSINFO` carries `VERIFIED`, `SAMPLES` and `SPAN_US` alongside fps and source. The client validates those fields and the expected PTS source before accepting authority.

Automatic AFR and the one-click automatic action require verified input PTS. Nominal hardware reports remain available to the HUD with an approximation marker. They cannot silently override a verified reading or authorize a fractional conversion. Manual display selection remains available when tracing is unsupported or evidence is insufficient.

The daemon reports its version in `PING`; app startup replaces a daemon from an older APK instead of silently reusing it.

## Reproduction

The test resources contain anonymized input timestamp events captured on Android TV. They include a generated MP4 at 24 fps, a generated MKV at 24000/1001 fps with millisecond timestamps, a protected Disney+ sample, and the Jellyfin failure whose nominal 24 fps report conflicts with its fractional input timestamps. No media payload, account data, device identifier, address or title is included.

Run the focused checks with:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The estimator tests cover integer/fractional pairs, quantization phases, reordered and missing events, codec separation, short samples, malformed protocol evidence and false authority from nominal codec reports.
