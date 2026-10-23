# 语音识别、分句、翻译与发声

本文描述 `v3.00`（`3.0.0-beta2`）里从麦克风到译文朗读的实现。模型文件不在 git 里，首次启动后下到应用私有目录。`Global.USE_EXTERNAL_MEMORY_FOR_RESOURCES` 目前是 `false`，所以下面的路径都相对于 `context.getFilesDir()`。

整条链路分四段，中间没有流式识别：

```
麦克风 PCM
  -> Silero VAD + 音量门限，切成一整句音频
  -> Whisper Small（ONNX）整段识别
  -> 翻译前按句切开，再按 token 预算合并
  -> Bergamot / Madlad / HY-MT，可选词典和 Tatoeba
  -> Android 系统 TTS 朗读
```

识别和翻译都在手机上。TTS 不是随应用打包的神经网络，用的是系统里已安装的 `TextToSpeech` 引擎。

## 1. 语音怎么被切成一句

切段在 `Recorder`，不在 Whisper 里。Whisper 只在一句结束时被调用一次，输入是这句的全部 PCM。

采集参数：

- 采样率只试 16000 Hz，单声道。
- 除 vivo 用 `PCM_16BIT` 外，其他机器用 `PCM_FLOAT`。源是 `AudioSource.MIC`。
- 环形缓冲大约 30 秒：`(29s + 1s) * sampleRate`。float 缓冲送给识别，short 缓冲送给 VAD。float 转 short 是乘 32768。
- 每次读取的样本数对齐到 512，也就是 Silero 的一帧（16 kHz 下 32 ms）。

VAD 在 `Global` 里创建，整个进程共用一个 `VadSilero`：16 kHz、帧长 512、`Mode.NORMAL`、最短语音 50 ms、静音 300 ms。这个 300 ms 是 Silero 库自己的内部参数。一句何时结束，由下面的 `speechTimeout` 决定，不是由这 300 ms 单独决定。

自动模式下，一个读块被当成“有语音”必须同时满足两件事：

1. 块里至少 15 个采样的绝对值超过音量门限。门限默认 1500，范围 400 到 15000，存在设置 `amplitudeThreshold` 里，刻度是 16-bit 幅度。
2. 这个读块切成的 512 点帧里，至少一帧 `vad.isSpeech()` 为 true。最后一帧如果没填满，剩余位置是 0，仍会送给 Silero。

两个条件都过，才刷新“最后一次听到语音”的时间。否则，如果一句已经开始，并且静音持续超过 `speechTimeout`，这句结束。`speechTimeout` 默认 1300 ms，范围 100 到 5000 ms。

一句的起点会往前多带一段，避免吞掉起音。这段叫 `prevVoiceDuration`，默认也是 1300 ms，范围 100 到 1800 ms。从环形缓冲里取 `tailIndex - prevVoiceLength` 作为起点。

一句还有硬上限：从起点（含往前带的那段）算起超过 29 秒，立刻截断并送去识别。所以 Whisper 看到的最长音频大约是 29 秒，不是无限流。

手动模式（WalkieTalkie 按住说话）把 `isHearingVoice()` 固定成 true，`prevVoice` 缩成 0.1 秒，直到调用 `end()` 才出句。

一句结束时 `executeEnd()` 把环形缓冲里 `[startVoiceIndex, tailIndex)` 拷成一段 float，然后依次回调 `onVoice(data)` 和 `onVoiceEnd()`。识别发生在 `onVoice`，不是在说话过程中。界面上的音量条只在 `isRecording` 为 true 时更新，也就是这句已经开始之后。

TalkBack 正在播时，如果没有走蓝牙 SCO，`readAudio` 会把刚读到的样本清零，并且正在进行的一句会被立刻结束。连着 SCO 耳机时不做这个软件静音。

### 同声传译的断句

对话模式和对讲机模式仍按上面的 `speechTimeout` 把一整段话交给识别。同声传译默认在这句话里面再切：静音满 420 ms 且已经说了至少 700 ms 就交一句，说满 7000 ms 也交一句，识别结果再用 `ClauseSplitter` 按标点切开。这一句交出去以后采集不停止。

首次进入时，模型设置里 Silero VAD v6.2.1（约 2 MB，`VAD/silero_vad.onnx`）默认打开，并跟其他已选模型一起下载。已经装过、又没下过这个文件的旧版本，冷启动时会把开关关掉，仍用 420 ms 和标点。打开且文件能加载时，同声传译改用这份模型自己的状态，不和上面那个打包的 `VadSilero` 共用。连续约 96 ms 概率达到 0.5，并且这一帧过了音量门限，才算开口。已经说了至少约 250 ms 之后，连续约 320 ms 概率低于 0.35 就交一句并继续听。说满 7000 ms 仍在说也会切开。静音达到 `speechTimeout` 才结束这一轮采集，并把 LSTM 状态清零。这种情况下不再跑 `ClauseSplitter`。开关关闭、文件不在或会话打不开时，退回 420 ms 和标点。

