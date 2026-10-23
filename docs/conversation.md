# Conversation 模式技术说明

本文描述 `v3.00`（`3.0.0-beta2`）里 Conversation 模式的实际实现。结论都来自当前源码，不是 README 里的产品介绍。

Conversation 是多手机、双向的实时口译。每台手机只识别自己的话，把原文和语言码经蓝牙低功耗发给对方。对方在本机把原文译成自己的语言，再用系统 TTS 读出来。音频不离开本机。

## 1. 它和另外两种模式的差别

三种模式共用 `Global` 里的 `Translator`、`Recognizer` 和 Silero VAD，但数据路径不同。

| | Text | WalkieTalkie | Conversation |
| --- | --- | --- | --- |
| 设备 | 一台 | 一台 | 两台或更多 |
| 识别 | 不识别 | 同时听两种语言 | 只听本机用户的一种语言 |
| 翻译发生在 | 本机 | 本机 | 接收方 |
| 网络 | 无 | 无 | 无，只有 BLE |
| 前台服务 | 无 | `WalkieTalkieService` | `ConversationService` |

WalkieTalkie 在同一部手机上做检测、翻译和播报。Conversation 把“我说的原文”当成消息，翻译推迟到听的那一侧。这样每台手机只需要加载“对方的语言 → 我的语言”，而不必在说话时知道所有听众的语言后再分别翻译。

## 2. 对象怎么挂在一起

```
VoiceTranslationActivity
  PairingFragment                         发现、发起、接受
  ConversationFragment
    ConversationMainFragment              麦克风、键盘、消息列表
    PeersInfoFragment                     已连接的人、继续加人
        | Messenger
        v
ConversationService                       录音、识别、收消息、翻译、TTS
        |
        +-- Recorder + Recognizer         本机语音
        +-- Translator                    收到的文本
        +-- TTSEngine                     系统 TTS
        |
        v
Global.ConversationBluetoothCommunicator  应用头 m / d / i / l
        |
        v
BluetoothCommunicator
  BluetoothConnectionClient               本机主动 connect 的链路
  BluetoothConnectionServer               别人连上来的链路
        |
        v
每个 Peer 一个 Channel                   分包、队列、重连
```

`Global` 是 `Application`。`Translator`、`Recognizer`、VAD 和 `ConversationBluetoothCommunicator` 都挂在它上面，Activity 和 Service 共用同一份。`ConversationBluetoothCommunicator` 在进入配对界面时由 `initializeBluetoothCommunicator()` 创建，策略固定为 `STRATEGY_P2P_WITH_RECONNECTION`，并且 `bluetoothChange=false`：应用不会再自己开关系统蓝牙。

界面和 Service 不直接调方法。`ConversationMainFragment` bind 到 `ConversationService`，双方用 `Messenger` 传 `Bundle`。界面发给服务的命令包括 `START_MIC`、`STOP_MIC`、`START_SOUND`、`STOP_SOUND`、`RECEIVE_TEXT`、`SPEAK_TEXT`。服务回界面的事件包括 `ON_MESSAGE`、`ON_VOICE_STARTED`、`ON_VOLUME_LEVEL`、`ON_TTS_STARTED`。

`ConversationService` 是前台服务。有录音权限时类型是 `FOREGROUND_SERVICE_TYPE_MICROPHONE`，否则降为 `DATA_SYNC`。它持有一个 10 分钟的 `PARTIAL_WAKE_LOCK`，到期前 10 秒续上，避免息屏后被系统挂起。

## 3. 一次会话的生命周期

1. 用户进入配对。`PairingFragment` 同时 `startAdvertising()` 和 `startDiscovery()`。两边都广播，也都扫描。
2. 扫描只认 service data。广播包不带设备名，service UUID 是 `00007B3D-0000-1000-8000-00805F9B34FB`，service data 是 `uniqueName`。
3. `uniqueName` = 用户显示名 + 2 个持久随机字符。这两个字符存在 `default` SharedPreferences 的 `bluetoothNameId`。显示名可以改，身份用 `uniqueName`。蓝牙地址在断线后经常变，不能当身份。
4. 点某个发现到的人会调用 `connect()`。发起方是 GATT client，对方是 GATT server。一部手机可以同时是某些链路的 client、另一些链路的 server。`BluetoothConnectionClient` 和 `BluetoothConnectionServer` 一直都在。
5. Server 收到连接请求后弹出确认框，15 秒超时。接受走 `acceptConnection()`，拒绝走 `rejectConnection()`。拒绝用的 GATT 状态是 `150`，落在应用保留区间 148–159，client 才能把它和系统错误分开。
6. 任意一方 `onConnectionSuccess` 后，`PairingFragment` 切到 `ConversationFragment`。这个界面有两页：对话页和连接页。
7. `ConversationMainFragment` bind 服务时，如果服务还没起，会先 `startService`。服务 `onCreate` 里加载本机目标语言的翻译资源，成功后才创建 `Recorder`。
8. 最后一个人断开时，服务 `stopSelf()`，界面回到 `PairingFragment`。用户确认退出时，如果还有连接，先 `disconnectFromAll()`。

