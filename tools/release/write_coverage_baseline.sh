#!/usr/bin/env bash
#
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail
if [[ $# -ne 4 ]]; then
  echo "Usage: $0 PACK UPDATE_JSON BUILD_INFO OUTPUT" >&2
  exit 2
fi
pack="$(realpath -e -- "$1")"
manifest="$(realpath -e -- "$2")"
build_info="$(realpath -e -- "$3")"
fourth="$(realpath -m -- "$4")"
repository="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd -- "$repository"
mkdir -p build/test-release-inputs
grammar_lock="$(mktemp -d "$repository/build/test-release-inputs/verification.XXXXXX")/grammar-lock.json"
output="$(python3 tools/release/dictionary_verification.py output "$fourth" "$repository")"
python3 tools/release/dictionary_verification.py prepare "$pack" "$manifest" "$build_info" --grammar-lock "$grammar_lock"
python3 tools/release/verify_toolchain.py --repository .
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
export AEGIS_COVERAGE_DIGEST_DUMP="$output"
unset AEGIS_DICTIONARY_RELEASE_VERIFY AEGIS_COVERAGE_DIGEST_BASELINE
./gradlew :app:testDebugUnitTest \
  --tests com.aegis.ime.decoder.CoverageIdentityGateTest.writeCoverageDigestWhenAsked \
  --rerun-tasks --no-build-cache --stacktrace
python3 tools/release/dictionary_verification.py results app/build/test-results/testDebugUnitTest --writer
python3 tools/release/dictionary_verification.py baseline "$output"