## 2. Whisper 怎么转写这一句

实现在 `Recognizer`。模型是拆开的 Whisper Small ONNX，带 KV cache：

| 文件 | 作用 |
| --- | --- |
| `Whisper_initializer.onnx` | `audio_pcm` 做成编码器输入特征 |
| `Whisper_encoder.onnx` | 编码。低内存模式关掉 CPU arena 和 memory pattern |
| `Whisper_cache_initializer.onnx` | 用编码器输出填 encoder KV |
| `Whisper_cache_initializer_batch.onnx` | 双语言时把同一份编码复制成 batch 2 |
| `Whisper_decoder.onnx` | 逐 token 解码，12 层 decoder cache |
| `Whisper_detokenizer.onnx` | token id 还原成字符串 |

解码是贪心，不是 beam search。`recognize(..., beamSize, ...)` 把 `beamSize` 放进 `DataContainer`，后面的循环没有读它。Conversation 和 WalkieTalkie 传入的 `SPEECH_BEAM_SIZE = 4` 因此不影响结果。每步用 `Utils.getIndexOfLargest` 取 logits 最大的 id。

单语言时，解码器前四个固定 token 是：

```
50258  起始
50258 + 语言下标 + 1
50359  transcribe
50363  不要时间戳
```

语言下标来自 `Recognizer.LANGUAGES`，这是 Whisper 的全语言表，不是界面上那份“高质量语言”列表。界面列表在 `res/raw/whisper_supported_languages.xml`。设置里打开低质量语言后，才改用完整的 `LANGUAGES`。

之后每步把上一个 id 作为 `input_ids`，并带上 12 层 `past_key_values.{i}.decoder/encoder.key/value`。结束符 `eos` 是 50257。

token 上限是 `min(445, 音频秒数 * 30)`。秒数用 `data.length / 16000` 估算。碰到上限就强制写成 eos，并且**不把这段送进 detokenizer**，结果变成占位符 `[(und)]`。调用方会把 `[(und)]` 以及前三个字符里带 `[` 或 `(` 的文本当成元数据丢掉。

detokenizer 之后 `correctText()` 做四件事：删掉残留的 `<|...|> ` 时间戳、去掉两端空白、把首字母改成大写、删掉 `...`。

`notifyResult` 的 `isFinal` 在这条路径上永远是 `true`。Conversation 里“非 final 只显示、不发送”的分支，对当前 Whisper 实现不会走到。一次一句，一次最终结果。多个一句在 `Recognizer.lock` 上串行，`stop()` 会清空还没开始的队列。

## 3. WalkieTalkie 如何在两种语言里选一句

WalkieTalkie 对同一段音频调用 `recognize(data, beam, lang1, lang2)`。编码只跑一次。编码器输出被复制成 batch 2，两个解码器头分别带 `lang1` 和 `lang2` 的语言 token，其余步骤并行。每步记录 `log(softmax(被选中的 logit))`，最后除以 token 数，得到两个平均对数概率。

`WalkieTalkieService.compareResults()` 接着做语言判断，阈值默认 0.5：

1. 只有一边是元数据时，留下另一边。
2. 否则把两段文本交给 `Translator.detectLanguage`。两边都判成了各自对应的语言时，用平均对数概率高的那一边。只有一边判对，就用那一边。
3. 语言判断失败时，同样退回比较平均对数概率。

`LanguageDetector` 的实际分支和注释不完全一样：

- 先做 NFC、小写，去掉 Whisper 的 `[...]` / `(...)`、标点、数字。词数用 ICU `BreakIterator.getWordInstance()`，只数以字母或数字开头的段。
- 不足 7 个词时：若恰好一个词，先查写死的单词表（目前只有 `ciao` → 意大利语），再查词典里有没有这个词。然后用 Lingua。
- FastText（`fasttext.ftz`，随包放在 assets，复制到 files）只在词数小于等于 1、而且 Lingua 没有给出结果时运行。
- 7 个词及以上直接返回 `und`，不会跑 FastText。

因此一句正常长度的话，WalkieTalkie 的语言选择经常落到 Whisper 的平均对数概率上，而不是 FastText。

Conversation 不走双语言。它只用本机 `Global.getLanguage()` 做一次单语言识别，原文连同语言码发给对方，翻译在接收端做。

## 4. 翻译前的分句

分句在 `Translator.performTextTranslation`，只对 **不是 Mozilla** 的后端做。Bergamot 自己在 native 层按句断开，Java 不再切。