连接页可以在会话中继续搜索并加入更多人。一个人断开不会结束整场会话；`peersLeft == 0` 才会结束。这是相对旧版本的行为：以前单个设备掉线会退出整个 Conversation。

## 4. 蓝牙握手

Client 连上之后按这个顺序走。握手包不进普通消息队列，因为此时 peer 还没标记为 connected，队列里的发送会被 `isConnected` 检查挡住。

1. `onServicesDiscovered`：若硬件支持，client 请求 LE 2M PHY，并把连接优先级设为 `CONNECTION_PRIORITY_HIGH`。
2. Client 向 `MTU_REQUEST_UUID` 写 247 字节。这不是协商数值本身，而是用一次尽量长的 write 探测当前能写下的长度。
3. Server 用这次 write 的长度减 1 作为自己的分包长度，再通过 `MTU_RESPONSE_UUID` 把这个长度 indicate 回去。
4. 若对端报回的长度小于 `PREFERRED_MTU`（247），client 再调用 `requestMtu(247)`。`onMtuChanged` 里把分包长度设为 `mtu - DATA_MARGIN`，`DATA_MARGIN` 是 6。在协商完成前，`Channel` 的默认分包长度是 17。
5. Client 订阅连接响应、消息、数据、改名和断开这几个 indicate 特征，然后写 `CONNECTION_REQUEST_UUID`，内容是自己的 `uniqueName`。重连时写的是 `CONNECTION_RESUMED_RECEIVE_UUID`，不是新的连接请求。
6. Server 弹出接受或拒绝。接受后 indicate `CONNECTION_RESPONSE_UUID`，内容是 `ACCEPT`（GATT success，0）或 `REJECT`（150）。
7. Client 收到接受，两边都回调 `onConnectionSuccess`。从这一刻起，应用层消息才能走消息队列。

整段握手有 10 秒的 `CONNECTION_COMPLETE_TIMEOUT`。超时就拆掉这条还没完成的 channel。

重连时地址往往已经变了，server 在 `onConnectionStateChange` 里认不出旧 channel，会先建一个占位 channel。等到 resume 写入里带上 `uniqueName`，server 找到正在重连、且名字相同的旧 channel，把新连接的 device 和参数换过去，删掉占位 channel，再在旧 channel 上把握手做完。旧协议只写一个字符 `1`，长度小于等于 1 的 resume 负载会被拒绝。因此 beta 2 的链路和更早的应用版本不兼容。

## 5. 应用层四类消息

`ConversationBluetoothCommunicator` 在 BLE 之上只用 1 字符的 header。文本走 `sendMessage`，头像走 `sendData`。两条队列互相独立，所以传头像不会堵住后面的口译文本，反过来也一样。`receiver == null` 表示发给当前所有还没进入断开流程的 peer。

| Header | 通道 | 谁先发 | 内容 |
| --- | --- | --- | --- |
| `d` | message | client，在 `onConnectionSuccess` | `Settings.Secure.ANDROID_ID` |
| `i` | data | client，在收到对方的 `d` 之后 | 160px、质量 70 的头像 JPEG，或字符串 `null` |
| `l` | message | client，在收到对方的 `i` 之后 | 对方要加载的源语言 |
| `m` | message | 任一方，识别结束或键盘发送 | 口译正文 |

握手后的身份交换是固定接力，角色看的是这条 GATT 链路的 `source`，不是“谁先开口”：

1. Client 发 `d`。
2. Server 收到 `d` 后回自己的 `d`。双方用这个 id 写入或更新 Room 里的 recent peer，键是 Android id 加 `uniqueName`。
3. Client 收到 `d` 后发 `i`。
4. Server 收到 `i` 后回自己的 `i`，并更新头像。
5. Client 收到 `i` 后发 `l`。
6. 双方收到 `l` 都调用 `loadSrcLangResourcesForPeer`。Server 按下面的条件决定要不要把自己的语言回给 client。

