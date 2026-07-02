# Aegis IME

Offline-first Android input method for Simplified Chinese and English, built on the open
**rime-wanxiang** CC-BY dictionaries with a **self-built decoder** — no rime/librime at
runtime. All input stays on device; there is no network use in the typing path.

## Features

- **26-key full pinyin** and **9-key T9**, plus **number** and **symbol** layouts (self-drawn
  View+Canvas keyboard).
- **Lattice Viterbi decoder** over a memory-mapped dictionary, scored by unigram log-probability +
  a **character-bigram** context model. (Top-1 ≈ 69% on a 179-sentence held-out eval.)
- **Fuzzy pinyin** for retroflex/flat sibilants and front/back nasals, with a user toggle.
- **Jianpin** initials abbreviation (`zg`→中国, `bjdx`→北京大学).
- **CN-EN mixed input** — commit English (e.g. `wifi`) without switching language.
- **On-device learning** — frequently/recently used words rank up, learned next-word predictions;
  import/export of the user dictionary. Stored only in `filesDir/userdb.txt`.
- **Material 3** setup screen. **Android 14+** (minSdk 34), Kotlin.

## Build

Requires JDK 17+, Android SDK (platform 36, build-tools 36.0.0).

```
./gradlew assembleDebug          # build the APK
./gradlew :app:testDebugUnitTest # JVM unit tests (decoder, dict, fuzzy, jianpin, learning)
./gradlew :app:lintDebug
```

The bundled dictionaries (`app/src/main/assets/aegis_*.bin`) are the **seed** pack — prebuilt from all
14 wanxiang tables (`zi jichu lianxiang cuoyin duoyin shici diming yixue huaxue yaopin mingren yiren
wuzhong renming`) at `--min-freq 400` by the `:tools` module, keeping the APK ~64 MB. The **full** pack
(the same 14 tables at `--min-freq 1`, no per-key cap) is built the same way and hosted as a downloadable
asset; at runtime a downloaded `aegis_*.bin` under `filesDir/downloaded/` overrides the seed
(`AegisInputMethodService.downloadedOverride`). No per-key cap is applied to any tier (jianpin long tails are
kept in full).

```
./gradlew :tools:installDist
# seed pack (bundled): --min-freq 400; full pack (download): --min-freq 1
tools/build/install/tools/bin/tools --out <dict> --min-freq 400 --keytype letter   <14 wanxiang .dict.yaml ...>
tools/build/install/tools/bin/tools --out <t9>   --min-freq 400 --keytype digit    <14 ...>
tools/build/install/tools/bin/tools --out <jp>   --min-freq 400 --keytype initials <14 ...>
tools/build/install/tools/bin/tools lm --out <lm> <14 wanxiang .dict.yaml ...>
```

## Release dictionary pack

Each app release should publish the APK and the current full dictionary pack in the same GitHub
release. The app discovers dictionary updates from `lurixo/Aegis` release assets, preferring the
latest pre-release dictionary ZIP by `published_at`, then falling back to normal releases. The
source/provenance link remains `https://github.com/amzxyz/rime-wanxiang`; the physical download is
the Aegis-converted binary ZIP release asset.

```
tools/release/build_dictionary_pack.py --release-tag v0.1.0-debug.44
```

The command clones `amzxyz/rime-wanxiang` branch `wanxiang` unless `--source-dir` is provided, builds
the 14 verified tables with `tools/DictBuilder` at `--min-freq 1` and no per-key cap, and writes:

- `build/release-dictionary/aegis_dict_pack_debug44.zip`
- `build/release-dictionary/aegis-build-info.json`
- `build/release-dictionary/aegis-dictionary-update.json`

Upload those generated files to the same GitHub release as the APK. The checked-in
`aegis-build-info.json` records the verified debug.13 pack trail and its remaining provenance gaps;
future release-generated build info should replace stale constants with the freshly built asset
name, URL, size, SHA-256, source commit, input YAML hashes, and output bin hashes. A dictionary pack
is not a fully reproducible public supply-chain artifact until the release also has the exact input
hashes, deterministic recipe, and signature or attestation needed to prove it.

## Architecture

- `app/.../ime` — IME service, self-drawn keyboard/candidate views, input state machine.
- `app/.../decoder` — `PinyinDecoder` (word-lattice Viterbi; exact > fuzzy > jianpin edges).
- `app/.../dict` — mmap readers: `BinaryDict`, `CharBigramLM`, `Fuzzy`.
- `app/.../user` — `UserModel` (offline learning, file-persisted).
- `tools/` — host dict/LM builders (`DictBuilder`, `LmBuilder`) + eval verifier.

## Licensing & Acknowledgments

Aegis's own code is **GPL-3.0** (see `LICENSE`). Aegis ships a **self-built decoder**
(clean-room Kotlin) and is an **independent project, not affiliated with the RIME project** — it
links no librime / native code. It stands on open data from the **wanxiang** project by
**amzxyz**, with our deepest thanks. The third-party attribution / ShareAlike obligations below
are not waived by Aegis's own license.

**rime-wanxiang dictionaries** — © amzxyz and rime-wanxiang contributors, **CC BY 4.0**
(https://github.com/amzxyz/rime-wanxiang , branch `wanxiang`). The bundled
`assets/aegis_{dict,t9,jianpin}.bin` and `aegis_lm.bin` are derivatives of the full 14 tables
(字 基础 联想 错音 多音 诗词 地名 医学 化学 药品 名人 异体 物种 人名). **Changes:** tones stripped
(ü→v), syllables concatenated into toneless keys, repacked into Aegis's binary format; the bundled
seed is frequency-filtered (`--min-freq 400`) for size, the downloadable full pack keeps every entry
(`--min-freq 1`).

**wanxiang octagram model** (`wanxiang-lts-zh-hans.gram`, ~401 MB) — © amzxyz, **CC BY 4.0**
(https://github.com/amzxyz/RIME-LMDG). The optional top-tier context model behind next-word /
whole-sentence ranking; fetched only on explicit opt-in, **not** bundled in the APK.
`OctagramReader` (Kotlin, GPL-3.0) is original Aegis code; its on-disk format was clean-room
reverse-engineered from **librime-octagram** (GPL-3.0) + **darts-clone** — no upstream source copied.

**Other:** AndroidX / Jetpack Compose / Material 3 / Kotlin stdlib — Apache-2.0; JUnit — EPL (test
scope only, not distributed). Algorithm references (not vendored): AOSP PinyinIME (Apache-2.0),
darts-clone (BSD-2-Clause).

## Status

P1–P8 complete (skeleton → data → decoder → T9 → n-gram → coverage → learning → fuzzy/jianpin/mixed),
plus release polish (P9). Remaining: optional-download implementation + `.gram` octagram reader,
English dictionary/correction. Not yet validated on physical hardware in this environment.
