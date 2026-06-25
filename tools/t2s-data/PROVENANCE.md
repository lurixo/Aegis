# t2s-data provenance

Source: OpenCC ver.1.1.7 (https://github.com/BYVoid/OpenCC, tag ver.1.1.7), Apache License 2.0
(LICENSE-OpenCC). Files TSCharacters.txt / TSPhrases.txt are verbatim upstream; sha256 at fetch:
- TSCharacters.txt 6b5a0a799bea2bb22c001f635eaa3fc2904310f0c08addbff275477a80ecf09a
- TSPhrases.txt    b2ef895dd4953b4bb77fc8ef8d26a2a9ca6d43a760ed9a1d767672cfafa6324f

variant_to_simplified.tsv is DERIVED from upstream JPVariants.txt (inverted to variant->simplified),
adjudicated: rows whose variant-side form is itself a valid simplified character (疏 醋 欠 缶 予 弁 浜
糸 緒 煙 連 桝 鉱 御 芸 沪 or any char in the OpenCC simplified evidence set) are dropped; supplements
尅→克 疎→疏 鉱→矿 鑛→矿 added by adjudication.

adjudications.tsv: per-reading rulings for OpenCC's reading-sensitive characters (TSCharacters rows
whose source char appears among its own mappings): 乾/徵/彷/祇/衹/藉/麽 map per pinyin syllable;
瞭/薹/覆/阪 are kept unconverted (standard simplified forms); 剋→克 for all readings (this is a
simplified-only IME; kei stays typeable through 克). 夥→伙 and 於→于 follow the OpenCC
first mapping. 牴 is left unconverted at the character level: OpenCC's first image for it is 牴
itself and no row in adjudications.tsv names it. TSPhrases.txt does convert it, in the two
whole-word entries 牴牾→抵牾 and 牴觸→抵触, and the phrase table is applied before any character
mapping, so a pack built from this directory keeps 牴 in every entry except those two words.

Addendum (chain collisions & phrase-protected forms): 苧 is BOTH a traditional form (zhù→苎) and the
standard simplified image of 薴 (níng) — mapped per reading (zhu→苎, else kept). 於 maps yu→于 but is
kept for other readings (樊於期/於菟, wū — OpenCC protects these at phrase level). 吒 is kept for all
readings (哪吒/金吒/木吒 are standard; OpenCC's per-phrase protection is widened to the character, so
the no-traditional candidate gate can operate per character).
