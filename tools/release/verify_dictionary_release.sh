#!/usr/bin/env bash
#
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail
if [[ $# -ne 4 ]]; then
  echo "Usage: $0 PACK UPDATE_JSON BUILD_INFO COVERAGE_BASELINE" >&2
  exit 2
fi
pack="$(realpath -e -- "$1")"
manifest="$(realpath -e -- "$2")"
build_info="$(realpath -e -- "$3")"
fourth="$(realpath -m -- "$4")"
repository="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd -- "$repository"
sdk_directory="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_directory" ]]; then
  echo "Set ANDROID_HOME or ANDROID_SDK_ROOT to the Android SDK directory" >&2
  exit 69
fi
if ! sdk_root="$(realpath -e -- "$sdk_directory" 2>/dev/null)" || [[ ! -d "$sdk_root" ]]; then
  echo "Android SDK directory does not exist: $sdk_directory" >&2
  exit 69
fi
if [[ -n "${ANDROID_HOME:-}" && -n "${ANDROID_SDK_ROOT:-}" ]] && \
    [[ "$(realpath -m -- "$ANDROID_SDK_ROOT")" != "$sdk_root" ]]; then
  echo "ANDROID_HOME and ANDROID_SDK_ROOT identify different SDK directories" >&2
  exit 69
fi
sdk_manager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$sdk_manager" ]]; then
  echo "Android SDK command-line tools are unavailable: $sdk_manager" >&2
  exit 69
fi
export ANDROID_HOME="$sdk_root"
export ANDROID_SDK_ROOT="$sdk_root"
mkdir -p build/test-release-inputs
grammar_lock="$(mktemp -d "$repository/build/test-release-inputs/verification.XXXXXX")/grammar-lock.json"
baseline="$(realpath -e -- "$fourth")"
python3 tools/release/dictionary_verification.py prepare "$pack" "$manifest" "$build_info" --grammar-lock "$grammar_lock" --baseline "$baseline"
python3 tools/release/verify_toolchain.py --repository . > build/toolchain-identity.json
python3 tools/release/test_verify_toolchain.py
python3 tools/release/test_build_dictionary_pack.py
python3 tools/release/test_build_tgh_asset.py
python3 tools/release/test_fetch_test_dict.py
python3 tools/release/test_dictionary_verification.py
export AEGIS_FULLDICT_DIR="$repository/app/src/main/assets"
export AEGIS_LOCKED_BASELINE="$repository/app/src/test/resources/locked-sequence-2.tsv"
export AEGIS_ENGLISH="$repository/build/test-models/aegis_english.bin"
export AEGIS_GRAM="$repository/build/test-model-cache/wanxiang-lts-zh-hans.gram"
export AEGIS_BUILD_INFO="$build_info"
export AEGIS_GRAMMAR_LOCK="$grammar_lock"
export AEGIS_AUDIT_FULL=1
export AEGIS_AUDIT_HEAVY=0
export AEGIS_BOOST_REPORT=1
export AEGIS_BOOST_SMOKE=1
export AEGIS_COVERAGE_DIGEST_BASELINE="$baseline"
export AEGIS_DICTIONARY_RELEASE_VERIFY=1
unset AEGIS_COVERAGE_DIGEST_DUMP
python3 tools/release/check_rare_criterion_gate.py \
  --lm app/src/main/assets/aegis_lm.bin \
  --authority app/src/main/assets-src/tongyong-guifan-hanzi-8105.tsv \
  --json build/rare-criterion-gate.json
./gradlew :app:testDebugUnitTest :app:assembleDebug --rerun-tasks --no-build-cache --stacktrace
python3 tools/release/dictionary_verification.py results app/build/test-results/testDebugUnitTest
./gradlew :app:lintDebug --rerun-tasks --no-build-cache --stacktrace
./gradlew :app:assembleRelease --rerun-tasks --no-build-cache --stacktrace
./gradlew :app:verifyExternalModelsNotPackaged --no-build-cache --stacktrace
"$sdk_manager" --sdk_root="$sdk_root" --list_installed > build/android-sdk-packages.txt
for report in configuration mapping seeds usage; do
  test -s "app/build/outputs/mapping/release/$report.txt"
done
