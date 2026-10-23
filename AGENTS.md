# Repository Guidelines

RTranslator 3.0 (`v3.00`, `3.0.0-beta2`) is an offline Android app for real-time translation. Speech recognition, translation, and language detection run on the device. The app does not have a backend.

Read `CONTRIBUTING.md` before a change that would be sent upstream. The maintainer rejects drive-by refactors and pull requests that are mostly unreviewed model output.

## Project structure

Single Gradle module `:app`. Application id and namespace: `nie.translator.rtranslator`. Java UI and services live under `app/src/main/java/nie/translator/rtranslator/`. Native code lives under `app/src/main/cpp/src/`. Layouts, strings, and language lists live under `app/src/main/res/`.

- `Global` is the `Application`. It owns shared preferences, language lists, the `Translator`, the `Recognizer`, TTS, Bluetooth, and download state. The three product modes are `TEXT_TRANSLATION_MODE`, `WALKIE_TALKIE_MODE`, and `CONVERSATION_MODE`.
- `voice_translation/` is the product. `_text_translation`, `_walkie_talkie_mode`, and `_conversation_mode` are the three screens. The leading underscore is part of the package name; keep it.
- `voice_translation/neural_networks/translation/Translator.java` runs text translation. `voice/Recognizer.java` and `voice/Recorder.java` run Whisper speech recognition and microphone capture. `TTS` / `TTSEngine` use the system text-to-speech engine.
- `LanguageResourcesManager` loads per-language resources (Mozilla models, dictionaries, Tatoeba links). `LanguageDetector` uses FastText. Lingua is the other open-source detector. ML Kit is gone.
- `bluetooth/` is the vendored Bluetooth LE stack used by Conversation mode. `ConversationBluetoothCommunicator` sits on top of it. Do not treat a Bluetooth change as a UI-only change.
- `downloader/` fetches model files on first launch. `databases/general` is Room (recent peers). `databases/tatoeba` reads the Tatoeba SQLite file through sqlite4java.
- `tools/nn` holds ONNX tensor helpers and the native KV-cache container. `tools/gui` holds custom views. `settings/` and `res/xml/preferences.xml` are the settings UI.
- `access/` is the first-launch flow (notice, user data, model download).

Native libraries, loaded from Java:

| Library | Java entry | Role |
| --- | --- | --- |
| `sentencepiece-interface` | `SentencePieceProcessorJava` | SentencePiece tokenization |
| `cache_container_native` | `CacheContainerNative` | ONNX KV-cache storage |
| `dictionary_translator_interface` | `DictionaryTranslator` | Multilingual dictionary lookup |
| `bergamot_translator_interface` | `BergamotTranslator` | Mozilla Bergamot translation |
| `fasttext-lib` | `LanguageDetector` | FastText language ID |

CMake root: `app/src/main/cpp/src/CMakeLists.txt`. Vendored native trees under `cpp/src/libs/` and `cpp/src/*/libs/` are third-party. Change them only when the JNI boundary requires it.

ONNX models are not in git. The app downloads them into app storage (`Android/data/nie.translator.rtranslator/files`, then private storage). See `Sideloading.md`.

## Translation and speech pipeline

`Translator` mode constants: `NLLB` 0, `MADLAD` 3, `MADLAD_CACHE` 5, `NLLB_CACHE` 6, `MOZILLA` 7, `HY_MT` 8. The saved preference `selectedTranslationModel` defaults to `MOZILLA`. Current 3.0 choices are Mozilla Bergamot, Madlad 400 3B (`MADLAD_CACHE`), and HY-MT 1.5 1.8B (`HY_MT`). NLLB remains in code as a legacy path.

Mozilla models are language resources, not one shared ONNX session. Madlad and HY-MT use ONNX Runtime sessions (encoder, decoder, cache, embeddings). Beam search, length and sibling penalties, dictionary replacement, and Tatoeba links are inside `Translator`. Do not retune `EOS_PENALTY`, `WORD_REWARD`, `LENGTH_ALPHA`, `SIBLING_PENALTY`, or `beamSize` without saying so and without a quality check.

Whisper runs through ONNX Runtime in `Recognizer`. Silero VAD is configured in `Global`. Low-RAM phones use a reduced Whisper mode (`whisperReducedRam`). A phone needs about 6 GB of RAM to run the app safely.

Supported language lists are XML in `res/raw/` (`mozilla_supported_languages.xml`, `hy_mt_supported_languages.xml`, `madlad_supported_launguages.xml`, `whisper_supported_languages.xml`, plus the legacy NLLB lists). The Madlad filename typo is real; do not rename it without updating every reader.

Conversation mode captures speech on the sender, sends text over Bluetooth LE, and translates plus speaks on the receiver. WalkieTalkie mode is one phone, two languages, turn-based. Text mode translates typed text only. Background execution is limited to Conversation and WalkieTalkie.

