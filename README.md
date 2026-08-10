# Aegis IME

<p align="center">
  <img src="docs/branding/banner.png" alt="Aegis: offline input method for Chinese and English" width="860">
</p>

[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/lurixo/Aegis?include_prereleases&sort=semver)](https://github.com/lurixo/Aegis/releases)
[![Platform: Android 14+](https://img.shields.io/badge/Android-14%2B%20(API%2034)-3DDC84.svg)](#requirements)

**Aegis** is an offline-first Android input method for **Simplified Chinese** and **English**.
It is built on the open **rime-wanxiang** CC BY dictionaries with a **self-built decoder**; no
rime / librime at runtime. Everything you type stays on your device: **no network request is ever
made while you type, and nothing you type is ever sent.**

**English** | [简体中文](README.zh-CN.md)

<div align="center">
<table>
<tr>
<td><img src="docs/screenshots/en/keyboard-qwerty.png" alt="26-key keyboard" width="420"></td>
<td><img src="docs/screenshots/en/keyboard-t9.png" alt="9-key T9 keyboard" width="420"></td>
</tr>
</table>
<table>
<tr>
<td><img src="docs/screenshots/en/emoji.png" alt="Emoji panel" width="280"></td>
<td><img src="docs/screenshots/en/clipboard.png" alt="Clipboard history and saved phrases" width="280"></td>
<td><img src="docs/screenshots/en/symbols.png" alt="Symbol panel" width="280"></td>
</tr>
</table>
</div>

## Contents

- [For users: install &amp; enable](#for-users-install--enable)
- [Features](#features)
- [Privacy &amp; permissions](#privacy--permissions)
- [Build (for developers)](#build-for-developers)
- [Release dictionary pack](#release-dictionary-pack)
- [Architecture](#architecture)
- [Licensing &amp; acknowledgments](#licensing--acknowledgments)

## For users: install & enable

Aegis is not on an app store yet; it is distributed as a downloadable APK.

### Requirements

- **Android 14 or newer** (minSdk 34). Older Android versions are not supported.
- A **64-bit ARM** device (`arm64-v8a`); the published APK carries that ABI only.

### Install

1. Open the [**Releases**](https://github.com/lurixo/Aegis/releases) page and download the APK
   asset from the latest build. Releases are marked **pre-release**, and the APK is a release
   build signed with an Android debug key.
2. Because the app is installed outside an app store, Android will ask you to **allow installing
   unknown apps** for the browser or file manager you used; grant it when prompted, then open the
   downloaded APK to install (this is a **sideload**).

### Enable Aegis as a keyboard

Menu names vary slightly by device, but the flow is the standard Android one:

1. **Settings > System > Languages & input > On-screen keyboard** (on some phones: *Manage
   keyboards* / *Virtual keyboard*).
2. Turn **Aegis** on. Android shows a warning that a keyboard can read what you type; this is the
   standard notice for **every** input method; see [Privacy & permissions](#privacy--permissions)
   for exactly what Aegis does and does not do.
3. Switch to Aegis while typing: tap the **input-method / globe key** on the keyboard, or open the
   **"Choose input method"** notification / picker and select **Aegis**.

### First run

**English typing works straight away; Chinese needs one download first.** The APK carries no Chinese
dictionary, and the keyboard never fetches the dictionary pack on its own. When you type Chinese with
no pack installed, the candidate strip offers the download — a ~102 MB transfer that expands to
~272 MB in the app's private storage, not restricted to Wi-Fi — and it starts only when you tap
there, or on the dictionary card in the settings screen. An interrupted transfer resumes where it
stopped, and the pack is checked against its SHA-256 before it is installed. Until it finishes,
Chinese input stays locked, while English and every panel keep working.

The settings screen additionally offers an **optional** enhancement model (the ~420 MB octagram
grammar) for sharper next-word and whole-sentence ranking; that one is fetched only when you tap to
start it. Once a file is installed, its card offers a check-for-updates button, and a check that
finds a newer file continues straight into the download. Beyond those two downloads and the update
checks that go with them (a small metadata file for the dictionary, a `HEAD` request for the model),
Aegis's own code makes no network requests, and nothing Aegis does reaches the network unless you
ask it to.

## Features

- **Keyboards:** **26-key full pinyin**, **9-key T9** and an **English 26-key** layout, plus
  **number**, **symbol** and **numeric-keypad** layouts, on a self-drawn View+Canvas keyboard. In
  landscape the keyboard keeps its portrait width and docks to one side, so the app behind it stays
  reachable.
- **Lattice Viterbi decoder:** over a memory-mapped dictionary, scored by unigram log-probability
  plus a **character-bigram** context model; with the enhancement model installed, a further
  next-word and whole-sentence reranking pass runs on top.
- **Fuzzy pinyin:** ten rules — `zh/z`, `ch/c`, `sh/s`, `ang/an`, `eng/en`, `ing/in`, `n/l`, `f/h`,
  `l/r`, `k/g`. The master switch is off until you turn it on, and each rule then has a switch of
  its own; changes take effect immediately.
- **Jianpin** (initials abbreviation): `zg` -> 中国, `bjdx` -> 北京大学.
- **Syllable control:** on the 9-key keyboard a side column lists the readings for what you typed
  and locks the one you pick; picking a locked reading again offers the characters that share it.
  While composing, a segmentation key splits a run of letters where you want it.
- **CN-EN mixed input:** commit English words (e.g. `wifi`) without switching language.
- **On-device learning:** frequently and recently used words rank up and next-word pairs are
  learned. Automatic learning has its own switch, and next-word suggestions in the candidate strip
  have another one that is off until you turn it on. Automatically learned entries fade out after
  months of disuse; words you add yourself are never forgotten. No word is learned in a password
  field, or in any field that asks for no personalized learning; in those fields the symbol and
  emoji panels do not count what you pick either. Everything lives in the
  app's private `filesDir` (`userdb.txt` and `userlearn.txt`), and the user-dictionary screen lets
  you search, add, delete, import, export and clear it.
- **Emoji panel:** a *Common* tab plus categories (smileys, gestures, flags, animals, plants, food,
  travel, activities, objects, symbols), with skin-tone and gender variants and a lock toggle for
  picking several in a row.
- **Clipboard history:** clips you copy are kept for reuse, with per-item and batch delete, select
  all, and clear. The history can also be switched off entirely.
- **Saved phrases (常用语):** reusable phrases in categories you name, reorder and move between,
  each with an optional note, importable and exportable as a plain text file.
- **Symbol & edit panels:** a symbol board with a *Common* tab plus Chinese, English, currency, web,
  math, Greek, arrow, super/subscript, numbering, IPA and pinyin categories and a lock toggle; and a
  text-editing panel with cursor movement, line start/end, selection, select-all, copy, cut, paste
  and delete.
- **Custom symbols:** choose which punctuation sits in the 9-key side column and which operators sit
  on the numeric keypad.
- **Symbol & emoji associations:** typing pinyin can offer matching symbols and emoji directly in
  the candidate strip.
- **Calculator:** type an expression and the candidate strip offers its result; `+ - * / × ÷`,
  parentheses, signs and `%` are understood.
- **Copy bar:** text you just copied or cut is offered back above the keyboard, either whole or
  split into pieces you pick from.
- **Encrypted backup:** export your learning dictionary, auto learning, saved phrases, clipboard
  history, symbol history, emoji history and all settings into a single encrypted file
  (AES-256-GCM, key derived with PBKDF2-HMAC-SHA256
  at 600,000 iterations), and import it back by overwriting or merging. A default backup password
  can be kept on the device behind biometric or screen-lock authentication. The downloadable
  dictionary and model are not in the backup; they can be downloaded again.
- **Simplified-Chinese normalization:** every candidate Aegis proposes is Simplified. Traditional
  and variant character forms from the upstream data are folded to their Simplified image (with
  frequency merging) when the dictionary pack is built, using the OpenCC tables in
  `tools/t2s-data`. That happens on the build host; no conversion table ships in the APK.
- **Material 3** settings screens: input settings, dictionaries & downloads, user dictionary, data
  backup, about & enable, and an open-source licenses page.

## Privacy & permissions

A keyboard sees everything you type, so trust is the whole point. Aegis is built so that trust does
not depend on our word alone:

- The app's own manifest declares **two** Android permissions: **`INTERNET`** and
  **`USE_BIOMETRIC`**. The installed APK lists a third,
  `com.aegis.ime.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which the AndroidX libraries add when the
  manifests are merged; it is not a platform permission, is declared at `signature` level in Aegis's
  own namespace, and asks for nothing on your device.
- **`INTERNET`** fetches the dictionary pack (offered by the keyboard while no pack is installed,
  because no Chinese dictionary ships in the APK, and started only when you tap), the optional
  enhancement model (only when *you* tap to start it), and the update checks for those two. **No
  network request is ever made while you type, and nothing you type is ever sent.**
- **`USE_BIOMETRIC`** is used only for the default backup password: saving it, or filling it into a
  backup dialog, needs a biometric or screen-lock confirmation first.
- Your **keystrokes, candidates, learned words, user dictionary, and clipboard never leave the
  device**: they live in the app's private storage (`filesDir`). The only way any of it leaves is a
  file *you* export — a backup, your learning dictionary, or your phrases — to a location you pick.
- Aegis is excluded from Android's **cloud backup and device-to-device transfer**, so its data is
  not carried off the device that way either.
- There is **no analytics, no telemetry, and no account.**
- One part of the APK is not Aegis's own code: `androidx.emoji2` arrives as part of Jetpack Compose
  and, once one of Aegis's own screens has been opened, looks for a package in the system image that
  offers an emoji font and asks it for one; where the device has no such package, nothing is asked.
  It does this at most once per run of the app, and typing does not trigger it. Aegis opens no
  connection for it and sends nothing of yours with it.

See [PRIVACY.md](PRIVACY.md) for the full statement.

## Build (for developers)

Prerequisites:

- **JDK 25** — the toolchain CI builds with; Java and Kotlin bytecode target 17
- **Android SDK** with **platform 37** and **build-tools 36** (`compileSdk` / `targetSdk` are 37;
  `minSdk` is 34)
- **Gradle** is provided by the wrapper; just use `./gradlew` (no system Gradle needed)

```
./gradlew assembleDebug           # build the debug APK
./gradlew :app:testDebugUnitTest  # JVM unit tests (decoder, dict, fuzzy, jianpin, learning, UI)
./gradlew :app:lintDebug          # Android lint
```

No dictionary-derived asset is packaged in the APK at all: `app/build.gradle.kts` excludes
`aegis_dict.bin`, `aegis_t9.bin`, `aegis_jianpin.bin` and the character-bigram context model
`aegis_lm.bin` from the packaged assets. All four are downloaded at runtime into
`filesDir/downloaded/` as members of one dictionary pack; the three dictionaries are the only
source of Chinese candidates and `aegis_lm.bin` reranks them. Decoding does not strictly require
`aegis_lm.bin`, but a pack missing it counts as incomplete and the app offers the download again.

The decode tests that need the real tables read them from the directory named by
`AEGIS_FULLDICT_DIR`; `python3 tools/fetch_test_dict.py` downloads the published pack and unpacks it
into `app/src/main/assets/`, where it stays out of both the APK and git.

The pack is prebuilt by the `:tools` module from all 14 wanxiang tables (`zi jichu lianxiang cuoyin
duoyin shici diming yixue huaxue yaopin mingren yiren wuzhong renming`) at `--min-freq 1` with no
per-key cap, so it keeps every entry.

```
./gradlew :tools:installDist
tools/build/install/tools/bin/tools --out <dict> --min-freq 1 --keytype letter   --t2s-data tools/t2s-data <14 wanxiang .dict.yaml ...>
tools/build/install/tools/bin/tools --out <t9>   --min-freq 1 --keytype digit    --t2s-data tools/t2s-data <14 ...>
tools/build/install/tools/bin/tools --out <jp>   --min-freq 1 --keytype initials --t2s-data tools/t2s-data <14 ...>
tools/build/install/tools/bin/tools lm --out <lm> --t2s-data tools/t2s-data <14 wanxiang .dict.yaml ...>
```

The screenshots in this README are rendered, not photographed: the unit tests write them to
`app/build/render/i18n/{en,zh}/`, and `docs/screenshots/` holds the copies linked above.

## Release dictionary pack

App APK releases and downloadable dictionary packs are published separately. Versioned app releases
carry the APK only. The dictionary pack is published on the rolling
[`dict-latest`](https://github.com/lurixo/Aegis/releases/tag/dict-latest) GitHub release, and the
app discovers dictionary updates from that single release tag by comparing the installed pack's
SHA-256 with the current dictionary ZIP asset, or by noticing that the installed pack is missing one
of its members.

```
tools/release/build_dictionary_pack.py --release-tag dict-latest
```

The command clones `amzxyz/rime-wanxiang` — the `wanxiang` branch by default, `--source-tag` to pin
a release tag instead, `--source-dir` to use an existing checkout — builds the 14 verified tables
with `tools/DictBuilder` at `--min-freq 1` and no per-key cap, and writes the pack ZIP,
`aegis-build-info.json`, and `aegis-dictionary-update.json` under `build/release-dictionary/`.
Upload those generated files to the rolling `dict-latest` release, not to versioned app releases.
The checked-in `aegis-build-info.json` records the trail of the pack it was generated from (source
tag & commit, per-table input hashes, build parameters, output-bin hashes, and physical asset URL)
and its remaining provenance gaps; regenerate it whenever the rolling dictionary pack is
republished. A dictionary pack is not a fully reproducible public supply-chain artifact until the
release also carries the exact input hashes, a deterministic recipe, and a signature or attestation.

## Architecture

- `app/.../ime`: IME service, self-drawn keyboard/candidate views, panels (emoji, clipboard,
  symbols, custom symbols, edit), copy bar, input state machine.
- `app/.../decoder`: `PinyinDecoder` (word-lattice Viterbi; exact > fuzzy > jianpin edges).
- `app/.../dict`: mmap readers `BinaryDict`, `CharBigramLM`, `Fuzzy`, `OctagramReader`, and
  `ModelDownload` — the one place any network request is made.
- `app/.../engine`: candidate assembly, symbol/emoji associations, calculator.
- `app/.../layout`: key and layout definitions, symbol and emoji catalogs.
- `app/.../user`: on-device data — `UserModel` and `UserLearning`, clipboard and phrase store,
  custom symbols, symbol usage.
- `app/.../backup`: encrypted backup archive, crypto, restore.
- `app/.../ui`: Compose settings screens, dictionary/model cards, user dictionary, backup, about,
  licenses.
- `tools/`: host dict/LM builders (`DictBuilder`, `LmBuilder`) + eval verifier + release packager.

## Licensing & acknowledgments

Aegis's own code is **GPL-3.0** (see [`LICENSE`](LICENSE)). Aegis ships a **self-built decoder**
(clean-room Kotlin) and is an **independent project, not affiliated with the RIME project**; it
links no librime / native code. It stands on open data from the **wanxiang** project by **amzxyz**,
with our deepest thanks. The third-party attribution / ShareAlike obligations below are not waived
by Aegis's own license.

**rime-wanxiang dictionaries:** (c) amzxyz and rime-wanxiang contributors, **CC BY 4.0**
([rime-wanxiang](https://github.com/amzxyz/rime-wanxiang)). The downloadable
`aegis_{dict,t9,jianpin}.bin` and `aegis_lm.bin` are derivatives of the full 14
tables (字 基础 联想 错音 多音 诗词 地名 医学 化学 药品 名人 异体 物种 人名). **Changes:** tones
stripped (`ü` -> `v`), syllables concatenated into toneless keys, traditional/variant forms folded
to Simplified (OpenCC tables), repacked into Aegis's binary format; the pack keeps every entry
(`--min-freq 1`).

**wanxiang octagram model** (`wanxiang-lts-zh-hans.gram`): (c) amzxyz, **CC BY 4.0**
([RIME-LMDG](https://github.com/amzxyz/RIME-LMDG)). The optional top-tier context model behind next-word /
whole-sentence ranking; fetched only on explicit opt-in, **not** bundled in the APK.
`OctagramReader` (Kotlin, GPL-3.0) is original Aegis code; its on-disk format was clean-room
reverse-engineered from **librime-octagram** (GPL-3.0) + **darts-clone**; no upstream source copied.

**OpenCC data** (`tools/t2s-data`): traditional-to-simplified and variant mappings, **Apache-2.0**
(see `tools/t2s-data/LICENSE-OpenCC` and `tools/t2s-data/PROVENANCE.md`); used at dictionary-build
time only.

**Other:** AndroidX / Jetpack Compose / Material 3 / Kotlin stdlib: Apache-2.0; JUnit: EPL (test
scope only, not distributed). Algorithm references (not vendored): AOSP PinyinIME (Apache-2.0),
darts-clone (BSD-2-Clause).

The full list, with the modifications made to each work, is in
[`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md); the app shows the same list under
**Settings → About & enable → Open-source licenses**.
