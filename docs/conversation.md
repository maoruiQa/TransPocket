# Conversation mode

中文：[conversation.zh-CN.md](conversation.zh-CN.md)

This note describes the Conversation implementation in TransPocket `0.1.0`. It is a fork of RTranslator `v3.00` (`3.0.0-beta2`). The conclusions come from the current source, not from the product summary in the README.

Conversation is multi-phone, two-way spoken translation. Each phone recognizes only its own speech, then sends the original text and a language code to the other phones over Bluetooth Low Energy. The receiver translates that text into its own language and reads it with the system TTS. Audio does not leave the phone.

## 1. How it differs from the other two modes

All three modes share the `Translator`, `Recognizer`, and Silero VAD in `Global`. The data paths differ.

| | Text | WalkieTalkie | Conversation |
| --- | --- | --- | --- |
| Devices | One | One | Two or more |
| Recognition | None | Listens for two languages at once | Listens for the local user's one language |
| Translation happens | On this phone | On this phone | On the receiver |
| Network | None | None | None, BLE only |
| Foreground service | None | `WalkieTalkieService` | `ConversationService` |

WalkieTalkie detects, translates, and speaks on the same phone. Conversation treats "what I said" as a message and postpones translation until the listening side. Each phone only has to load "their language → my language". It does not have to know every listener's language at speak time and translate once per listener.

## 2. How the objects are wired

```
VoiceTranslationActivity
  PairingFragment                         discover, request, accept
  ConversationFragment
    ConversationMainFragment              microphone, keyboard, message list
    PeersInfoFragment                     people already connected, add more
        | Messenger
        v
ConversationService                       record, recognize, receive, translate, TTS
        |
        +-- Recorder + Recognizer         local speech
        +-- Translator                    text that arrived
        +-- TTSEngine                     system TTS
        |
        v
Global.ConversationBluetoothCommunicator  app headers m / d / i / l
        |
        v
BluetoothCommunicator
  BluetoothConnectionClient               links this phone opened with connect
  BluetoothConnectionServer               links other phones opened to us
        |
        v
one Channel per Peer                      fragmentation, queues, reconnect
```

`Global` is the `Application`. `Translator`, `Recognizer`, VAD, and `ConversationBluetoothCommunicator` all hang off it. The activity and the service share those instances. `ConversationBluetoothCommunicator` is created by `initializeBluetoothCommunicator()` when the pairing screen opens. The strategy is fixed at `STRATEGY_P2P_WITH_RECONNECTION`, and `bluetoothChange=false`: the app does not toggle the system Bluetooth radio itself.

The UI and the service do not call each other directly. `ConversationMainFragment` binds to `ConversationService`. Both sides pass a `Bundle` through a `Messenger`. Commands from the UI to the service include `START_MIC`, `STOP_MIC`, `START_SOUND`, `STOP_SOUND`, `RECEIVE_TEXT`, and `SPEAK_TEXT`. Events from the service to the UI include `ON_MESSAGE`, `ON_VOICE_STARTED`, `ON_VOLUME_LEVEL`, and `ON_TTS_STARTED`.

`ConversationService` is a foreground service. With record permission its type is `FOREGROUND_SERVICE_TYPE_MICROPHONE`. Otherwise it drops to `DATA_SYNC`. It holds a 10 minute `PARTIAL_WAKE_LOCK` and renews it 10 seconds before expiry so the screen-off state does not suspend the process.

## 3. Lifecycle of one session

