<img src="images/icon.png" width="180" alt="TransPocket">

# TransPocket

[中文](README.zh-CN.md)

TransPocket is an offline Android app for real-time translation. The current version is **0.1.0**. Speech recognition, translation, and language detection all run on the phone. The app has no server of its own and does not upload speech or text.

**This repository is a fork of [RTranslator](https://github.com/niedev/RTranslator).** The upstream author is Luca Martino (GitHub: [niedev](https://github.com/niedev)). The upstream program is licensed under the Apache License 2.0. This fork renames the app to TransPocket and adds simultaneous interpretation, more on-device models, and color palettes on top of upstream 3.0.0-beta2. TransPocket is not the upstream project and is not maintained by upstream.

## Four ways to use it

**Text translation.** Type text and translate it on the device.

**Walkie-talkie.** One phone, two languages, taking turns. The app detects which language is being spoken, translates it into the other language, and reads it with the system text-to-speech engine.

**Conversation.** Several phones running this app connect over Bluetooth Low Energy. Each phone recognizes only its own side of the conversation, then sends the text and a language code to the other phones. The receiver translates and speaks on its own device. Audio does not leave the phone. More than two phones can be connected at once. Conversation and walkie-talkie can keep running in the background. Some systems limit background power use; leaving the app in the foreground is more reliable in that case.

**Simultaneous interpretation.** One phone listens, translates sentence by sentence, and speaks the result. By default, Silero VAD splits speech on pauses. The Auto speak setting can turn speech on, turn it off, or follow the current playback device each time you enter the screen. The button on the screen affects only the current session.

## What 0.1.0 already includes

These are the changes in this repository relative to upstream RTranslator 3.0.0-beta2:

- Simultaneous interpretation. Silero VAD (about 2 MB) is downloaded and enabled by default and splits speech on pauses.
- Translation models run on the phone with llama.cpp: Qwen3.5 0.8B (performance, about 1.4 GB of memory), Index-Translate 2B (quality, the default, about 1.9 GB), and Google TranslateGemma 4B (about 4 GB, Gemma terms). Each has a quantization menu (Q4_0, IQ4_NL, IQ4_XS, Q4_K, Q8_0 where available), fastest on phone CPUs first. Mozilla Bergamot, Madlad 400 3B (int4) and HY-MT 1.5 1.8B (int8) are under "Other models".
- Simultaneous interpretation can stream: the live transcript is shown while you speak, and its stable start is translated before you pause (both on by default in Settings).
- Speech recognition can be Whisper Small, Whisper Large V3 Turbo, Qwen3-ASR 0.6B, or Qwen3-ASR 1.7B. The 1.7B model needs a phone with about 8 GB of memory.
- Speech uses the phone's system text-to-speech engine. A sentence is spoken as soon as it is finished, and the next sentence waits in a queue. The whole passage is not synthesized in one request. With Bluetooth headphones, the media route is kept awake so the start of a sentence is not eaten by headphone sleep.
- Model downloads continue after the app leaves the foreground. Loading and switching models show stage progress.
- Five palettes: autumn gold, bamboo, indigo, sakura, and wisteria. Changing the palette also changes the launcher icon and the cold-start screen. Every icon uses the light mark on that palette color.
- The displayed name is TransPocket.

The dictionary, the Tatoeba sentence bank, and beam search remain part of text-translation quality. Voice modes use a beam width of 1. Do not change `EOS_PENALTY`, `WORD_REWARD`, `LENGTH_ALPHA`, `SIBLING_PENALTY`, or the beam width without a quality comparison.

NLLB remains in the code as a legacy path. It is not the current default model.

## Download and install

Install `TransPocket-0.1.0.apk` from this repository's [Releases](https://github.com/maoruiQa/TransPocket/releases). Only **arm64-v8a** is supported. `minSdk` is 28. A phone with about **6 GB** of memory is recommended. An x86_64 emulator cannot install this package.

The application id is `io.github.maoruiqa.transpocket`. It does not replace an installed copy of RTranslator (`nie.translator.rtranslator`), and it does not replace an earlier TransPocket build that used that id. `versionCode` is 28. The two packages can sit side by side. Uninstall the old one from system settings if you do not want it.

The first launch needs a network connection to download the selected models. The download size depends on the chosen models. It is not a fixed 1.2 GB. After the download, the app can be used offline. Models come from their own release pages, including Hugging Face. They are not in this git repository.

[Sideloading.md](Sideloading.md) keeps the upstream 2.0 steps for placing files by hand. That attachment is not the TransPocket 0.1.0 model pack.

## Languages and speech

The available languages depend on the current recognition model and translation model. Madlad covers the widest set. Mozilla models are downloaded per language. Low-quality languages can be enabled in settings.

Speech language and voice quality depend on the text-to-speech engine selected in the system. Google Text-to-speech is a good choice. Other engines work too. After changing the system engine, swipe TransPocket away from recents and open it again.

## Building

From the repository root in PowerShell:

```
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

The release package still uses the Android debug signing config. That is the upstream build setup. Do not commit a keystore. The toolchain is Android Gradle Plugin 8.2.2, Gradle 8.12, NDK `28.0.12674087`, and CMake 3.22.1. Change native code only when the JNI boundary requires it.

Implementation notes: [docs/speech-and-translation.md](docs/speech-and-translation.md) and [docs/conversation.md](docs/conversation.md). Chinese copies are `docs/speech-and-translation.zh-CN.md` and `docs/conversation.zh-CN.md`.

## License

Read these three files together:

| File | Role |
| --- | --- |
| [LICENSE.txt](LICENSE.txt) | Upstream RTranslator Apache License 2.0, unmodified |
| [LICENSE](LICENSE) | GNU General Public License v3, full text |
| [NOTICE.txt](NOTICE.txt) | Upstream notice and the license note for this fork |

Upstream RTranslator is copyright Luca Martino and the other upstream contributors. Apache 2.0 file headers already present in source files stay in force. This repository does not rewrite them into GPL headers. The Apache License 2.0 is compatible with GPLv3, so **changes in this fork** and **combined works distributed from this repository** are distributed under GPLv3. Anyone who receives the original Apache 2.0 portions must still keep the attribution, the NOTICE, and the patent grant in that license.

Third-party code under `app/src/main/cpp` and `app/libs` keeps its own licenses, including ONNX Runtime, SentencePiece, Bergamot, FastText, sherpa-onnx, sqlite4java, and the Bluetooth stack used by Conversation mode. Model weights follow the terms of their publishers. Gemma models follow the Gemma terms.

Contributions to this repository are licensed under GPLv3.

## Privacy

TransPocket has no accounts, no analytics, and no backend for speech or text. The network is used to download models. The privacy-policy text is kept from upstream, with a note that this copy belongs to this fork:

- [privacy/Privacy_Policy_en.md](privacy/Privacy_Policy_en.md)
- [privacy/Privacy_Policy_it.md](privacy/Privacy_Policy_it.md)

## Upstream

Upstream repository: [https://github.com/niedev/RTranslator](https://github.com/niedev/RTranslator)

Upstream funding, donations, and the Product Hunt page belong to the upstream project. This fork does not take donations pointed at those pages. Other projects that use the upstream code (WhisperIMEplus, WhisperJET) belong to the upstream ecosystem.

File bugs for this repository on [maoruiQa/TransPocket](https://github.com/maoruiQa/TransPocket) Issues. Do not send this fork's bugs to the upstream maintainer. Contribution habits are in [CONTRIBUTING.md](CONTRIBUTING.md).
