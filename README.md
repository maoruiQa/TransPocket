<img src="images/icon.png" width="180" alt="TransPocket">

# TransPocket

TransPocket 是离线的 Android 实时翻译应用，当前版本 **0.1.0**。语音识别、翻译和语言判断都在手机上运行。应用没有自己的服务器，也不会上传语音或文本。

**本仓库是 [RTranslator](https://github.com/niedev/RTranslator) 的 fork。** 上游作者是 Luca Martino（GitHub：[niedev](https://github.com/niedev)）。上游程序以 Apache License 2.0 授权。本 fork 把应用改名为 TransPocket，并在上游 3.0.0-beta2 之上加入了同声传译、更多端侧模型和界面配色。TransPocket 不代表上游，也不由上游维护。

![对话模式](images/Conversation_image.png)

![对讲机模式与文本翻译](images/TextTranslation_and_WalkieTalkie.png)

## 四种用法

**文本翻译。** 输入文字，在本机翻译。

**对讲机（WalkieTalkie）。** 一台手机、两种语言、轮流说话。应用判断当前是哪种语言，译成另一种，再用系统语音朗读。

**对话（Conversation）。** 多台安装了本应用的手机通过蓝牙低功耗连接。每台手机只识别自己这一侧的话，把文字和语言码发给其他手机。接收方在自己的手机上翻译并朗读。音频不离开本机。可以同时连接多台手机。对话和对讲机可以在后台继续运行；有的系统会限制后台耗电，那种情况下把应用留在前台更稳。

**同声传译。** 一台手机听对方说话，一句一句翻译并朗读。默认使用 Silero VAD 按停顿切句。设置里的「自动发音」可以每次进入时打开、关闭，或按当前播放设备决定。界面上的按钮只影响这一次。

## 0.1.0 里已经有的能力

这些是相对上游 RTranslator 3.0.0-beta2 已经在本仓库里的改动：

- 同声传译，默认下载并启用 Silero VAD（约 2 MB）按语音停顿断句。
- 翻译模型除 Mozilla Bergamot、Madlad 400 3B（int4）、HY-MT 1.5 1.8B（int8）之外，可以选用 Google TranslateGemma 4B 和 Qwen3.5 0.8B。后两者是 GGUF Q4_K_M，在手机上用 llama.cpp 运行。TranslateGemma 大约需要 4 GB 内存，并适用 Gemma 条款。Qwen3.5 0.8B 大约需要 1.4 GB 内存。
- 语音识别可以在 Whisper Small、Whisper Large V3 Turbo、Qwen3-ASR 0.6B、Qwen3-ASR 1.7B 之间选择。1.7B 需要大约 8 GB 内存的手机。
- 朗读使用手机上的系统文字转语音。一句结束就开始读这一句，下一句排队，不把整段合成一次提交。连着蓝牙耳机时，会先保持媒体通路，避免句首被耳机休眠吃掉。
- 模型下载在应用离开前台后继续。加载和切换模型时显示阶段进度。
- 五种配色：秋日金、竹青、靛蓝、樱粉、紫藤。切换配色会同时切换桌面图标和冷启动画面。
- 界面显示名称为 TransPocket。

词典、Tatoeba 句库和波束搜索仍是文本翻译质量的一部分。语音模式里的波束宽度是 1。不要在没有质量对比的情况下改 `EOS_PENALTY`、`WORD_REWARD`、`LENGTH_ALPHA`、`SIBLING_PENALTY` 或波束宽度。

NLLB 仍留在代码里，作为旧路径，不是当前的默认模型。

## 下载与安装

从本仓库的 [Releases](https://github.com/maoruiQa/TransPocket/releases) 安装 `TransPocket-0.1.0.apk`。只支持 **arm64-v8a**。`minSdk` 是 28。建议手机有大约 **6 GB** 内存。x86_64 模拟器不能安装这个包。

应用包名仍是 `nie.translator.rtranslator`，以便覆盖本机已经安装的同一应用。`versionCode` 是 28，高于上游 3.0.0-beta2 的 27。覆盖安装会保留原来的应用数据。

首次打开需要联网下载所选模型。模型体积取决于选项，不是一个固定的 1.2 GB。下载完成后可以离线使用。模型来自各自的发布页（包括 Hugging Face），不在本 git 仓库里。

[Sideloading.md](Sideloading.md) 保留的是上游 2.0 的手动放置步骤。那份附件不是 TransPocket 0.1.0 的模型包。

## 语言与朗读

可选语言取决于当前的识别模型和翻译模型。Madlad 覆盖面最宽。Mozilla 需要按语言下载。低质量语言可以在设置里打开。

朗读语言和音质取决于系统里选中的文字转语音引擎。推荐安装 Google 文字转语音，也可以用别的引擎。更换系统引擎后，从最近任务划掉 TransPocket 再打开。

## 设备与构建

在仓库根目录用 PowerShell：

```
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

发布包仍使用 Android 调试签名，这是上游的构建方式。不要把密钥库提交进仓库。工具链是 Android Gradle Plugin 8.2.2、Gradle 8.12、NDK `28.0.12674087`、CMake 3.22.1。原生代码只在 JNI 边界需要时才改。

实现说明见 [docs/speech-and-translation.md](docs/speech-and-translation.md) 和 [docs/conversation.md](docs/conversation.md)。

## 许可证

请同时阅读下面三份文件：

| 文件 | 作用 |
| --- | --- |
| [LICENSE.txt](LICENSE.txt) | 上游 RTranslator 的 Apache License 2.0 全文，未经修改 |
| [LICENSE](LICENSE) | GNU General Public License v3 全文 |
| [NOTICE.txt](NOTICE.txt) | 上游声明和本 fork 的许可说明 |

上游 RTranslator 版权归 Luca Martino 及其他上游贡献者。源文件里原有的 Apache 2.0 文件头继续有效，本仓库没有把它们改写成 GPL 头。Apache License 2.0 与 GPLv3 兼容，因此本仓库把 **fork 中的修改** 以及 **从本仓库发行的组合程序** 放在 GPLv3 下分发。收到原 Apache 2.0 部分的人，仍须保留其中的署名、NOTICE 和该许可证中的专利授权。

`app/src/main/cpp` 与 `app/libs` 里的第三方代码各自带有自己的许可证，包括 ONNX Runtime、SentencePiece、Bergamot、FastText、sherpa-onnx、sqlite4java 以及对话模式使用的蓝牙栈。模型权重适用发布者自己的条款。Gemma 模型适用 Gemma 条款。

向本仓库贡献的修改，以 GPLv3 授权。

## 隐私

TransPocket 不设账号，不加统计，也没有用于语音或文本的后端。联网用途是下载模型。隐私政策原文随上游保留，并注明本副本属于这个 fork：

- [privacy/Privacy_Policy_en.md](privacy/Privacy_Policy_en.md)
- [privacy/Privacy_Policy_it.md](privacy/Privacy_Policy_it.md)

## 上游项目

RTranslator 上游仓库：[https://github.com/niedev/RTranslator](https://github.com/niedev/RTranslator)

上游的资助、捐赠和 Product Hunt 页面属于上游项目。本 fork 不接收指向那些页面的捐赠。使用上游代码的其他项目（WhisperIMEplus、WhisperJET）也属于上游生态。

本仓库的缺陷请开在 [maoruiQa/TransPocket](https://github.com/maoruiQa/TransPocket) 的 Issues。不要把本 fork 的问题寄给上游维护者。贡献习惯见 [CONTRIBUTING.md](CONTRIBUTING.md)。