1. The user enters pairing. `PairingFragment` calls `startAdvertising()` and `startDiscovery()` together. Both sides advertise and both sides scan.
2. The scan accepts only service data. The advertising packet has no device name. The service UUID is `00007B3D-0000-1000-8000-00805F9B34FB`. The service data is `uniqueName`.
3. `uniqueName` is the display name plus 2 persistent random characters. Those characters are stored in the `default` SharedPreferences key `bluetoothNameId`. The display name can change. Identity is `uniqueName`. The Bluetooth address often changes after a disconnect and cannot be the identity.
4. Tapping a discovered person calls `connect()`. The initiator is the GATT client. The other phone is the GATT server. One phone can be the client on some links and the server on others. `BluetoothConnectionClient` and `BluetoothConnectionServer` are both always present.
5. After the server receives a connection request it shows a confirm dialog with a 15 second timeout. Accept calls `acceptConnection()`. Reject calls `rejectConnection()`. The reject GATT status is `150`, inside the application range 148–159, so the client can tell it apart from a system error.
6. After either side's `onConnectionSuccess`, `PairingFragment` switches to `ConversationFragment`. That screen has two pages: the conversation page and the connection page.
7. When `ConversationMainFragment` binds, it calls `startService` first if the service is not up. In `onCreate` the service loads translation resources for the local target language and creates the `Recorder` only after that succeeds.
8. When the last person disconnects, the service calls `stopSelf()` and the UI returns to `PairingFragment`. If the user confirms exit while connections remain, it first calls `disconnectFromAll()`.

The connection page can keep searching and add more people during a session. One person disconnecting does not end the session. It ends when `peersLeft == 0`. That differs from the old behavior, where one device dropping out left Conversation entirely.

## 4. Bluetooth handshake

After the client connects, the steps run in this order. Handshake packets do not enter the normal message queue. The peer is not marked connected yet, and a send from that queue is blocked by the `isConnected` check.

1. `onServicesDiscovered`: if the hardware supports it, the client requests LE 2M PHY and sets the connection priority to `CONNECTION_PRIORITY_HIGH`.
2. The client writes 247 bytes to `MTU_REQUEST_UUID`. That is not the negotiated number itself. It is a long write used to probe the length that can be written now.
3. The server takes that write length minus 1 as its own fragment length, then indicates that length back through `MTU_RESPONSE_UUID`.
4. If the peer reports a length below `PREFERRED_MTU` (247), the client calls `requestMtu(247)`. In `onMtuChanged` the fragment length becomes `mtu - DATA_MARGIN`. `DATA_MARGIN` is 6. Before negotiation finishes, a `Channel`'s default fragment length is 17.
5. The client subscribes to the indicate characteristics for the connection response, messages, data, rename, and disconnect, then writes `CONNECTION_REQUEST_UUID` with its own `uniqueName`. A reconnect writes `CONNECTION_RESUMED_RECEIVE_UUID`, not a new connection request.
6. The server shows accept or reject. After accept it indicates `CONNECTION_RESPONSE_UUID` with `ACCEPT` (GATT success, 0) or `REJECT` (150).
7. The client receives accept. Both sides call `onConnectionSuccess`. From that moment, application messages can use the message queue.

The whole handshake has a 10 second `CONNECTION_COMPLETE_TIMEOUT`. On timeout the unfinished channel is torn down.

On reconnect the address has often changed. The server does not recognize the old channel in `onConnectionStateChange` and first creates a placeholder channel. When the resume write carries `uniqueName`, the server finds the old channel that is reconnecting under the same name, swaps in the new connection's device and parameters, deletes the placeholder, and finishes the handshake on the old channel. The old protocol wrote a single character `1`. A resume payload of length 1 or less is rejected. Beta 2 links are therefore incompatible with earlier app versions.

## 5. Four application messages

Above BLE, `ConversationBluetoothCommunicator` uses a 1-character header. Text uses `sendMessage`. The avatar uses `sendData`. The two queues are independent, so an avatar transfer does not block a later interpretation text, and the reverse is also true. `receiver == null` means every peer that has not entered disconnect.

| Header | Channel | Who sends first | Contents |
| --- | --- | --- | --- |
| `d` | message | client, in `onConnectionSuccess` | `Settings.Secure.ANDROID_ID` |
| `i` | data | client, after receiving the peer's `d` | a 160 px JPEG at quality 70, or the string `null` |
| `l` | message | client, after receiving the peer's `i` | the source language the peer should load |
| `m` | message | either side, after recognition or a keyboard send | the text to interpret |

The identity exchange after the handshake is a fixed relay. The role follows the GATT link's `source`, not "who spoke first":

