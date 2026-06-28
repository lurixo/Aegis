# Third-Party Notices

Aegis's own source code is licensed **GPL-3.0** (see `LICENSE`). That does not waive the
attribution / ShareAlike obligations of the bundled or downloaded third-party data and the
referenced works listed below; those remain under their own licenses.

## Bundled dictionary data — rime-wanxiang (万象)

`app/src/main/assets/aegis_dict.bin` (26-key), `aegis_t9.bin` (9-key/T9) and
`aegis_jianpin.bin` (26-key 简拼 initials) are prebuilt, tone-stripped, re-serialized
derivatives of the **rime-wanxiang** dictionaries — the full set of 14 tables
`dicts/{zi, jichu, lianxiang, cuoyin, duoyin, shici, diming, yixue, huaxue, yaopin,
mingren, yiren, wuzhong, renming}` — bundled in full, every entry with freq ≥ 1 (no
frequency floor). `aegis_lm.bin` (character bigram) is a context model derived from the
same source. Built by `tools/` (see `DictBuilder` / `LmBuilder`).

- Source: https://github.com/amzxyz/rime-wanxiang (branch `wanxiang`)
- License: **CC BY 4.0** (https://creativecommons.org/licenses/by/4.0/)
- Attribution: © amzxyz and rime-wanxiang contributors.
- Changes: tones stripped (ü→v), syllables concatenated into toneless keys, repacked
  into Aegis's binary dictionary format (no entries dropped for size). No data files
  are DRM-locked.

The upstream "离线大模型" `.gram` and the ~32 GB training corpus are **not**
bundled here; any future inclusion is gated in `README.md`.

## Optional download — wanxiang octagram model

The wanxiang octagram `.gram` (~401 MB, fetched only on explicit user opt-in to
`filesDir/downloaded/`) is **CC BY 4.0** — amzxyz/RIME-LMDG
(https://github.com/amzxyz/RIME-LMDG). Not bundled in the APK.

## Clean-room reader

`OctagramReader` (Kotlin) is original Aegis code (GPL-3.0). Its on-disk format was
reverse-engineered, clean-room, from **librime-octagram** (GPL-3.0) + **darts-clone**; no upstream
source was copied.

## Runtime / build libraries

- AndroidX, Jetpack Compose, Material 3, Kotlin stdlib — **Apache-2.0** (compatible with GPL-3.0).
- JUnit — **EPL** (test scope only; not distributed in the APK).

## Algorithm / skeleton references (not vendored)

- AOSP PinyinIME (Apache-2.0) — decoder/trie/segmentation reference.
- darts-clone (BSD-2-Clause) — double-array trie design reference.
