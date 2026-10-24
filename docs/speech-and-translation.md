# Speech recognition, segmentation, translation, and speech

中文：[speech-and-translation.zh-CN.md](speech-and-translation.zh-CN.md)

This note describes how TransPocket `0.1.0` goes from the microphone to spoken translation. It is a fork of RTranslator `v3.00` (`3.0.0-beta2`). Model files are not in git. After the first launch they are downloaded into the app's private directory. `Global.USE_EXTERNAL_MEMORY_FOR_RESOURCES` is currently `false`, so the paths below are relative to `context.getFilesDir()`.

The pipeline has four stages. There is no streaming recognition in the middle:

```
microphone PCM
  -> Silero VAD plus an amplitude gate, cut into one whole utterance
  -> Whisper Small (ONNX) recognizes that whole utterance
  -> split into sentences before translation, then merge by a token budget
  -> Bergamot / Madlad / HY-MT, with an optional dictionary and Tatoeba
  -> Android system TTS
```

Recognition and translation run on the phone. TTS is not a neural model shipped with the app. It uses a `TextToSpeech` engine already installed on the system.

## 1. How speech is cut into one utterance

Segmentation lives in `Recorder`, not in Whisper. Whisper is called once when an utterance ends. Its input is all of that utterance's PCM.

Capture parameters:

- The sample rate tried is 16000 Hz, mono.
- vivo devices use `PCM_16BIT`. Other devices use `PCM_FLOAT`. The source is `AudioSource.MIC`.
- The ring buffer is about 30 seconds: `(29s + 1s) * sampleRate`. The float buffer goes to recognition. The short buffer goes to VAD. Float-to-short conversion multiplies by 32768.
- Each read is aligned to 512 samples, one Silero frame (32 ms at 16 kHz).

VAD is created in `Global` and shared by the process: one `VadSilero` at 16 kHz, frame length 512, `Mode.NORMAL`, minimum speech 50 ms, silence 300 ms. That 300 ms is an internal parameter of the Silero library. When an utterance ends is decided by `speechTimeout` below, not by those 300 ms alone.

In automatic mode a read counts as speech only when both of these are true:

1. At least 15 samples in the block have an absolute value above the amplitude threshold. The default is 1500, the range is 400 to 15000, and the setting is `amplitudeThreshold` on a 16-bit scale.
2. At least one 512-sample frame in the block returns true from `vad.isSpeech()`. A short final frame is padded with zeros and still sent to Silero.

Only when both pass is the "last time speech was heard" timestamp refreshed. Otherwise, if an utterance has already started and silence lasts longer than `speechTimeout`, the utterance ends. `speechTimeout` defaults to 1300 ms and ranges from 100 to 5000 ms.

The start of an utterance reaches backward so the attack is not clipped. That lead-in is `prevVoiceDuration`, also 1300 ms by default, ranging from 100 to 1800 ms. The start index is `tailIndex - prevVoiceLength` in the ring buffer.

There is a hard cap: more than 29 seconds from the start, including the lead-in, is cut immediately and sent to recognition. The longest audio Whisper sees is about 29 seconds.

Manual mode (push-to-talk in WalkieTalkie) forces `isHearingVoice()` to true and shrinks `prevVoice` to 0.1 seconds until `end()` is called.

When the utterance ends, `executeEnd()` copies `[startVoiceIndex, tailIndex)` out of the ring buffer as a float array and then calls `onVoice(data)` and `onVoiceEnd()` in that order. Recognition happens in `onVoice`, not while the person is still talking. The on-screen level meter updates only while `isRecording` is true, which is after the utterance has started.

While TalkBack is speaking, and the route is not Bluetooth SCO, `readAudio` zeroes the samples just read and ends the current utterance at once. That software mute is skipped when an SCO headset is connected.

### Segmentation for simultaneous interpretation

Conversation and WalkieTalkie still hand a whole stretch of speech to recognition using `speechTimeout` above. Simultaneous interpretation cuts again inside that stretch by default: 420 ms of silence after at least 700 ms of speech submits one sentence, 7000 ms of speech also submits one sentence, and the recognition text is then split on punctuation by `ClauseSplitter`. Capture does not stop after a sentence is submitted.