## Build and test

From the repo root in PowerShell:

- `.\gradlew.bat assembleDebug` builds the debug APK.
- `.\gradlew.bat assembleRelease` builds the release APK. Release still uses the debug signing config.
- `.\gradlew.bat test` runs the local unit tests.
- `.\gradlew.bat connectedDebugAndroidTest` needs an arm64 device or emulator.

Toolchain pinned by the project:

- Android Gradle Plugin 8.2.2, Gradle 8.12.
- `compileSdk` and `targetSdkVersion` 36, `minSdkVersion` 28.
- NDK `28.0.12674087`, CMake 3.22.1, C++17, generator Ninja.
- `abiFilters` is `arm64-v8a` only. An x86_64 emulator will not run this APK.

The native build is the slow part. Do not clean `.cxx`, `.externalNativeBuild`, or `.gradle` unless a native change requires it.

`app/src/test` and `app/src/androidTest` only contain the template examples. There is no model or Bluetooth test harness. After a logic change, say what you could not run on a device.

## Coding style

Match the file you edit.

- Java is indented with 4 spaces. `CONTRIBUTING.md` says tabs, but the tree uses spaces. Do not reindent a file.
- Opening braces stay on the same line. Fields and methods stay in the existing order unless the change needs a move.
- Names are Android-era Java: explicit types, `m` prefixes are not used, listeners are abstract classes or interfaces on the owning type. Keep existing spelling (`MicrophoneComunicable`, `launguages` in the Madlad raw file).
- New Java files that sit next to licensed sources keep the Apache 2.0 header used in that package.
- The app module is Java. Do not add Kotlin, Jetpack Compose, or a second module for a small change.
- Comments explain a non-obvious constraint (memory, JNI lifetime, Bluetooth serialization). Do not narrate the change.

Keep edits local. `Translator.java`, `Recognizer.java`, `Global.java`, and the Bluetooth stack are large and easy to break by "cleanup". Do not rename packages, reformat, or replace `AsyncTask`, `Handler`, or the existing callback types unless the task is that replacement.

## Safe change map

| Task | Start here |
| --- | --- |
| Text translation quality or model I/O | `Translator`, `Tokenizer`, `CacheContainerNative` |
| Mozilla / Bergamot | `BergamotTranslator`, `LanguageResourcesManager` |
| Dictionaries or Tatoeba | `DictionaryTranslator`, `databases/tatoeba` |
| Speech recognition or VAD | `Recognizer`, `Recorder`, `Global` |
| WalkieTalkie flow | `WalkieTalkieService`, `WalkieTalkieFragment` |
| Conversation flow | `ConversationService`, `ConversationMainFragment`, `ConversationBluetoothCommunicator` |
| Bluetooth link | `bluetooth/` (`Channel`, client/server connection, `BluetoothCommunicator`) |
| Model download | `downloader/`, `LoadingActivity`, `AccessActivity` |
| Settings or language lists | `res/xml/preferences.xml`, `res/raw/*languages*.xml`, `settings/` |
| Strings | `res/values/strings.xml` and the existing locale folders only |

## What not to change casually

- Model mode integers and preference keys. Old installs persist `selectedTranslationModel`.
- JNI signatures (`native` methods and the matching C++ names). A signature change needs both sides in the same change.
- ONNX input and output names, especially KV-cache tensors (`present.*`). `CacheContainerNative` branches on `HY_MT`.
- Bluetooth message framing and the parallel-channel write/read/notify serialization. Conversation mode is not compatible with pre-3.0-beta2 peers.
- `minSdk`, `abiFilters`, NDK, and CMake versions.
- Signing. `gradle.properties` points at the local Android debug keystore. Do not commit a keystore, and do not replace debug signing with a personal release key.
- Generated or local files: `.gradle/`, `build/`, `app/.cxx/`, `.externalNativeBuild/`, `local.properties`, `.idea/`, `*.iml`.

## Verification

A UI or translation change is not done when Gradle succeeds. Check the affected mode on an arm64 device:

- Text: both directions, empty input, a long sentence, and a language pair that uses the selected model.
- WalkieTalkie: language detection, turn-taking, TTS start/stop, and the mic mute path.
- Conversation: pair, send one utterance each way, disconnect one peer, and confirm the other peers stay in the session.
- Download: first launch with no models, and a relaunch when models are already present.

State which of these you actually ran.

## Upstream expectations

`CONTRIBUTING.md` is the maintainer's review policy: describe the pull request, match local style, do not refactor, and ask first for a change over about 400 lines. Once a pull request is marked ready, do not push more commits until review.

`TODO_LIST.md` is partly stale against 3.0 (some listed items already exist). Confirm the current code before treating a row as open work.

Privacy: do not add analytics, accounts, or a network path for audio or text. Model download is the expected network use.
