# OpenAI TTS Integration Plan

## Goal

Replace the app's built-in Android `TextToSpeech` path for assistant replies with OpenAI's speech API, specifically `gpt-4o-mini-tts`, while preserving the existing Bluetooth/audio route logic that keeps playback on the glasses speaker when appropriate.

## Why this approach

- Android `TextToSpeech` cannot directly call `gpt-4o-mini-tts`.
- `gpt-4o-mini-tts` generates audio on OpenAI's servers and returns audio bytes.
- The app still needs to respect its current route management (`restorePhoneAudioRouteForPlayback`, Bluetooth communication-device cleanup, and glasses-speaker routing).
- Using OpenAI speech as a final audio-generation step keeps the app's route logic intact while allowing `instructions` to shape speaking style.

## High-level design

1. Add a dedicated OpenAI speech provider in the remote client layer.
2. Add a runtime toggle for TTS provider selection (`NATIVE_ANDROID` vs `OPENAI_GPT4O_MINI_TTS`).
3. Add a configurable TTS cache pool keyed by the normalized text payload so repeated phrases such as "I am listening" or standard system voice-overs resolve from cache instead of re-requesting audio.
4. When the OpenAI provider is selected, generate the audio file remotely from the assistant text and style instructions if the cached result is not available.
5. Save the returned audio to a temp file and/or keep a durable cache artifact for reuse.
6. Play the file through the existing Android media pipeline using the already-managed route state.
7. Keep the native TTS path as a fallback for offline or unsupported cases.

## Implementation steps

### 1. Add a speech provider configuration toggle

- Decide where preferences live for assistant TTS selection.
- Add a persisted setting such as:
  - `NATIVE_ANDROID`
  - `OPENAI_GPT4O_MINI_TTS`
- Default this to the current native behavior unless the user explicitly chooses the OpenAI route.
- Expose the setting in the existing app settings UI if there is already a model/provider settings screen.

Acceptance criteria:
- The app can read the active TTS provider without hard-coding.
- The toggle is safely defaulted and easy to test.

### 2. Extend the remote OpenAI client with a speech generation method

- In `RemoteOpenAiClient`, add a new method such as `generateSpeech(...)`.
- Use the OpenAI audio/speech endpoint:
  - `POST https://api.openai.com/v1/audio/speech`
- Request fields:
  - `model`: `gpt-4o-mini-tts`
  - `input`: final assistant text
  - `voice`: e.g. `alloy`, `ash`, `coral`, `sage`, `verse`, `onyx`
  - `instructions`: speaking style prompt, e.g. warm, concise, natural, confident, calm
  - `response_format`: `mp3` (or whichever format is best for Android playback)
- Use the same API key and base URL conventions already used by the remote OpenAI client.
- Validate the HTTP response and surface a useful error if the request fails.

Acceptance criteria:
- A valid request reaches the OpenAI audio/speech endpoint.
- The returned bytes are written to a temporary file.
- Failures are surfaced with the same style of diagnostics already used in the remote client.

### 3. Add a configurable TTS cache pool by text hash

- Create a small cache manager that stores generated speech files by a stable hash of the text prompt, with provider/model/voice/instructions included in the hash input to avoid collisions across different voice settings.
- Recommended key format:
  - normalized input text
  - selected TTS provider
  - model name (`gpt-4o-mini-tts`)
  - voice (`alloy`, `ash`, etc.)
  - style instructions
- Use a deterministic hash such as SHA-256 of the canonical payload so common phrases like "I am listening" can be replayed from disk without hitting the API again.
- Store artifacts in a dedicated cache directory such as:
  - `cacheDir/openai_tts_cache/<hash>.mp3`
- Expose config values for:
  - cache enabled/disabled
  - max number of cached files
  - max age or TTL for stale entries
  - optional forced refresh flag for debugging or voice tuning
- Keep the cache scoped to the app and safe for reuse across app restarts.

Acceptance criteria:
- Repeated requests for the same assistant text and voice configuration re-use the same audio file.
- Built-in or recurrent system voice-overs such as "I am listening" resolve from cache instead of generating duplicate audio.
- Cache entries are invalidated or refreshed when voice/instructions change.