On first entry, Silero VAD v6.2.1 (about 2 MB, `VAD/silero_vad.onnx`) is on by default in the model settings and is downloaded with the other selected models. An install that already exists and never downloaded that file turns the switch off at cold start and keeps the 420 ms plus punctuation path. When the switch is on and the file loads, simultaneous interpretation uses that model's own state. It does not share the bundled `VadSilero` above. Each frame is prefixed with the last 64 samples of the previous frame, so the model sees 576 samples. Speech onset requires about 96 ms of probability at or above 0.5, and the frame must also pass the amplitude gate. Audio from before that confirmation is included up to the expected voice duration, 1300 ms by default. Samples already given to the previous sentence are not included again. After at least about 250 ms of speech, about 320 ms below a probability of 0.35 submits the sentence and listening continues. Reaching 7000 ms while speech is still going also cuts. Silence that reaches `speechTimeout` ends the capture round and clears the LSTM state. In that case `ClauseSplitter` does not run. If the switch is off, the file is missing, or the session cannot be opened, the path falls back to 420 ms and punctuation.

## 2. How Whisper transcribes one utterance

The implementation is `Recognizer`. 3.0 runs `WhisperTurboEngine` (a merged-decoder graph) for Whisper Small and large-v3-turbo. The split Whisper Small graph below is kept for installs that predate the model bundle:

| File | Role |
| --- | --- |
| `Whisper_initializer.onnx` | Turns `audio_pcm` into encoder input features |
| `Whisper_encoder.onnx` | Encodes. Low-memory mode turns off the CPU arena and the memory pattern |
| `Whisper_cache_initializer.onnx` | Fills encoder KV from the encoder output |
| `Whisper_cache_initializer_batch.onnx` | Copies one encoding into batch 2 for two languages |
| `Whisper_decoder.onnx` | Decodes token by token, 12 decoder-cache layers |
| `Whisper_detokenizer.onnx` | Turns token ids back into a string |

Decoding is greedy, not beam search. `recognize(..., beamSize, ...)` stores `beamSize` in a `DataContainer`. The loop that follows does not read it. The `SPEECH_BEAM_SIZE = 4` passed by Conversation and WalkieTalkie therefore does not change the result. Each step takes the id with the largest logit through `Utils.getIndexOfLargest`.

For one language, the first four decoder tokens are fixed:

```
50258  start
50258 + language index + 1
50359  transcribe
50363  no timestamps
```

The language index comes from `Recognizer.LANGUAGES`, Whisper's full language list, not the "high quality" list shown in the UI. The UI list is `res/raw/whisper_supported_languages.xml`. Turning on low-quality languages in settings switches to the full `LANGUAGES` list.

Each later step feeds the previous id as `input_ids` together with 12 layers of `past_key_values.{i}.decoder/encoder.key/value`. The end token `eos` is 50257.

The token cap is `min(445, seconds of audio * 30)`. Seconds are estimated as `data.length / 16000`. Hitting the cap forces eos and does **not** send that span to the detokenizer. The result becomes the placeholder `[(und)]`. Callers drop `[(und)]` and any text whose first three characters contain `[` or `(`, treating it as metadata.

After the detokenizer, `correctText()` does four things: strip leftover `<|...|>` timestamps, trim both ends, capitalize the first letter, and delete `...`.

`isFinal` from `notifyResult` is always `true` on this path. The Conversation branch that shows a non-final result without sending it is not reached by the current Whisper implementation. One utterance, one final result. Utterances are serialized on `Recognizer.lock`. `stop()` clears the queue of work that has not started.

## 3. How WalkieTalkie picks one of two languages

WalkieTalkie calls `recognize(data, beam, lang1, lang2)` on the same audio. Encoding runs once. The encoder output is copied to batch 2. The two decoder heads carry the language tokens for `lang1` and `lang2`. The remaining steps run in parallel. Each step records `log(softmax(chosen logit))`. Dividing by the token count gives two mean log probabilities.