非 Mozilla 的步骤：

1. `BreakIterator.getSentenceInstance(inputLanguage.getLocale())` 按源语言的 ICU 规则切句。中文、日文和英文的边界规则不同，因为 locale 不同。
2. 相邻片段如果 token 数之和小于预算，或者后一段不足 5 个 token，就拼回去。拼完再扫一遍，直到没有可拼的相邻对。
3. 预算按模型写死：NLLB 和 Madlad 200 token，HY-MT 5000 token。源码里这两处都标了 TODO，不是测量过的最优值。
4. 每一段单独翻译，再用一个空格接起来。

5 token 这条规则会把被 ICU 拆得过碎的片段并回去，例如小数、缩写后面多切出来的一小段。它不保证语义完整，只保证不把极短片段单独送进模型。

每一段的翻译顺序是：词典整段命中，否则 Tatoeba 整句命中，否则神经网络。词典只在整段就是一个词条时返回译文。Tatoeba 把文本 NFC、统一引号、压空白、转小写后做 SHAKE-256 前 8 字节哈希，再核对原文，避免哈希碰撞。命中就直接用库里的译句，不跑模型。

Mozilla 路径对整段文本调用 `BergamotTranslator.translateMultiple`。native 配置里的 `max-length-break: 256` 是 Bergamot 自己的断句长度，单位是它的词片，不是上面的 Java token 预算。

如果设置了“语音模式用 Mozilla”，Conversation 和 WalkieTalkie 会把 `performMode` 改成 `MOZILLA`，即使当前选中的是 Madlad 或 HY-MT。语言对不被 Mozilla 支持时，会退回当前选中的大模型再试一次。文本模式不用这个开关。

## 5. 三个翻译后端

模式整数存在偏好 `selectedTranslationModel` 里，默认 `MOZILLA`（7）。不要改这些数的含义。

| 常量 | 值 | 运行时模型 | 词表 |
| --- | --- | --- | --- |
| `NLLB` / `NLLB_CACHE` | 0 / 6 | 遗留 NLLB ONNX，12 层，hidden 1024，16 头，head 维 64 | `sentencepiece_bpe.model` |
| `MADLAD_CACHE` | 5 | Madlad 400 3B，4-bit 权重 ONNX，32 层，hidden 1024，16 头，head 维 128 | `Translation/Madlad/spiece.model` |
| `MOZILLA` | 7 | 每种语言一对 Bergamot 模型，经英语中转 | 模型目录里的 vocab |
| `HY_MT` | 8 | HY-MT 1.5，`model_int8_final.onnx`，32 层，hidden 2048 | `Translation/HY-MT/tokenizer.json` |

内部存储路径：

- Madlad：`Translation/Madlad/Int4Acc4/madlad_{encoder,decoder,cache_initializer}_4bit.onnx`，外加 `madlad_embed_8bit.onnx`。
- HY-MT：`Translation/HY-MT/model_int8_final.onnx`。它是一个解码器会话，没有单独的 encoder 文件。
- Mozilla：`Translation/Mozilla/{lang}/{lang}en` 和 `{lang}/en{lang}`。每个目录要有 model、srcvocab、trgvocab（或一份共用 vocab）和 `lex.*` shortlist。

### Madlad

SentencePiece 编码前在文本前加上 `<2目标语言码> `。源语言不写进 token。id 与 SentencePiece 一一对应，不再加 1。末尾加 `</s>`。

解码是带 cache 的 beam search，但语音模式把 beam 设为 1，所以退化为贪心。文本模式用设置里的 beam。结构上：

- 第一步 `input_ids = 0`，batch 为 1。
- beam 大于 1 时，从第二步起 batch 变成 beam 宽，encoder KV 和 attention mask 被复制到每一条 beam。
- embedding 在 `embedSession` 里单独算，解码器吃 `embed_matrix`。
- 结束符是 SentencePiece 的 `</s>`。

beam 的打分常量在 `Translator` 里：

- `WORD_REWARD = 0.5`
- `LENGTH_ALPHA = 0`（长度归一化关闭，因为 word reward 已经在拉长句子）
- `SIBLING_PENALTY = 0.2`
- `PATIENCE = 1.5`，已完成句子的保留条数大约是 `1.5 * beamSize`
- `EOS_PENALTY = 0`
- `PARTIAL_RESULT_MARGIN = 0.2`，提前停止时用来挑还没结束的最好候选

这些数同时作用于 NLLB cache 和 HY-MT 的解码循环。

### HY-MT

不用 SentencePiece。`HuggingFaceTokenizer` 把整段提示编码进去。源语言是中文时提示是中文指令，否则是英文指令，目标语言名来自 `hy_mt_supported_languages.xml` 里的中文名或英文名。提示以 `<｜hy_Assistant｜>` 结尾，模型只应接着写译文。