### 4. Add a helper for temp audio output and cache fallback

- Create a small helper that writes the MP3/WAV payload to a cache file such as:
  - `cacheDir/openai_tts_cache/<hash>.mp3`
  - optional temp output in `cacheDir/audio/openai_tts_<timestamp>.mp3` for one-off playback
- Reuse cache entries when available and only fetch from the API when the hash is missing.
- Ensure the file is cleaned up after playback only for non-cached temporary output.
- Make the helper reusable from either the activity or another service.

Acceptance criteria:
- Temporary audio files are created and readable.
- Repeated phrases resolve from cache and do not trigger redundant requests.
- Cleanup does not interfere with active playback.

### 5. Add a dedicated audio-playback path in `MainActivity`

- Keep the current `TextToSpeech` pipeline as the fallback path.
- Add a branch in `speak(...)` or a new helper method that checks the selected TTS provider.
- If the provider is `OPENAI_GPT4O_MINI_TTS`:
  - call the new OpenAI speech generator,
  - create a `MediaPlayer` or `ExoPlayer` instance,
  - set audio attributes to spoken content,
  - restore the active audio route via `restorePhoneAudioRouteForPlayback(...)` before starting playback,
  - call completion cleanup when playback ends.

Acceptance criteria:
- The app routes to the OpenAI-generated audio file for assistant responses only when the new provider is selected.
- Existing BT/glasses route restoration remains intact.
- Native TTS remains the fallback when the OpenAI provider is disabled or the request fails.

### 6. Preserve the current route management semantics

- Do not remove the current `restorePhoneAudioRouteForPlayback(...)` logic.
- Keep the stale Bluetooth communication-route cleanup in place.
- Ensure the playback path still uses the same route restoration and `AudioSessionCoordinator` state updates.
- If the app is connected through a glasses communication device, the OpenAI TTS playback should still be issued on the correct route rather than falling back to the wrong audio output.

Acceptance criteria:
- The OpenAI-generated audio still plays through the glasses speaker when that route is active.
- No stale Bluetooth communication device route leaks into the generated speech playback.

### 7. Add style instructions at the right layer

- Define the speaking style string as a separate constant or config value.
- Example prompt:
  - "Speak in a friendly, calm, natural voice. Keep the message concise, conversational, and confident. Emphasize clarity over speed."
- Pass this string as the `instructions` part of the speech request.
- Make the persona tuneable for future iterations if needed.

Acceptance criteria:
- The final generated audio follows the configured voice style instead of the default OS TTS voice.
- The style prompt is a first-class part of the speech request rather than embedded ad hoc in one-off code paths.

### 8. Add logs and diagnostics

- Log when the app chooses the OpenAI TTS path.
- Log the selected model, voice, and response format.
- Log failures from the remote audio generation call.
- Keep the same debugging conventions already used in the app for audio route issues.

Acceptance criteria:
- It is easy to tell from logcat which TTS path was used.
- Route and playback issues are visible during device validation.

### 9. Validate with the connected handset

- Build and install with `./build_to_device.sh`.
- Launch the app and trigger a fresh AI response.
- Confirm the answer is spoken through the expected route.
- Verify the voice behavior matches the supplied style instructions.
- Validate both:
  - route-to-glasses case
  - fallback/native case

Acceptance criteria:
- OpenAI TTS works on the connected phone/device.
- The selection switch behaves correctly.
- Audio still routes to the glasses as expected.

## Suggested implementation order

1. Add the TTS provider setting.
2. Add the OpenAI speech client method.
3. Add the configurable text-hash cache pool.
4. Add temp-file helpers and cache fallback logic.
5. Branch `MainActivity` playback logic.
6. Preserve route restoration and logs.
7. Validate on the live connected device.

## Notes for the agent

- Prefer a small, isolated change set rather than rewriting the whole TTS stack.
- Keep the current route logic as the source of truth for output device selection.
- Treat OpenAI TTS as a replacement of the generated audio payload, not as a replacement of the route management architecture.
- If the speech model fails, fall back cleanly to Android native TTS to avoid a dead-end voice path.
