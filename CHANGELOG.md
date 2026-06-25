# Changelog

## 0.1.0 — first feature-complete cut

First end-to-end build of Aegis: an offline-first Android IME with a **self-built pinyin engine**
on open **rime-wanxiang (CC-BY)** data — no rime/librime at runtime. Android 14+ (minSdk 34), Kotlin,
Material 3.

### Input
- 26-key full pinyin and 9-key **T9**, plus number and symbol layouts (self-drawn View+Canvas).
- Word-lattice **Viterbi decoder** over a memory-mapped dictionary; unigram + **character-bigram**
  context. Held-out top-1 ≈ **69%** (bundled) → **79%** with the optional octagram download.
- **Fuzzy pinyin** (平翘舌 zh/ch/sh, 前后鼻音 ang/eng/ing) with a user toggle.
- **简拼** initials abbreviation (`zg`→中国, `bjdx`→北京大学).
- **CN-EN mixed input** (commit English without switching).
- **English** mode: frequency-ranked completion + edit-distance-1 correction.
- Shift one-shot / caps-lock.

### Intelligence
- On-device **learning** (word frequency/recency + user bigram), next-word prediction, import/export.
  Stored only in `filesDir/userdb.txt`.
- Optional **enhancement download**: the wanxiang octagram `.gram` (~401 MB) via a clean-room Kotlin
  reader (`OctagramReader`); measured +9.5 top-1 points. INTERNET permission is used only for this
  settings-screen download — the typing path is fully offline.

### Data & tooling
- `:tools` builds all dictionaries/LM from wanxiang + an English frequency list
  (`DictBuilder` / `LmBuilder` / `EnBuilder`). Bundled assets ~73 MB; APK ~72 MB.
- Verification: JVM unit tests for dict/decoder/T9/fuzzy/简拼/learning/English/octagram; lint clean.

### Validation
- Built + unit-tested green throughout. Validated on an Android 16 (API 36) emulator: APK installs,
  the Compose M3 setup screen renders, and the IME service binds + loads all dictionaries without
  crashing. Live on-keyboard interaction was not exercised — the headless emulator's IME-insets show
  is cancelled for all IMEs (stock keyboard included), so no input view renders there.

### Licensing
- Code: **GPL-3.0**. Bundled data: rime-wanxiang (CC BY 4.0), FrequencyWords/OpenSubtitles
  (CC BY-SA 4.0) — see `THIRD_PARTY_NOTICES.md`. Pre-ship gates tracked in `README.md`.

### Not yet done
- Real-device keyboard interaction test; octagram higher-order (currently bigram-level) context;
  full-dictionary download pack; English next-word prediction; Rust hot-path (deferred — Kotlin
  decoder is fast enough so far).