结束符不是 `</s>`，而是 `<｜hy_place▁holder▁no▁2｜>`。KV cache 只记 4 组（GQA，注释写真实头数 16、组大小 4）。cache 张量名没有 `.decoder` 这一段，和 Madlad / NLLB 不同。

第一步就把带提示的 `input_ids` 送进解码器，而不是一个单独的起始 id。

### Mozilla Bergamot

`bergamot_translator_interface` 加载本地 C++。每种非英语语言缓存两个模型：`{lang}en` 和 `en{lang}`。翻译规则：

- 源和目标相同：原样返回。
- 两边都不是英语：`pivotMultiple`，先译到英语，再译到目标语言。
- 只有一边不是英语：用对应的那一个方向。

生成配置固定为 beam 1、`normalize: 1.0`、`word-penalty: 0`、`max-length-break: 256`、`max-length-factor: 3`、1 个 CPU 线程、`workspace: 256`、soft alignment。文件名含 `intgemm8.bin` 或 `intgemm.alphas.bin` 时加上对应的 int8 GEMM 精度。Java 侧的 beam、词典式逐句拼接都不进入这条路径。短句仍可以先被词典或 Tatoeba 截走。

### NLLB

仍能加载，但不是 3.0 的默认选项。SentencePiece id 要加 1，并且把 0、1、2 映射成 NLLB 的特殊 id。序列开头是源语言 id（`256000 + 语言下标 + 1`），结尾是 `</s>`。cache 解码第一步用 `input_ids = 2`。

## 6. 发声

应用没有自己的声学模型或声码器。`TTSEngine` 包了一层 Android `android.speech.tts.TextToSpeech`，语言和音色取决于手机上选中的系统引擎。设置里的 “Text to Speech” 打开的是系统 TTS 设置。Google TTS 只是 README 的推荐，不是依赖。

队列行为：

- 每条译文 `QUEUE_ADD` 排在系统引擎后面。语言和当前 voice 不一致时先 `setLanguage`。
- 第一条开始时，若没有蓝牙耳机，服务会停麦；队列全部读完后再等 500 ms 开麦。SCO 耳机连接时不停麦。
- WalkieTalkie 检测到新的一句开始会 `pause()` 当前朗读，这句的 `onVoiceEnd` 之后延迟再 `resume()`。
- TalkBack 打断时，被打断的那句放回队列头部，1.5 秒后再 `speak()`。这是为了让 TalkBack 把连续的提示说完，避免两套语音交错。用户自己按停止则清空队列，不重播。
- Conversation 的自动朗读开关是 `conversationAutoTTS`，WalkieTalkie 是 `walkieTalkieAutoTTS`，默认都开。目标语言不在系统 TTS 支持列表里就只显示文字。

## 7. 三种模式各走哪条路

| | 切段 | 识别 | 分句 | 翻译 | 朗读 |
| --- | --- | --- | --- | --- | --- |
| Conversation | 自动 VAD，本机一种语言 | Whisper 贪心，单语言 | 接收端、非 Mozilla 才切 | 接收端，beam 1 | 系统 TTS，耳机可与麦并行 |
| WalkieTalkie | 自动或按住 | 同一段音频两种语言各解一次 | 本机、非 Mozilla 才切 | 本机，beam 1，译到另一种语言 | 系统 TTS，开口时暂停 |
| 文本 | 无 | 无 | 非 Mozilla 才切 | 本机，beam 用设置值 | 按钮触发，同一套 TTS |

Conversation 的语音 beam 常量是 4，翻译 beam 常量是 1。前者目前没有被解码器使用。

## 8. 改动时容易错的地方

- 不要把识别改成边说边出字，除非同时改 `Recorder.executeEnd` 和所有 `onVoice` 调用方。现在 `onVoice` 的语义是“一句的完整 PCM”。
- `speechTimeout`、`prevVoiceDuration`、音量门限是用户设置。29 秒上限和 512 点帧长是代码常量。
- Whisper 的 `beamSize` 参数是死的。真正的 beam 在 `Translator.executeCacheDecoder`，而且语音模式传入 1。
- 7 个词以上的文本，`LanguageDetector` 直接返回 `und`。WalkieTalkie 因此更依赖两条 Whisper 假设的平均对数概率。
- Java 分句不作用于 Bergamot。改 ICU 切分不会改变 Mozilla 的断句。
- HY-MT 的提示语、结束符和 cache 张量名都和其他模型不同。换模型时这三处要一起换。
- Bergamot 的非英语对一定会经过英语。质量问题可能出在两跳中的任何一跳。
- 发声质量和语言覆盖不由这个仓库的模型决定，而由系统 TTS 决定。