`WalkieTalkieService.compareResults()` then decides the language. The default threshold is 0.5:

1. If only one side is metadata, keep the other side.
2. Otherwise both texts go to `Translator.detectLanguage`. If both are judged to be their own target language, keep the side with the higher mean log probability. If only one side matches, keep that side.
3. If language detection fails, fall back to comparing the mean log probabilities.

The real `LanguageDetector` branch does not match its comment exactly:

- NFC, lowercase, then strip Whisper `[...]` / `(...)`, punctuation, and digits. Word count uses ICU `BreakIterator.getWordInstance()` and counts only segments that start with a letter or a digit.
- Under 7 words: a single word is checked against a hard-coded list (currently only `ciao` to Italian) and then against the dictionary. Then Lingua runs.
- FastText (`fasttext.ftz`, shipped in assets and copied into files) runs only when the word count is 1 or less and Lingua returned nothing.
- Seven words or more returns `und` immediately. FastText does not run.

A normal-length utterance therefore often has its WalkieTalkie language chosen by Whisper's mean log probability, not by FastText.

Conversation does not use two languages. It runs one recognition pass in the local `Global.getLanguage()`, sends the original text with the language code, and translates on the receiving phone.

## 3.1 Language lock, guard, and the four ASR models

`Global.isAsrLanguageLock()` (default `true`, settings key `asrLanguageLock`) decides whether a recognition may use a language other than the configured one.

- **Whisper Small / large-v3-turbo** (`WhisperTurboEngine`): the language token comes from `AsrLanguages.whisperToken(code, largeV3)`. Large-v3 has 100 language tokens; Small has 99, so `yue` falls back to `zh`. In simultaneous mode a locked clause is decoded with the fixed token and skips the SOT probe. With the lock off, the probe may switch only to the session target language, and still needs the 2.0 hint bonus plus the 1.5 margin from `AsrLanguageHint`.
- **Qwen3-ASR 0.6B / 1.7B** (`Qwen3AsrEngine`): `transcribe(samples, languageCode)` appends `language <Name>` to the assistant turn, so the model decodes the requested language instead of reporting one. The prefix uses the model's own byte-level vocabulary; when it cannot be encoded and decoded back to the same string, the call falls back to the free decode and logs `prefixFallback`.
- **`AsrLanguageGuard`** checks the transcript against the selected language using Unicode script counts and, for Cyrillic, the exclusive letters `ыэё` (Russian) and `іїєґ` (Ukrainian). `CONFLICT` becomes `[(und)]` when the lock is on. Bilingual recognition vetoes a conflicting side so `compareResults` can use the other one. Text shorter than 12 letters is `WEAK` and is never dropped.
- **Diagnostics**: `Log.i("asrLanguage", ...)` records the model, soft/hard mode, requested and chosen language, the top two language scores, the guard verdict, and the latency. It does not log transcript text.

## 4. Sentence splits before translation

Splitting is in `Translator.performTextTranslation` and applies only to backends that are **not** Mozilla. Bergamot splits in native code. Java does not split that path again.

Steps for non-Mozilla backends:

1. `BreakIterator.getSentenceInstance(inputLanguage.getLocale())` splits on the ICU rules for the source language. Chinese, Japanese, and English boundaries differ because the locale differs.
2. Neighboring pieces are joined when their token counts sum to less than the budget, or when the following piece has fewer than 5 tokens. The scan repeats until no neighboring pair can be joined.
3. The budget is fixed per model: 200 tokens for NLLB and Madlad, 5000 tokens for HY-MT. Both sites are marked TODO in the source. They are not measured optima.
4. Each piece is translated on its own and joined with a single space.

The 5-token rule merges fragments that ICU split too finely, such as a short piece after a decimal or an abbreviation. It does not promise a complete meaning. It only avoids sending a very short fragment to the model alone.

