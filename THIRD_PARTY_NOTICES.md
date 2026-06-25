# Third-Party Notices

## Bundled dictionary data — rime-wanxiang (万象)

`app/src/main/assets/aegis_dict.bin` (26-key), `aegis_t9.bin` (9-key/T9), and
`aegis_lm.bin` (character bigram) are prebuilt, tone-stripped, re-serialized
derivatives of the **rime-wanxiang** dictionaries — `dicts/{zi, jichu, lianxiang,
shici, diming, duoyin, mingren, renming}` — frequency-filtered for size. Built by
`tools/` (see `DictBuilder` / `LmBuilder`).

- Source: https://github.com/amzxyz/rime-wanxiang (branch `wanxiang`)
- License: **CC BY 4.0** (https://creativecommons.org/licenses/by/4.0/)
- Attribution: © amzxyz and rime-wanxiang contributors.
- Changes: tones stripped (ü→v), syllables concatenated into toneless keys,
  frequency-filtered, repacked into Aegis's binary dictionary format. No data
  files are DRM-locked.

The upstream "离线大模型" `.gram` and the ~32 GB training corpus are **not**
bundled here; any future inclusion is gated in `README.md`.

## Skeleton / algorithm references (not yet vendored)

- AOSP PinyinIME (Apache-2.0) — decoder/trie/segmentation reference.
- darts-clone (BSD / LGPL-2.1) — double-array trie design reference.
