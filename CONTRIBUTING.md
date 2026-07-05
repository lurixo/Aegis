# Contributing to Aegis

Thanks for your interest in improving Aegis! This guide covers how to build, test, and submit
changes. By contributing you agree to abide by the [Code of Conduct](CODE_OF_CONDUCT.md).

## Getting started

Prerequisites:

- **JDK 17**
- **Android SDK** with **platform 37** (`compileSdk` / `targetSdk` are 37; `minSdk` is 34)
- **Gradle** is provided by the wrapper — use `./gradlew` (no system Gradle needed)

Common commands:

```
./gradlew assembleDebug           # build the debug APK
./gradlew :app:testDebugUnitTest  # run the JVM unit test suite
./gradlew :app:lintDebug          # Android lint
```

Please make sure `./gradlew :app:testDebugUnitTest` is green before opening a pull request. If your
change affects behavior, add or update tests.

## Project layout

- `app/.../ime` — IME service, self-drawn keyboard/candidate views, panels (emoji, clipboard,
  symbols, edit), input state machine.
- `app/.../decoder` — `PinyinDecoder` (word-lattice Viterbi).
- `app/.../dict` — memory-mapped readers (`BinaryDict`, `CharBigramLM`, `Fuzzy`).
- `app/.../user` — offline learning model.
- `tools/` — host-side dictionary/LM builders and the release packager.

## Dictionaries

The bundled dictionaries are prebuilt from the open **rime-wanxiang** tables; Aegis does not use
rime/librime at runtime. If your change requires rebuilding dictionary assets, see the
**Build** and **Release dictionary pack** sections of the [README](README.md#build-for-developers):
the seed pack is built at `--min-freq 400` and the full downloadable pack at `--min-freq 1`, both via
`tools/DictBuilder`, with traditional/variant forms folded to Simplified using `tools/t2s-data`.

Do not commit large regenerated dictionary binaries in a feature PR unless that is the point of the
change — dictionary packs ship as GitHub release assets, not in-tree.

## Branches & pull requests

- Create a topic branch off **`dev`**.
- Open your pull request against **`dev`**.
- Keep PRs focused; describe what changed and why, and how you verified it.
- Ensure the unit-test suite passes and lint is clean.

## Commit messages

- Write in **English**, imperative mood ("add", "fix", not "added"/"fixes").
- Use a Conventional-Commits-style prefix consistent with the existing history:
  `feat`, `fix`, `docs`, `build`, `refactor`, `test`, `chore` (an optional scope is fine, e.g.
  `feat(kbd): ...`).
- Keep the summary line concise; add a body when context helps.
- Do **not** add automated co-author or tool trailers.

## Commit identity & privacy

- Configure git so your commits use **your** GitHub-provided **no-reply email**
  (GitHub → Settings → Emails → *Keep my email addresses private*), so your personal address is not
  published in the git history.
- The **author and committer** of each commit should be the **same** identity.

```
git config user.name  "<your-github-username>"
git config user.email "<id>+<your-github-username>@users.noreply.github.com"
```

## Reporting bugs & requesting features

Open a GitHub issue with clear reproduction steps (device, Android version, input mode) and expected
vs. actual behavior. For **security** issues, do **not** open a public issue — follow
[SECURITY.md](SECURITY.md).