`l` 和 `m` 的正文用同一种尾巴编码：

```
{payload}{languageCode}{languageCode 的字符数，一位十进制}
```

例如语言码 `en`、文本 `hello` 的 `m` 消息正文是 `helloen2`。解析时先读最后一个字符得到语言码长度，再从末尾切出语言码，剩下的是正文。语言码必须是 1 到 9 个字符。`zh-Hans` 这种更长的码会把边界切错。

Server 回语言的条件和注释不一致。注释说“第一次收到对方语言才回复”。代码在调用 `loadSrcLangResourcesForPeer` 之前读 `isPeerLoaded()`，而这个方法在 peer 已经出现在 `conversationSrcResources` 里时返回 true。因此第一次收到 `l` 时 `firstLoad` 为 false，server 不会回复；之后的语言更新反而会回复。Client 的语言是在头像交换后发出的，server 的语言回复依赖这段判断。改这段逻辑前要对照一次真实双机握手，不要只按注释改。

用户在会话中改自己的语言时，`Global.setLanguage()` 会重新 `loadTgtLangResourcesForConversation`，再 `sendLanguage()` 广播给所有已连接的人。

## 6. BLE 分包

`Message` 先变成一串 `BluetoothMessage`。每一片的字节布局是：

```
id(4) + sequence(3) + type(1) + data
```

`id` 和 `sequence` 不是整数的十进制，而是 `BluetoothTools.getSupportedUTFCharacters()` 里的字符进位。`type` 是字符 `1`（`NON_FINAL`）或 `2`（`FINAL`）。`data` 是“1 字符 header + 正文 UTF-8”切出来的一片。片长是当前 channel 的 `bluetoothMessagesLength`，其中要先扣掉这 8 字节头。

同一个 `id` 的片按 sequence 拼回去，看到 `FINAL` 才还原成 `Message`。消息 id 和数据 id 分开计数。每个 channel 同时只发一条逻辑消息的各个分片；这条发完才发队列里的下一条。写失败会立刻重试当前片。

一条 channel 上的 characteristic write、read、notification 进 `pendingCommands`，串行执行。不同 peer 的 channel 并行。Server 上所有 channel 共用一个 `BluetoothGattServer` 和同一批 characteristic 对象，所以 `notifyCharacteristicChanged` 还要用 `gattServerLock` 串起来，避免两个 channel 同时改同一个 characteristic 的 value。

超时：命令 1 秒，消息片 1.5 秒，断开通知 5 秒，断开完成 4 秒。`disconnectFromAll()` 先给所有人发断开通知；5 秒内收不齐，就 `forceDisconnectionFromAll()`，拆掉 client 和 server 再重建。

## 7. 本机说的一句话怎么出去

`ConversationService` 把 `Recorder` 的回调直接接成识别，不在这里做第二种语言。

`Recorder` 用 `AudioSource.MIC`。除 vivo 用 `PCM_16BIT` 外，其他机器用 `PCM_FLOAT`。采样率从候选列表里挑设备能打开的那一个。读块对齐到 Silero 的帧长。VAD 在 `Global` 里建好后传进来：16 kHz、帧长 512、`Mode.NORMAL`、语音段至少 50 ms、静音 300 ms 视为结束。

检测到语音后，`onVoice` 把 float 缓冲交给 `Recognizer.recognize()`。Conversation 的语音 beam size 是 `SPEECH_BEAM_SIZE = 4`，语言码固定是 `global.getLanguage()`，也就是这台手机用户自己的语言。Whisper 在本机 ONNX Runtime 里跑，模型文件是 `Whisper_encoder.onnx` 以及 init、cache、decoder、detokenizer 那一组。低内存手机会关掉 encoder 的 CPU arena。

识别回调里的过滤：

- 空文本丢掉。
- `[(und)]` 丢掉。
- 前三个字符里出现 `[` 或 `(` 的文本当元数据丢掉，避免把 Whisper 的特殊标记送出去。
- 非 final 结果只 `notifyMessage` 给界面，不入库，也不发送。每次回调都会 `incrementCurrentResultID()`，所以这些中间结果在界面上是独立气泡，不会覆盖上一条。
- final 结果才发送，并 `addOrUpdateMessage` 放进服务里的列表。界面重连服务时用 `GET_ATTRIBUTES` 把这份列表拿回去。

