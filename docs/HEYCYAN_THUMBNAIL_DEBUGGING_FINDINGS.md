# HeyCyan Thumbnail Transfer Debugging Findings

## Summary

The root cause of the thumbnail corruption was not a simple JPEG prefix issue. The device callback stream was arriving as a fragmented, mixed byte stream: the first chunk could contain random garbage, later chunks could start with the JPEG SOI marker (`FF D8`), and several packets were repeated or interleaved. Appending raw callback bytes directly to disk preserved that corruption instead of reconstructing a valid JPEG frame.

The fix was to reassemble the packet stream by packet index, drop duplicates, trim leading junk, and keep only the valid JPEG segment from SOI to EOI before writing the output.

## What was validated

A captured log was replayed through the validator and successfully reconstructed into a valid JPEG:

- 32 packet dumps were parsed successfully
- reconstructed payload started with `FF D8 FF E0 00 10 4A 46 49 46` (JFIF header)
- image ended with `FF D9`
- Pillow verification succeeded
- file metadata identified the output as a valid JPEG (`640x480`, baseline, 3 components)

This confirms the underlying reconstruction logic matches the actual device transfer pattern seen in the raw log.

## Root cause

The callback payload was not a clean continuous JPEG stream. The observed pattern was:

- packet 1: mostly garbage / non-JPEG bytes
- packet 2+: begins with `FF D8 FF E0 ... JFIF ...`
- later packets: mixed data, duplicate fragments, and trailing garbage after the real JPEG end

If the app wrote each callback payload directly to the file, the result became a corrupted image because the stream was concatenated before the valid JPEG frame was isolated.

## Fix implemented

The app-side reconstruction path now:

1. collects fragment payloads keyed by packet number
2. reorders them by packet index
3. drops duplicate payload blocks
4. strips leading junk before the first SOI marker
5. keeps only the bytes from the first valid JPEG start to the final valid JPEG end
6. writes only the reconstructed JPEG frame

This was applied to the live preview path and the corresponding thumbnail emission paths that directly wrote raw callback chunks.

## Validation helper

The repository now includes a validator utility that accepts either:

- a raw adb log from `logcat`, or
- a pre-filtered log containing only `HeyCyan thumbnail packet dump ...` lines

It reconstructs the JPEG and validates it with Pillow. The helper is available at:

- `tools/validate_heycyan_thumbnail_log.py`
- `tools/heycyan_thumbnail_capture_helper.sh`

Example usage:

```bash
python3 tools/validate_heycyan_thumbnail_log.py /tmp/heycyan_thumbnail_debug.log --strict
```

or:

```bash
bash tools/heycyan_thumbnail_capture_helper.sh --capture --output /tmp/heycyan_thumbnail_debug.log
bash tools/heycyan_thumbnail_capture_helper.sh --validate /tmp/heycyan_thumbnail_debug.log --strict
```

## Takeaway

This was a transport-level fragmentation problem, not a firmware command-selection problem. The fix is robust because it validates against the actual byte stream captured from the device rather than assuming the callback payload is a clean JPEG.