Each piece is translated in this order: a whole-piece dictionary hit, otherwise a whole-sentence Tatoeba hit, otherwise the neural network. The dictionary returns a translation only when the whole piece is one entry. Tatoeba normalizes with NFC, unified quotes, collapsed whitespace, and lowercase, hashes the first 8 bytes of SHAKE-256, then checks the original text so a hash collision is not accepted. A hit uses the stored translation and does not run the model.

The Mozilla path calls `BergamotTranslator.translateMultiple` on the whole text. `max-length-break: 256` in the native config is Bergamot's own break length, in its word pieces, not the Java token budget above.

If "use Mozilla in voice modes" is set, Conversation and WalkieTalkie change `performMode` to `MOZILLA` even when Madlad or HY-MT is selected. A language pair Mozilla does not support falls back to the selected large model and tries again. Text mode does not use this switch.

## 5. Three translation backends

The mode integer is stored in the preference `selectedTranslationModel`. The default is `MOZILLA` (7). Do not change what these numbers mean.

| Constant | Value | Runtime model | Vocabulary |
| --- | --- | --- | --- |
| `NLLB` / `NLLB_CACHE` | 0 / 6 | Legacy NLLB ONNX, 12 layers, hidden 1024, 16 heads, head dim 64 | `sentencepiece_bpe.model` |
| `MADLAD_CACHE` | 5 | Madlad 400 3B, 4-bit weight ONNX, 32 layers, hidden 1024, 16 heads, head dim 128 | `Translation/Madlad/spiece.model` |
| `MOZILLA` | 7 | One Bergamot pair per language, pivoting through English | vocab in the model directory |
| `HY_MT` | 8 | HY-MT 1.5, `model_int8_final.onnx`, 32 layers, hidden 2048 | `Translation/HY-MT/tokenizer.json` |

Internal storage paths:

- Madlad: `Translation/Madlad/Int4Acc4/madlad_{encoder,decoder,cache_initializer}_4bit.onnx`, plus `madlad_embed_8bit.onnx`.
- HY-MT: `Translation/HY-MT/model_int8_final.onnx`. One decoder session, no separate encoder file.
- Mozilla: `Translation/Mozilla/{lang}/{lang}en` and `{lang}/en{lang}`. Each directory needs the model, srcvocab, trgvocab (or one shared vocab), and a `lex.*` shortlist.

### Madlad

Before SentencePiece encoding, the text is prefixed with `<2target-language-code> `. The source language is not written into the tokens. Ids match SentencePiece one to one. They are not shifted by 1. `</s>` is appended.

Decoding is beam search with a cache, but voice mode sets the beam to 1, so it degenerates to greedy. Text mode uses the beam from settings. Structurally:

- The first step uses `input_ids = 0` and a batch of 1.
- When the beam is greater than 1, the batch becomes the beam width from the second step. Encoder KV and the attention mask are copied onto each beam.
- Embeddings are computed alone in `embedSession`. The decoder consumes `embed_matrix`.
- The end token is SentencePiece `</s>`.

Beam scoring constants in `Translator`:

- `WORD_REWARD = 0.5`
- `LENGTH_ALPHA = 0` (length normalization is off because the word reward already lengthens sentences)
- `SIBLING_PENALTY = 0.2`
- `PATIENCE = 1.5`, so finished sentences kept are about `1.5 * beamSize`
- `EOS_PENALTY = 0`
- `PARTIAL_RESULT_MARGIN = 0.2`, used to pick the best unfinished candidate when stopping early

These numbers also apply to the NLLB cache decode loop and the HY-MT decode loop.

### HY-MT

HY-MT does not use SentencePiece. `HuggingFaceTokenizer` encodes the whole prompt. A Chinese source language uses a Chinese instruction. Otherwise the instruction is English. The target language name comes from the Chinese or English name in `hy_mt_supported_languages.xml`. The prompt ends with `<｜hy_Assistant｜>`. The model should continue with the translation only.

The end token is not `</s>`. It is `<｜hy_place▁holder▁no▁2｜>`. The KV cache stores 4 groups (GQA; the comment says 16 real heads and a group size of 4). Cache tensor names have no `.decoder` segment, unlike Madlad and NLLB.

