# Input PTS regression captures

These resources contain only timestamp trace events. Process and codec instance identifiers have been replaced, and protected-media positions are rebased. No video or audio payload is included.

- `24-mp4.trace`: generated 320×180 H.264 MP4, 24 fps, microsecond PTS from the real Codec2 input queue.
- `23976-mkv.trace`: generated 320×180 H.264 MKV, 24000/1001 fps, millisecond-quantized PTS from the real Codec2 input queue.
- `disney-protected-24.trace`: HEVC secure decoder input PTS captured during protected Disney+ playback; the standard-rate fit uniquely matches 24 fps.

Configuration timestamps, duplicates and the ExoPlayer media offset are preserved in the generated clip captures. They deliberately exercise offset separation and timestamp deduplication.

- `jellyfin-declared24-actual23976.trace`: the reported Jellyfin failure. The installed daemon and hardware report 24 fps while this independently captured HEVC input sequence uniquely matches 24000/1001 fps.