1. The client sends `d`.
2. The server receives `d` and replies with its own `d`. Both sides write or update the Room recent peer. The key is the Android id plus `uniqueName`.
3. The client receives `d` and sends `i`.
4. The server receives `i`, replies with its own `i`, and updates the avatar.
5. The client receives `i` and sends `l`.
6. Both sides call `loadSrcLangResourcesForPeer` when they receive `l`. The server uses the condition below to decide whether to send its own language back to the client.

The bodies of `l` and `m` use the same tail encoding:

```
{payload}{languageCode}{character count of languageCode, one decimal digit}
```

For language code `en` and text `hello`, the `m` body is `helloen2`. Parsing reads the last character as the language-code length, cuts the language code off the end, and keeps the rest as the body. The language code must be 1 to 9 characters. A longer code such as `zh-Hans` cuts the boundary in the wrong place.

The condition for the server's language reply does not match the comment. The comment says "reply only the first time the peer's language arrives". The code reads `isPeerLoaded()` before calling `loadSrcLangResourcesForPeer`, and that method returns true when the peer is already in `conversationSrcResources`. The first `l` therefore sees `firstLoad` as false and the server does not reply. Later language updates do reply. The client's language is sent after the avatar exchange, and the server's language reply depends on this check. Before changing it, compare a real two-phone handshake. Do not change it from the comment alone.

When the user changes their own language during a session, `Global.setLanguage()` loads `loadTgtLangResourcesForConversation` again and then `sendLanguage()` broadcasts to everyone connected.

## 6. BLE fragmentation

A `Message` becomes a series of `BluetoothMessage`s. Each piece is laid out as:

```
id(4) + sequence(3) + type(1) + data
```

`id` and `sequence` are not decimal integers. They are digits in the character set from `BluetoothTools.getSupportedUTFCharacters()`. `type` is the character `1` (`NON_FINAL`) or `2` (`FINAL`). `data` is one slice of "1-character header + UTF-8 body". The slice length is the channel's current `bluetoothMessagesLength`, after subtracting these 8 header bytes.

Pieces with the same `id` are joined by sequence. The `Message` is restored only when `FINAL` is seen. Message ids and data ids are counted separately. A channel sends the pieces of only one logical message at a time. The next queued message starts after that one finishes. A failed write retries the current piece immediately.

Characteristic write, read, and notification on one channel enter `pendingCommands` and run serially. Channels for different peers run in parallel. On the server, every channel shares one `BluetoothGattServer` and the same characteristic objects, so `notifyCharacteristicChanged` is also serialized on `gattServerLock`. Two channels must not change the same characteristic value at once.

Timeouts: 1 second for a command, 1.5 seconds for a message piece, 5 seconds for the disconnect notice, 4 seconds for disconnect completion. `disconnectFromAll()` first sends a disconnect notice to everyone. If they have not all arrived within 5 seconds, `forceDisconnectionFromAll()` tears down the client and the server and builds them again.

## 7. How one local utterance leaves the phone

`ConversationService` connects `Recorder` callbacks straight to recognition. It does not run a second language here.

`Recorder` uses `AudioSource.MIC`. vivo devices use `PCM_16BIT`. Other devices use `PCM_FLOAT`. The sample rate is the one the device can open from the candidate list. Reads are aligned to Silero's frame length. VAD is built in `Global` and passed in: 16 kHz, frame length 512, `Mode.NORMAL`, a speech segment of at least 50 ms, and 300 ms of silence treated as the end.

After speech is detected, `onVoice` gives the float buffer to `Recognizer.recognize()`. Conversation's speech beam size is `SPEECH_BEAM_SIZE = 4`. The language code is fixed at `global.getLanguage()`, the language of the user of this phone. Whisper runs in the local ONNX Runtime. The model files are `Whisper_encoder.onnx` plus the init, cache, decoder, and detokenizer set. A low-memory phone turns off the encoder CPU arena.

Filters in the recognition callback:

- Empty text is dropped.
- `[(und)]` is dropped.
- Text whose first three characters contain `[` or `(` is dropped as metadata, so Whisper special tokens are not sent.
- A non-final result only `notifyMessage`s the UI. It is not stored and not sent. Every callback calls `incrementCurrentResultID()`, so those intermediate results are separate bubbles. They do not replace the previous one.
- Only a final result is sent, and `addOrUpdateMessage` puts it in the service's list. When the UI rebinds, `GET_ATTRIBUTES` returns that list.

