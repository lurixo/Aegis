# Aegis 输入法

Offline-first Android input method (输入法) for Simplified Chinese + English, built on the open
**rime-wanxiang (万象)** CC-BY dictionaries with a **self-built decoder** — no rime/librime at
runtime. All input stays on device; there is no network use in the typing path.

## Features

- **26-key full pinyin** and **9-key T9**, plus **number** and **symbol** layouts (self-drawn
  View+Canvas keyboard).
- **Lattice Viterbi decoder** over a memory-mapped dictionary, scored by unigram log-probability +
  a **character-bigram** context model. (Top-1 ≈ 69% on a 179-sentence held-out eval.)
- **Fuzzy pinyin** (平翘舌 zh/ch/sh, 前后鼻音 ang/eng/ing) with a user toggle.
- **简拼** initials abbreviation (`zg`→中国, `bjdx`→北京大学).
- **CN-EN mixed input** — commit English (e.g. `wifi`) without switching language.
- **On-device learning** — frequently/recently used words rank up, learned next-word predictions;
  import/export of the user dictionary. Stored only in `filesDir/userdb.txt`.
- **Material 3** setup screen. **Android 14+** (minSdk 34), Kotlin.

## Build

Requires JDK 17+, Android SDK (platform 36, build-tools 36.0.0).

```
./gradlew assembleDebug          # build the APK
./gradlew :app:testDebugUnitTest # JVM unit tests (decoder, dict, fuzzy, 简拼, learning)
./gradlew :app:lintDebug
```

The bundled dictionaries (`app/src/main/assets/aegis_*.bin`) are the **seed** pack — prebuilt from all
14 wanxiang tables (`zi jichu lianxiang cuoyin duoyin shici diming yixue huaxue yaopin mingren yiren
wuzhong renming`) at `--min-freq 400` by the `:tools` module, keeping the APK ~64 MB. The **full** pack
(the same 14 tables at `--min-freq 1`, no per-key cap) is built the same way and hosted as a downloadable
asset; at runtime a downloaded `aegis_*.bin` under `filesDir/downloaded/` overrides the seed
(`AegisInputMethodService.downloadedOverride`). No per-key cap is applied to any tier (简拼 long tails are
kept in full).

```
./gradlew :tools:installDist
# seed (bundled): --min-freq 400 ;  full (download): --min-freq 1
tools/build/install/tools/bin/tools --out <dict> --min-freq 400 --keytype letter   <14 wanxiang .dict.yaml ...>
tools/build/install/tools/bin/tools --out <t9>   --min-freq 400 --keytype digit    <14 ...>
tools/build/install/tools/bin/tools --out <jp>   --min-freq 400 --keytype initials <14 ...>
tools/build/install/tools/bin/tools lm --out <lm> <14 wanxiang .dict.yaml ...>
```

## Architecture

- `app/.../ime` — IME service, self-drawn keyboard/candidate views, input state machine.
- `app/.../decoder` — `PinyinDecoder` (word-lattice Viterbi; exact > fuzzy > 简拼 edges).
- `app/.../dict` — mmap readers: `BinaryDict`, `CharBigramLM`, `Fuzzy`.
- `app/.../user` — `UserModel` (offline learning, file-persisted).
- `tools/` — host dict/LM builders (`DictBuilder`, `LmBuilder`) + eval verifier.

Design notes: see `README.md`.

## Licensing

Code: **GPL-3.0**. Bundled dictionary data derives from **rime-wanxiang** (CC BY 4.0) — see
`THIRD_PARTY_NOTICES.md`. The optional 401 MB wanxiang `.gram` octagram (top-tier context) is **not**
bundled; it is a future opt-in download.

## Status

P1–P8 complete (skeleton → data → decoder → T9 → n-gram → coverage → learning → fuzzy/简拼/mixed),
plus release polish (P9). Remaining: optional-download implementation + `.gram` octagram reader,
English dictionary/correction. Not yet validated on physical hardware in this environment.