发送前再包一层：

```
text + languageCode + languageCode.length()
header = "m"
receiver = null
```

键盘发送走命令 `RECEIVE_TEXT`，跳过识别，但用同一条 `sendMessage()`。本机气泡的输入语言和输出语言都设成自己的语言，因为本机不翻译自己的话。

## 8. 对方收到后怎么翻译和朗读

`onMessageReceived` 只处理 header `m`。它按第 5 节的规则切出原文和源语言，做成 `ConversationMessage(sender, NeuralNetworkApiText)`，然后：

```
translator.translateMessage(message, 本机语言, TRANSLATOR_BEAM_SIZE=1, ...)
```

`translateMessage` 在名为 `messageTranslationPerformer` 的线程里排队，同一时间只翻一条。源语言和本机语言相同就直接回原文，不跑模型。否则先 `loadSrcLangResourcesForPeer(源语言, sender)`，再 `performTextTranslation`。翻译队列和文本模式、WalkieTalkie 模式共用 `Translator` 的锁，但 Conversation 走的是 `translateMessage` 这条入口，不是普通 `translate`。

译完之后：

- `message.textToTranslate` 保存原文，`message.text` 换成译文。
- 若是 final、系统 TTS 支持目标语言、且 `conversationAutoTTS` 为 true，就 `speak()`。
- 气泡交给界面，并写入服务内的消息列表。
- 翻译失败目前只 `notifyError`。源码里留了 TODO：语言不支持时还没有红色的“不支持”气泡。

TTS 是手机上的系统引擎，不是模型。默认自动朗读。用户关掉声音按钮会把 `conversationAutoTTS` 写成 false，并停掉当前朗读。

麦克风和 TTS 的关系：

- 没连蓝牙耳机时，`shouldDeactivateMicDuringTTS()` 为 true。TTS 一开始就停录音，全部朗读结束后再等 500 ms 重新开麦。
- 连着 SCO 耳机时这个方法返回 false，麦保持开着，耳机播报不会掐掉自己的下一句。
- `Recorder` 还会看系统是不是正在播无障碍音频。TalkBack 说话时会把麦让开；如果当前走的是耳机 SCO，这条让麦逻辑不生效。

`ConversationService` 里旧的 `BluetoothHelper` 已经注释掉。耳机 SCO 的连接和断开由 `Recorder` 自己的 `BluetoothHeadsetCallback` 通知界面，把输入标签在 “mic” 和 “btHeadset” 之间切换。

## 9. 翻译资源按人加载

Conversation 的语言资源不是一对固定的源语言和目标语言。`LanguageResourcesManager` 为这个模式保存：

- 一个目标语言 `conversationTgtResource`：本机用户的语言。服务启动时加载。
- 一张表 `conversationSrcResources`：key 是对方 `uniqueName`，value 是对方发来的语言。

每一对“对方语言 → 我的语言”会按当前设置加载 Mozilla Bergamot 模型、词典和 Tatoeba 句对。Madlad 和 HY-MT 是整模型，不按语言对拆文件；Mozilla 才是每种语言一个模型。资源在 Text、WalkieTalkie、Conversation 之间复用。卸载某个 peer 时，如果别的 peer 或别的模式还用着同一种语言，模型留在缓存里。

一个人断开时调用 `unloadSrcLangResourcesForPeer`。服务销毁时调用 `unloadAllLangResourcesForConversation`。peer 改名会走 `updatePeer`，避免资源表还指着旧的 `uniqueName`。

当前可选的翻译后端仍是 `Translator` 上的模式常量。Conversation 不单独再选模型，用的就是设置里的 `selectedTranslationModel`，默认 `MOZILLA`（7）。`HY_MT` 是 8，`MADLAD_CACHE` 是 5。这些整数写在偏好里，不能改含义。

## 10. 断线、重连和多人

每个已连接的 peer 是一条 `Channel`。链路掉了以后，策略 `STRATEGY_P2P_WITH_RECONNECTION` 会把 peer 标成 reconnecting，并给 30 秒。窗口内重新发现到同一个 `uniqueName` 就走 resume 握手，而不是新的连接请求。30 秒内回不来就真正断开。

正在重连的 peer 不会被继续发消息。消息留在该 channel 的 `pendingMessages` 里，恢复后从被打断的队列继续。握手期间会停掉普通命令，避免和 resume 包抢 GATT 操作。

