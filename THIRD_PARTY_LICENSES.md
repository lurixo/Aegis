# Third-Party Licenses

Aegis's own source code is licensed **GPL-3.0-only** (see [`LICENSE`](LICENSE)). This file lists the
third-party works Aegis builds on, their licenses, and how Aegis modified them. These third-party
attribution obligations are **not** waived by Aegis's own license. The in-app **Settings → About &
enable → Open-source licenses** screen shows the same list, and every downloadable dictionary pack ZIP
carries a `NOTICE.txt` repeating the dictionary attribution.

Aegis ships a **self-built decoder** (clean-room Kotlin), links no rime / librime / native code, and is
an independent project not affiliated with the RIME project.

---

## 1. rime-wanxiang dictionaries — CC BY 4.0

- **Copyright:** © amzxyz and the rime-wanxiang contributors
- **License:** Creative Commons Attribution 4.0 International (CC BY 4.0) —
  <https://creativecommons.org/licenses/by/4.0/>
- **Source:** <https://github.com/amzxyz/rime-wanxiang> (tag `v16.3.0`, commit
  `ef047401ef5d2f80cb7f88641722da24e222a017`)
- **Used in:** the downloadable dictionary pack — `aegis_{dict,t9,jianpin}.bin`, which is where every
  Chinese candidate comes from, and `aegis_lm.bin`, the character-bigram context model; nothing
  derived from these tables ships inside the APK. All are derived from the 14 tables
  (字 基础 联想 错音 多音 诗词 地名 医学 化学 药品 名人 异体 物种 人名).
- **Modifications:** tones stripped (ü→v), syllables concatenated into toneless keys, repacked into
  Aegis's own binary format; the pack keeps every entry (`--min-freq 1`). `aegis_lm.bin` is compiled
  from the same tables into a character-bigram language model in Aegis's format (used for context
  ranking).

## 2. wanxiang octagram grammar model — CC BY 4.0

- **Copyright:** © amzxyz
- **License:** CC BY 4.0 — <https://creativecommons.org/licenses/by/4.0/>
- **Source:** <https://github.com/amzxyz/RIME-LMDG> (`wanxiang-lts-zh-hans.gram`, ~401 MB)
- **Used in:** the optional top-tier context model behind next-word / whole-sentence ranking. Fetched
  only on explicit opt-in; **not** bundled in the APK.
- **Modifications:** none to the model bytes (downloaded and used as published). `OctagramReader`
  (Aegis, GPL-3.0) is original code whose on-disk format was clean-room reverse-engineered from
  librime-octagram (GPL-3.0) + darts-clone — no upstream source copied.

## 3. OpenCC conversion data — Apache-2.0

- **Copyright:** © BYVoid and the OpenCC contributors
- **License:** Apache License 2.0 (full text in Appendix A)
- **Source:** <https://github.com/BYVoid/OpenCC>
- **Used in:** `tools/t2s-data` — the traditional/variant → simplified conversion tables applied at
  dictionary build time (see `tools/t2s-data/PROVENANCE.md` and `tools/t2s-data/LICENSE-OpenCC`).
- **Modifications:** the conversion mappings were adjudicated/curated for the dictionary build and used
  as data only (no OpenCC code is linked).

## 4. Unicode / CLDR emoji data — Unicode License

- **Copyright:** © Unicode, Inc.
- **License:** Unicode License v3 (Unicode Data Files and Software) — <https://www.unicode.org/license.txt>
- **Used in:** the emoji catalogue glyphs, names and ordering.
- **Modifications:** a subset was selected and mapped to Chinese keywords; used as data only.

## 5. AndroidX / Jetpack Compose / Material 3 / Kotlin standard library — Apache-2.0

- **Copyright:** © The Android Open Source Project; © JetBrains s.r.o. and the Kotlin contributors
- **License:** Apache License 2.0 (full text in Appendix A)
- **Used in:** the app UI (Jetpack Compose, Material 3, Navigation Compose, AndroidX core / activity /
  lifecycle) and the Kotlin standard library. Linked as unmodified binary dependencies.
- **Modifications:** none (used as published).

## Other references (not vendored, no source copied)

- Algorithm references only: AOSP PinyinIME (Apache-2.0), darts-clone (BSD-2-Clause).
- JUnit (EPL-2.0) and Robolectric (Apache-2.0) — **test scope only**, not distributed in the app.

---

## Appendix A — Apache License 2.0 (full text)

```
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or Derivative
          Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```