Before sending, one more wrapper is applied:

```
text + languageCode + languageCode.length()
header = "m"
receiver = null
```

A keyboard send uses the command `RECEIVE_TEXT`. It skips recognition and uses the same `sendMessage()`. The local bubble's input language and output language are both this phone's language, because this phone does not translate its own speech.

## 8. Translation and speech after the other phone receives it

`onMessageReceived` handles header `m` only. It splits out the original text and the source language with the rule in section 5, builds a `ConversationMessage(sender, NeuralNetworkApiText)`, then:

```
translator.translateMessage(message, local language, TRANSLATOR_BEAM_SIZE=1, ...)
```

`translateMessage` queues on a thread named `messageTranslationPerformer` and translates one item at a time. If the source language and the local language are the same, it returns the original text and does not run a model. Otherwise it calls `loadSrcLangResourcesForPeer(source language, sender)` and then `performTextTranslation`. The translation queue shares `Translator`'s lock with text mode and WalkieTalkie, but Conversation enters through `translateMessage`, not the ordinary `translate`.

After translation:

- `message.textToTranslate` keeps the original text. `message.text` is replaced with the translation.
- If it is final, the system TTS supports the target language, and `conversationAutoTTS` is true, it calls `speak()`.
- The bubble is handed to the UI and written into the service's message list.
- A translation failure currently only calls `notifyError`. The source has a TODO: an unsupported language does not yet get a red "unsupported" bubble.

TTS is the phone's system engine, not a model. Automatic speech is on by default. Turning the sound button off writes `conversationAutoTTS` to false and stops the current speech.

Microphone and TTS:

- With no Bluetooth headset, `shouldDeactivateMicDuringTTS()` is true. Recording stops when TTS starts and the microphone opens again 500 ms after all speech has finished.
- With an SCO headset the method returns false. The microphone stays open, and headset playback does not cut off the user's next sentence.
- `Recorder` also watches whether the system is playing accessibility audio. TalkBack speaking yields the microphone. That yield does not apply when the route is headset SCO.

The old `BluetoothHelper` in `ConversationService` is commented out. SCO connect and disconnect are reported to the UI by `Recorder`'s own `BluetoothHeadsetCallback`, which switches the input label between "mic" and "btHeadset".

## 9. Language resources loaded per person

Conversation language resources are not one fixed source and target pair. `LanguageResourcesManager` keeps, for this mode:

- One target language, `conversationTgtResource`: the local user's language. Loaded when the service starts.
- A map, `conversationSrcResources`: the key is the peer's `uniqueName`, the value is the language that peer sent.

Each pair "their language → my language" loads Mozilla Bergamot models, the dictionary, and Tatoeba pairs according to the current settings. Madlad and HY-MT are whole models. They are not split into per-language files. Only Mozilla is one model per language. Resources are reused across Text, WalkieTalkie, and Conversation. When one peer is unloaded, a model stays cached if another peer or another mode still uses that language.

A disconnect calls `unloadSrcLangResourcesForPeer`. Service destruction calls `unloadAllLangResourcesForConversation`. A renamed peer goes through `updatePeer` so the resource map does not keep the old `uniqueName`.

The selectable translation backends are still the mode constants on `Translator`. Conversation does not pick a separate model. It uses `selectedTranslationModel` from settings, default `MOZILLA` (7). `HY_MT` is 8. `MADLAD_CACHE` is 5. Those integers are stored in preferences. Do not change what they mean.

## 10. Disconnect, reconnect, and several people

Each connected peer is one `Channel`. After the link drops, `STRATEGY_P2P_WITH_RECONNECTION` marks the peer reconnecting and allows 30 seconds. If the same `uniqueName` is discovered again inside that window, the resume handshake runs instead of a new connection request. If it does not return within 30 seconds, it is a real disconnect.

A reconnecting peer is not sent further messages. Messages stay in that channel's `pendingMessages` and continue from the interrupted queue after the link returns. Ordinary commands are paused during the handshake so they do not compete with the resume packet for GATT operations.