多人会话没有主持人。每台手机把 `m` 消息发给 `receiver == null` 的所有 channel，也就是当前所有未断开的人。每台接收机用自己的目标语言独立翻译。A 说中文、B 的语言是英文、C 的语言是日文时，B 和 C 各自把同一条中文原文译成自己的语言。A 不会先译好再转发。

最近联系人存在 Room（`RecentPeerEntity`）。发现列表会用 `uniqueName` 把旧头像贴回去。头像和 Android id 只在 `d` / `i` 交换里更新，不放进每一条口译消息。

## 11. 线程和锁

改 Conversation 时最容易踩的是“哪条线程可以动哪份状态”。

| 位置 | 线程 |
| --- | --- |
| 广播、扫描、GATT 回调后的处理 | 主线程。`startAdvertising` 不在主线程会返回 `NOT_MAIN_THREAD` |
| `Recorder.ProcessVoice` | 录音线程，经命令队列把 start/stop 收进去 |
| `Recognizer.recognize` | 每次调用新开线程，内部用 `lock` 串成一条识别队列 |
| `Translator.translateMessage` | `messageTranslationPerformer`，`lock` 保证一条条翻 |
| 语言资源加载 | 单独线程，再加 `langResourcesLock` |
| 界面更新 | `ConversationBluetoothCommunicator` 和 `BluetoothCommunicator` 把回调 post 回主线程 |

Service 里的 `mVoiceRecognizerCallback` 跑在识别线程上。它调用 `sendMessage` 和 `notifyMessage`。`notifyMessage` 通过 `Messenger` 把 `GuiMessage` 送到界面进程内的 handler。

## 12. 改代码时不要假设的事

- 线上传的是原文，不是译文，也不是音频。
- 本机语言来自 `Global.getLanguage()`。WalkieTalkie 的 first/second language 不参与 Conversation。
- 语音 beam 是 4，翻译 beam 是 1。不要把设置里的 beam 直接套到这两个常量上，除非连同质量一起改。
- `m` 消息的语言码长度只有一位。多字符 locale 不能直接塞进去。
- header `l` 的回复条件和注释相反，见第 5 节。
- `uniqueName` 才是身份。比较 MAC 会在重连时失败。
- beta 2 的 resume 包带完整 `uniqueName`。只写 `1` 的旧客户端会被拒绝。
- JNI、ONNX 输入名、模型模式整数、`abiFilters arm64-v8a` 都不属于这条会话协议，但翻译质量的改动会落到接收端的 `Translator`。
- 应用不上传音频或文本。新增网络上报会破坏这个模式的隐私前提。

## 13. 关键文件

| 文件 | 职责 |
| --- | --- |
| `voice_translation/VoiceTranslationActivity.java` | 配对、会话、WalkieTalkie、文本四种界面切换，以及服务的启动和绑定 |
| `voice_translation/_conversation_mode/PairingFragment.java` | 发现、请求、接受、连上后进入会话 |
| `voice_translation/_conversation_mode/_conversation/ConversationFragment.java` | 对话页和连接页 |
| `.../main/ConversationMainFragment.java` | 麦、键盘、朗读按钮、消息列表 |
| `.../connection_info/PeersInfoFragment.java` | 会话中的人和继续搜索 |
| `.../_conversation/ConversationService.java` | 识别、发送、接收、翻译、TTS |
| `voice_translation/VoiceTranslationService.java` | 前台服务、唤醒锁、麦和 TTS 的互斥 |
| `.../communication/ConversationBluetoothCommunicator.java` | `m` / `d` / `i` / `l` |
| `bluetooth/BluetoothCommunicator.java` | 广播、扫描、client/server 生命周期 |
| `bluetooth/BluetoothConnectionClient.java` | 主动连接和 MTU 握手 |
| `bluetooth/BluetoothConnectionServer.java` | GATT server、特征表、接受和拒绝 |
| `bluetooth/Channel.java` | 分包、两条队列、超时 |
| `bluetooth/Message.java`、`BluetoothMessage.java` | 应用消息和线上分片 |
| `neural_networks/translation/Translator.java` | `translateMessage` 和按 peer 加载资源 |
| `neural_networks/translation/LanguageResourcesManager.java` | Conversation 的目标语言和每个 peer 的源语言 |
| `neural_networks/voice/Recorder.java`、`Recognizer.java` | 采集、VAD、Whisper |