The first step sends the prompted `input_ids` into the decoder. It does not send a lone start id.

### Mozilla Bergamot

`bergamot_translator_interface` loads the local C++ library. Each non-English language caches two models: `{lang}en` and `en{lang}`. The translation rules are:

- Same source and target: return the text unchanged.
- Neither side is English: `pivotMultiple`, English first, then the target language.
- Only one side is not English: use that one direction.

The generation config is fixed at beam 1, `normalize: 1.0`, `word-penalty: 0`, `max-length-break: 256`, `max-length-factor: 3`, 1 CPU thread, `workspace: 256`, and soft alignment. File names containing `intgemm8.bin` or `intgemm.alphas.bin` add the matching int8 GEMM precision. The Java beam and the dictionary-style sentence joining do not enter this path. A short sentence can still be taken by the dictionary or Tatoeba first.

### NLLB

NLLB can still be loaded. It is not the 3.0 default. SentencePiece ids are shifted by 1, and 0, 1, and 2 are mapped to NLLB special ids. The sequence starts with the source-language id (`256000 + language index + 1`) and ends with `</s>`. The first cache-decode step uses `input_ids = 2`.

## 6. Speech output

The app has no acoustic model and no vocoder of its own. `TTSEngine` wraps Android `android.speech.tts.TextToSpeech`. Language and voice depend on the system engine selected on the phone. The "Text to Speech" row in settings opens the system TTS settings. Google TTS is a README recommendation, not a dependency.

Queue behavior:

- There is one queue inside `TTSEngine`. `SpeechQueue` holds what is waiting and submits each item only once. The system engine **receives one sentence at a time**: the next sentence is submitted only after the previous `onDone` (or a failure, an interruption, or a watchdog timeout). A new sentence does not cut off the one being spoken.
- Submissions to the system engine use only `QUEUE_ADD`. A new sentence is never started with `QUEUE_FLUSH`. Only the text-mode speak button replaces the previous sentence.
- `TTS` is a thin wrapper. `setLanguage` / `setSpeechRate` run only when the value actually changes (setting them again before every sentence makes some engines swallow the start). Empty text and punctuation-only text are not submitted.
- Engine settling: after `onDone`, any `stop()`, and a real language change, the next sentence waits `TTS.ENGINE_SETTLE_MS` (250 ms). `onDone` fires before the audio output has drained. Submitting immediately makes the engine drop the first words of the new sentence. `TTSEngine` implements this with a `notBefore` timestamp. An early `pump` only schedules a delayed task. It does not pop the queue head.
- Bluetooth sentence onset: the same sentence on the loudspeaker is complete. An idle headphone media link sleeps, and the first few hundred milliseconds of a new clip are dropped. The onset is complete when music is already playing in the background. While the simultaneous-interpretation service is up and the output is A2DP, SCO, a hearing aid, or LE Audio, `BluetoothRouteWake` keeps writing an inaudible media stream with the same `AudioAttributes` as TTS until the user leaves that mode or the headset disconnects. The stream has already started before the microphone settle time (`MIC_SETTLE_MILLIS`, 2 seconds). That settle time waits for the headset to leave sleep. Speech itself does not add another wait for this sentence. `tts.stop()` does not stop this stream.
- The system engine is set to `AudioAttributes(USAGE_MEDIA, CONTENT_TYPE_SPEECH)`. The default accessibility usage gets a fade-in on some ROMs, which sounds like the first few words were skipped. The round holds `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` and releases it when the queue is empty or on `stop()` / `pause()` / `shutdown()`. If another app takes focus, the flag is dropped and the queue is not paused by this code. Engine callbacks still drive the queue.
- The text-mode speak button has its own `TTS` instance and is the only place that replaces the previous sentence: if speech is in progress it calls `stop()`, waits 250 ms, then submits the new sentence with `QUEUE_ADD`. If nothing is speaking it uses `QUEUE_ADD` directly. It does not use `QUEUE_FLUSH`.
- `logcat -s tts_engine` prints each submission (id, character count, milliseconds since the last `onDone` / `stop()`, first 60 characters), the `onStart` delay relative to submission, and the finished duration. That separates "the text was wrong" from "it was submitted hard against the previous sentence".
- Pieces of one translation are spoken in arrival order. `onUtteranceFinished` runs only when the last piece of that translation has been spoken, and `last` is true only when the **whole queue** is empty. When the first sentence starts, the service stops the microphone if no Bluetooth headset is connected. After the queue is fully spoken it waits another 500 ms and opens the microphone. An SCO headset does not stop the microphone.
- An engine error drops only the sentence that failed. It does not clear the whole queue.
- The watchdog only recovers: if `onStart` does not arrive within 5 seconds of submission, or `onDone` does not arrive long after the sentence should have finished, it calls `stop()` on the engine, drops that sentence, and continues. It does not replay the text and it does not flush.
- When WalkieTalkie detects a new utterance it `pause()`s current speech (the sentence being spoken goes back to the head of the queue). After that utterance's `onVoiceEnd` it delays and then `resume()`s from the sentence that was put back.
- When TalkBack interrupts, the interrupted sentence goes back to the head of the queue and is submitted again after 1.5 seconds. That lets TalkBack finish a run of prompts so the two voices do not overlap. The same sentence is rescheduled only once, to avoid a loop. The user's own stop clears the queue and does not replay.
- Conversation's automatic speech switch is `conversationAutoTTS`. WalkieTalkie's is `walkieTalkieAutoTTS`. Both default to on. If the target language is not in the system TTS list, only the text is shown.