A multi-phone session has no host. Each phone sends `m` messages to every channel with `receiver == null`, which is everyone not disconnected. Each receiver translates independently into its own target language. If A speaks Chinese, B's language is English, and C's language is Japanese, B and C each translate the same Chinese original into their own language. A does not translate first and then forward.

Recent contacts are stored in Room (`RecentPeerEntity`). The discovery list uses `uniqueName` to put the old avatar back. The avatar and the Android id are updated only in the `d` / `i` exchange. They are not placed in every interpretation message.

## 11. Threads and locks

The easy mistake when changing Conversation is which thread may touch which state.

| Place | Thread |
| --- | --- |
| Advertising, scanning, work after GATT callbacks | Main thread. `startAdvertising` off the main thread returns `NOT_MAIN_THREAD` |
| `Recorder.ProcessVoice` | The record thread. Start and stop are pulled in through a command queue |
| `Recognizer.recognize` | A new thread per call, serialized internally on `lock` into one recognition queue |
| `Translator.translateMessage` | `messageTranslationPerformer`. `lock` keeps translations one at a time |
| Language-resource loading | Its own thread, plus `langResourcesLock` |
| UI updates | `ConversationBluetoothCommunicator` and `BluetoothCommunicator` post callbacks back to the main thread |

`mVoiceRecognizerCallback` in the service runs on the recognition thread. It calls `sendMessage` and `notifyMessage`. `notifyMessage` sends a `GuiMessage` to the in-process UI handler through a `Messenger`.

## 12. Do not assume these things

- What goes on the air is the original text, not the translation and not the audio.
- The local language comes from `Global.getLanguage()`. WalkieTalkie's first and second languages do not take part in Conversation.
- The speech beam is 4 and the translation beam is 1. Do not apply the beam from settings to these two constants unless the quality check changes with them.
- The language-code length on an `m` message is one digit. A multi-character locale cannot be dropped in directly.
- The reply condition for header `l` is the opposite of the comment. See section 5.
- `uniqueName` is the identity. Comparing MAC addresses fails on reconnect.
- The beta 2 resume packet carries the full `uniqueName`. An old client that writes only `1` is rejected.
- JNI, ONNX input names, model-mode integers, and `abiFilters arm64-v8a` are not part of this session protocol, but a translation-quality change lands in the receiver's `Translator`.
- The app does not upload audio or text. Adding a network report would break the privacy premise of this mode.

## 13. Key files

| File | Responsibility |
| --- | --- |
| `voice_translation/VoiceTranslationActivity.java` | Switches pairing, the session, WalkieTalkie, and text, and starts and binds the service |
| `voice_translation/_conversation_mode/PairingFragment.java` | Discover, request, accept, then enter the session |
| `voice_translation/_conversation_mode/_conversation/ConversationFragment.java` | Conversation page and connection page |
| `.../main/ConversationMainFragment.java` | Mic, keyboard, speak button, message list |
| `.../connection_info/PeersInfoFragment.java` | People in the session and continued search |
| `.../_conversation/ConversationService.java` | Recognize, send, receive, translate, TTS |
| `voice_translation/VoiceTranslationService.java` | Foreground service, wake lock, mic and TTS exclusion |
| `.../communication/ConversationBluetoothCommunicator.java` | `m` / `d` / `i` / `l` |
| `bluetooth/BluetoothCommunicator.java` | Advertising, scanning, client and server lifetime |
| `bluetooth/BluetoothConnectionClient.java` | Outgoing connection and the MTU handshake |
| `bluetooth/BluetoothConnectionServer.java` | GATT server, characteristic table, accept and reject |
| `bluetooth/Channel.java` | Fragmentation, two queues, timeouts |
| `bluetooth/Message.java`, `BluetoothMessage.java` | Application messages and on-air pieces |
| `neural_networks/translation/Translator.java` | `translateMessage` and per-peer resource loading |
| `neural_networks/translation/LanguageResourcesManager.java` | Conversation's target language and each peer's source language |
| `neural_networks/voice/Recorder.java`, `Recognizer.java` | Capture, VAD, Whisper |
