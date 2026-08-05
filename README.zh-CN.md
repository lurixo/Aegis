# Aegis 输入法

<p align="center">
  <img src="docs/branding/banner.png" alt="Aegis —— 简体中文与英文离线输入法" width="860">
</p>

[![许可证：GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![最新发布](https://img.shields.io/github/v/release/lurixo/Aegis?include_prereleases&sort=semver)](https://github.com/lurixo/Aegis/releases)
[![平台：Android 14+](https://img.shields.io/badge/Android-14%2B%20(API%2034)-3DDC84.svg)](#系统要求)

**Aegis** 是一款离线优先的安卓**简体中文与英文**输入法。它基于开源的 **rime-wanxiang**（万象）
CC BY 词库，配以**自研解码器**——运行时不依赖 rime 或 librime。你输入的一切都留在本机：**击键绝不会
发起网络请求，你输入的内容也绝不会被发送。**

[English](README.md) · **简体中文**

<p align="center">
  <img src="docs/screenshots/zh/keyboard-qwerty.png" alt="26 键全拼键盘" width="420">
  <img src="docs/screenshots/zh/keyboard-t9.png" alt="9 键 T9 键盘" width="420">
</p>
<p align="center">
  <img src="docs/screenshots/zh/emoji.png" alt="Emoji 选择器" width="280">
  <img src="docs/screenshots/zh/clipboard.png" alt="剪贴板历史与常用语" width="280">
  <img src="docs/screenshots/zh/symbols.png" alt="符号面板" width="280">
</p>

## 目录

- [面向用户——安装与启用](#面向用户安装与启用)
- [功能](#功能)
- [隐私与权限](#隐私与权限)
- [构建（面向开发者）](#构建面向开发者)
- [发布词库包](#发布词库包)
- [架构](#架构)
- [许可与致谢](#许可与致谢)
- [状态](#状态)

## 面向用户——安装与启用

Aegis 暂未上架应用商店，目前以可下载的 APK 形式分发。

### 系统要求

- **Android 14 及以上**（minSdk 34）。不支持更低版本的安卓。

### 安装

1. 打开 [**Releases**](https://github.com/lurixo/Aegis/releases) 页面，从最新构建里下载 APK
   附件（当前发布均标注为 **预发布（debug）**）。
2. 由于并非从应用商店安装，安卓会提示你为所用的浏览器或文件管理器**允许安装未知应用**——按提示
   授予后，打开下载的 APK 完成安装（即**侧载**）。

### 把 Aegis 启用为键盘

各家设备的菜单名称略有差异，流程是标准的安卓流程：

1. **设置 → 系统 → 语言和输入法 → 屏幕键盘**（部分手机为*管理键盘*或*虚拟键盘*）。
2. 打开 **Aegis** 开关。安卓会提示键盘可以读取你输入的内容——这是**每一款**输入法都会出现的
   标准提示；Aegis 到底做与不做什么，见[隐私与权限](#隐私与权限)。
3. 打字时切换到 Aegis：点按键盘上的**输入法键或地球键**，或打开**“选择输入法”**通知或选择器，
   选择 **Aegis**。

### 首次使用

**英文可以立即输入，中文需要先下载一次。**APK 内不含中文词库，键盘也绝不会自行开始下载词库包。
在没有词库包时输入中文，候选栏会给出下载入口——下载约 98 MB，安装到应用私有存储后约 272 MB，且
不限于 Wi-Fi——只有你点按该入口（或设置页词库卡片中的“下载”）才会开始传输。在它完成之前，中文
输入处于锁定状态，而英文与各面板照常可用。

设置页另外提供**可选**的增强模型（约 420 MB 的八元语法模型），用于更准的下一词与整句排序；这一项
仅在你点击开始时才下载。除这两项下载与随之进行的更新检测（词库是一个小的元数据文件，模型是一个
`HEAD` 请求）之外，Aegis 自身的代码不发起任何网络请求；不经你的操作，Aegis 自身不会有任何联网。

## 功能

- **两种输入模式** —— **26 键全拼**与 **9 键 T9**，外加**数字**、**符号**布局，基于自绘的
  View+Canvas 键盘。
- **格状 Viterbi 解码器** —— 在内存映射词库上解码，以一元对数概率加**字级二元**上下文模型打分。
- **模糊拼音** —— 覆盖平翘舌与前后鼻音，带用户开关。
- **简拼**（声母缩写）—— `zg`→中国，`bjdx`→北京大学。
- **中英混输** —— 无需切换语言即可上屏英文单词（如 `wifi`），含英文补全与纠错。
- **本机学习** —— 常用、近用词自动提权，学习下一词预测，用户词库可导入和导出。仅存于
  `filesDir/userdb.txt`。
- **Emoji 选择器** —— 最近使用加分类 emoji（黄脸、手势、动物、旗帜等）。
- **剪贴板历史与常用语** —— 近期剪贴与可复用常用语同在一个面板，支持逐条管理。
- **符号与编辑面板** —— 分类符号板（中文、英文、货币、数学、希腊、箭头等，带锁定开关）以及
  光标与文本编辑面板。
- **简体归一** —— Aegis 给出的每一个候选都是简体。上游数据中的繁体与异体字形，在构建词库包时被
  归并到其简体形态（并合并词频），使用 `tools/t2s-data` 下的 OpenCC 映射表。该过程只在构建主机上
  进行，APK 内不含任何转换表。
- **Material 3** 设置界面。

## 隐私与权限

键盘能看到你输入的一切，所以信任是关键。Aegis 的设计让这份信任不必只靠我们的口头承诺：

- 应用自身的清单声明**两项**安卓权限：**`INTERNET`** 与 **`USE_BIOMETRIC`**。实际安装的 APK 里还
  列有第三项 `com.aegis.ime.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，由 AndroidX 库在清单合并时
  加入；它不是安卓平台权限，声明在 Aegis 自己的命名空间下、保护级别为 `signature`，不索取你设备上
  的任何东西。
- **`INTERNET`** 用于获取词库包（因为 APK 内不含中文词库；在没有词库包时键盘会给出下载入口，
  仅在你点按时才开始下载）、可选的增强模型（仅在*你*点击开始时才下载），以及这两者的更新检测。
  **击键绝不会发起网络请求，你输入的内容也绝不会被发送。**
- **`USE_BIOMETRIC`** 仅用于默认备份密码：保存它、或把它填入备份对话框，都需要先通过生物识别或
  锁屏验证。
- 你的**击键、候选、已学词、用户词库与剪贴板都不会离开设备**——它们存放在应用私有存储
  （`filesDir`）。
- **无任何分析、无遥测、无账号。**
- APK 里有一部分不是 Aegis 自己的代码：`androidx.emoji2` 随 Jetpack Compose 一同引入。在 Aegis
  自己的界面被打开之后，它会在设备上查找系统镜像中提供 emoji 字体的程序包并向其索取字体；设备上
  没有这样的程序包时，则不会向任何一方索取。这在应用的每次运行中最多发生一次，打字不会触发它。
  Aegis 不为此建立任何连接，也不随之发送你的任何数据。

完整声明见 [PRIVACY.md](PRIVACY.md)。

## 构建（面向开发者）

先决条件：

- **JDK 17**
- **Android SDK**，含 **platform 37**（`compileSdk` 与 `targetSdk` 为 37；`minSdk` 为 34）
- **Gradle** 由 wrapper 提供——直接用 `./gradlew` 即可（无需系统级 Gradle）

```
./gradlew assembleDebug           # 构建 debug APK
./gradlew :app:testDebugUnitTest  # JVM 单元测试（解码器、词库、模糊、简拼、学习、UI）
./gradlew :app:lintDebug          # Android lint
```

APK 内不再打包任何由词库派生的资源：`app/build.gradle.kts` 把 `aegis_dict.bin`、`aegis_t9.bin`、
`aegis_jianpin.bin` 以及约 16 MB 的字级二元上下文模型 `aegis_lm.bin` 一并排除在打包资源之外，
APK 因此保持在 24 MB 左右。这四者都在运行时下载到 `filesDir/downloaded/`；三个词库是中文候选的
唯一来源，模型只负责重排。模型是可选件——没有它键盘照样解码，只是上下文排序变弱。放进
`app/src/main/assets/` 的本地构建产物不会进入 APK，但会被解码器测试读取。

词库包由 `:tools` 模块从全部 14 张万象表（`zi jichu lianxiang cuoyin duoyin shici diming yixue
huaxue yaopin mingren yiren wuzhong renming`）以 `--min-freq 1`、无每键上限预构建，保留每一条。

```
./gradlew :tools:installDist
tools/build/install/tools/bin/tools --out <dict> --min-freq 1 --keytype letter   --t2s-data tools/t2s-data <14 张万象 .dict.yaml ...>
tools/build/install/tools/bin/tools --out <t9>   --min-freq 1 --keytype digit    --t2s-data tools/t2s-data <14 ...>
tools/build/install/tools/bin/tools --out <jp>   --min-freq 1 --keytype initials --t2s-data tools/t2s-data <14 ...>
tools/build/install/tools/bin/tools lm --out <lm> --t2s-data tools/t2s-data <14 张万象 .dict.yaml ...>
```

## 发布词库包

应用 APK 发布与可下载词库包分开发布。带版本号的应用 release 只携带 APK，不参与词库更新发现。词库包与
随附的 `aegis-dictionary-update.json` 与 `aegis-build-info.json` 元数据发布在滚动的
[`dict-latest`](https://github.com/lurixo/Aegis/releases/tag/dict-latest) GitHub release 上；应用只从
这个 release tag 读取词库元数据，按已安装包与当前 ZIP 的 SHA-256 和内容元数据判断是否更新。

```
tools/release/build_dictionary_pack.py --release-tag dict-latest
```

该命令克隆 `amzxyz/rime-wanxiang`（默认取 `wanxiang` 分支，用 `--source-tag` 改为固定到某个
release tag，或用 `--source-dir` 指定已有的本地目录），
用 `tools/DictBuilder` 以 `--min-freq 1`、无每键上限构建 14 张已核验表，并在
`build/release-dictionary/` 下写出词库包 ZIP、`aegis-build-info.json` 与
`aegis-dictionary-update.json`。把这些生成文件上传到滚动的 `dict-latest` release，不要上传到带
版本号的应用 release。已签入的 `aegis-build-info.json` 记录当前滚动词库包溯源链（来源 tag 与
commit、逐表输入哈希、构建参数、输出 bin 哈希和物理附件 URL）及其尚存的溯源缺口；滚动词库包重新
发布时需要同步更新它。在 release 同时携带确切
输入哈希、可确定复现的配方以及签名或证明之前，词库包还不是完全可复现的公共供应链产物。

## 架构

- `app/.../ime` —— 输入法服务、自绘键盘与候选视图、各面板（emoji、剪贴板、符号、编辑）、
  输入状态机。
- `app/.../decoder` —— `PinyinDecoder`（词格 Viterbi；精确 > 模糊 > 简拼边）。
- `app/.../dict` —— 内存映射读取器：`BinaryDict`、`CharBigramLM`、`Fuzzy`。
- `app/.../user` —— `UserModel`（离线学习，文件持久化）。
- `tools/` —— 主机侧词库与语言模型构建器（`DictBuilder`、`LmBuilder`）、评测校验器与发布打包器。

## 许可与致谢

Aegis 自身代码为 **GPL-3.0**（见 [`LICENSE`](LICENSE)）。Aegis 采用**自研解码器**（净室 Kotlin），
是一个**独立项目，与 RIME 项目无从属关系**——不链接任何 librime 或原生代码。它立足于 **amzxyz** 的
**万象（wanxiang）** 项目所提供的开放数据，在此致以最深的谢意。下述第三方署名与相同方式共享义务
不因 Aegis 自身许可而被免除。

**rime-wanxiang 词库** —— © amzxyz 及 rime-wanxiang 贡献者，**CC BY 4.0**，见
[rime-wanxiang](https://github.com/amzxyz/rime-wanxiang)。可下载的 `aegis_{dict,t9,jianpin}.bin`
与 `aegis_lm.bin` 是全部 14 张表（字 基础 联想 错音 多音 诗词 地名 医学 化学 药品
名人 异体 物种 人名）的衍生物。**改动：**去声调（ü→v），音节拼接为无调键，繁体与异体归并到简体
（OpenCC 表），重新打包为 Aegis 的二进制格式；词库包保留每一条（`--min-freq 1`）。

**万象八元语法模型**（`wanxiang-lts-zh-hans.gram`）—— © amzxyz，**CC BY 4.0**，见
[RIME-LMDG](https://github.com/amzxyz/RIME-LMDG)。用于下一词和整句排序的可选顶层上下文模型；仅在明确
选择时才下载，**不**打包进 APK。`OctagramReader`（Kotlin，GPL-3.0）是 Aegis 原创代码；其磁盘格式
由 **librime-octagram**（GPL-3.0）与 **darts-clone** 净室逆向而得——未复制任何上游源码。

**OpenCC 数据**（`tools/t2s-data`）—— 繁转简与异体映射，**Apache-2.0**（见
`tools/t2s-data/LICENSE-OpenCC` 与 `tools/t2s-data/PROVENANCE.md`）；仅在构建词库时使用。

**其他：**AndroidX、Jetpack Compose、Material 3、Kotlin 标准库 —— Apache-2.0；JUnit —— EPL
（仅测试范围，不随包分发）。算法参考（未内联引入）：AOSP PinyinIME（Apache-2.0）、
darts-clone（BSD-2-Clause）。

## 状态

Aegis 处于活跃开发中，当前发布均为**预发布（debug）**构建。已知限制：

- 发布为 debug 签名的预发布 APK，经由 GitHub Releases 分发，而非应用商店。
- 可下载的词库包记录了其构建输入，但尚不是经签名或可独立复现的供应链产物（见
  [发布词库包](#发布词库包)）。