## 7. Which path each mode takes

| | Segmentation | Recognition | Sentence split | Translation | Speech |
| --- | --- | --- | --- | --- | --- |
| Conversation | Automatic VAD, one local language | Whisper greedy, one language | On the receiver, and only if not Mozilla | On the receiver, beam 1 | System TTS; a headset can run with the mic |
| WalkieTalkie | Automatic or push-to-talk | The same audio decoded once per language | On this phone, and only if not Mozilla | On this phone, beam 1, into the other language | System TTS, paused when speech starts |
| Text | None | None | Only if not Mozilla | On this phone, beam from settings | Button, same TTS |

Conversation's speech beam constant is 4 and its translation beam constant is 1. The decoder does not currently use the former.

## 8. Easy mistakes when changing this

- Do not turn recognition into partial text while the person is still talking unless `Recorder.executeEnd` and every `onVoice` caller change together. `onVoice` means "the complete PCM of one utterance".
- `speechTimeout`, `prevVoiceDuration`, and the amplitude threshold are user settings. The 29 second cap and the 512-sample frame are code constants.
- Whisper's `beamSize` argument is dead. The real beam is in `Translator.executeCacheDecoder`, and voice mode passes 1.
- Text of 7 words or more makes `LanguageDetector` return `und` immediately. WalkieTalkie then depends more on the mean log probability of the two Whisper hypotheses.
- Java sentence splitting does not apply to Bergamot. Changing the ICU split does not change Mozilla's breaks.
- HY-MT's prompt, end token, and cache tensor names differ from the other models. Replacing the model has to change all three together.
- A Bergamot pair that is not English always goes through English. A quality problem can be in either hop.
- Speech quality and language coverage are decided by the system TTS, not by a model in this repository.
- Do not call `speak` on the system engine outside `TTSEngine` / `TTS`, and do not use `QUEUE_FLUSH` to start a new sentence. That cuts off the sentence being spoken. The engine also reports the cut sentence through `onStop`, and the queue state follows it into the wrong place. The text-mode speak button is the only place that needs to replace the previous sentence.
- Do not call `speak()` inside the same synchronous block as `stop()` or an `onDone` callback, and do not call `speak()` immediately after `setLanguage()`. All three submit too close together and the engine drops the first words of the new request. Use `TTSEngine`'s `notBefore`, or the delayed submit on the text-mode button.
