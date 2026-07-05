#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path


TABLES = [
    "zi",
    "jichu",
    "lianxiang",
    "cuoyin",
    "duoyin",
    "shici",
    "diming",
    "yixue",
    "huaxue",
    "yaopin",
    "mingren",
    "yiren",
    "wuzhong",
    "renming",
]

OUTPUTS = [
    ("aegis_dict_full.bin", "aegis_dict.bin", "letter"),
    ("aegis_t9_full.bin", "aegis_t9.bin", "digit"),
    ("aegis_jianpin_full.bin", "aegis_jianpin.bin", "initials"),
]


def run(cmd, cwd):
    print("+", " ".join(str(part) for part in cmd), flush=True)
    subprocess.run(cmd, cwd=cwd, check=True)


def output(cmd, cwd):
    return subprocess.check_output(cmd, cwd=cwd, text=True).strip()


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def default_asset_name(release_tag):
    match = re.fullmatch(r"v\d+\.\d+\.\d+-debug\.(\d+)", release_tag)
    if match:
        return f"aegis_dict_pack_debug{match.group(1)}.zip"
    safe = re.sub(r"[^A-Za-z0-9._-]+", "-", release_tag).strip("-").replace(".", "")
    return f"aegis_dict_pack_{safe}.zip"


def ensure_source_checkout(args, work_dir):
    if args.source_dir:
        source = Path(args.source_dir).resolve()
        if not source.exists():
            raise SystemExit(f"source dir does not exist: {source}")
        return source

    source = work_dir / "rime-wanxiang"
    if source.exists():
        shutil.rmtree(source)
    # A tag pin (preferred: a stable upstream release) clones that tag; otherwise the branch HEAD.
    # git clone --branch accepts a tag name as well as a branch name.
    clone_ref = args.source_tag or args.source_branch
    run(
        [
            "git",
            "clone",
            "--depth",
            "1",
            "--branch",
            clone_ref,
            args.source_repo,
            str(source),
        ],
        cwd=work_dir,
    )
    return source


def write_zip(zip_path, entries):
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for entry_name, file_path in entries:
            info = zipfile.ZipInfo(entry_name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            zf.writestr(info, file_path.read_bytes())


def build_info(args, repo_root, source, asset_name, zip_path, bin_infos, source_infos):
    release_url = f"https://github.com/lurixo/Aegis/releases/tag/{args.release_tag}"
    asset_url = f"https://github.com/lurixo/Aegis/releases/download/{args.release_tag}/{asset_name}"
    source_commit = output(["git", "rev-parse", "HEAD"], cwd=source)
    builder_commit = output(["git", "rev-parse", "HEAD"], cwd=repo_root)
    dirty = output(["git", "status", "--short"], cwd=repo_root)
    generated_at = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")

    return {
        "schema_version": 1,
        "schema_name": "aegis.resource-build-info",
        "generated_at": generated_at,
        "app": {
            "project": "Aegis",
            "repository": "https://github.com/lurixo/Aegis",
            "release_tag": args.release_tag,
        },
        "resources": [
            {
                "kind": "dictionary",
                "physical_asset": {
                    "name": asset_name,
                    "url": asset_url,
                    "release_tag": args.release_tag,
                    "release_url": release_url,
                    "prerelease": args.prerelease,
                    "published_at": None,
                    "sha256": sha256_file(zip_path),
                    "size_bytes": zip_path.stat().st_size,
                },
                "source": {
                    "repo": args.source_repo_https,
                    "ref_type": "tag" if args.source_tag else "branch",
                    "tag": args.source_tag,
                    "branch": None if args.source_tag else args.source_branch,
                    "commit": source_commit,
                    "license": "CC-BY-4.0",
                    "tables": TABLES,
                    "input_yaml_sha256": source_infos,
                },
                "build": {
                    "builder_path": "tools/src/main/kotlin/com/aegis/tools/DictBuilder.kt",
                    "builder_commit": builder_commit,
                    "builder_tree_dirty": bool(dirty),
                    "full_pack_parameters": {
                        "min_freq": 1,
                        "max_per_key": None,
                        "commands": [
                            "--out aegis_dict_full.bin --min-freq 1 --keytype letter --t2s-data tools/t2s-data",
                            "--out aegis_t9_full.bin --min-freq 1 --keytype digit --t2s-data tools/t2s-data",
                            "--out aegis_jianpin_full.bin --min-freq 1 --keytype initials --t2s-data tools/t2s-data",
                        ],
                    },
                    "seed_parameters": {
                        "min_freq": 400,
                        "max_per_key": None,
                        "keep_syllable_singles": 3,
                        "keep_syllable_singles_keytypes": ["letter", "digit"],
                        "commands": [
                            "--out aegis_dict.bin --min-freq 400 --keep-syllable-singles 3 --keytype letter --t2s-data tools/t2s-data",
                            "--out aegis_t9.bin --min-freq 400 --keep-syllable-singles 3 --keytype digit --t2s-data tools/t2s-data",
                            "--out aegis_jianpin.bin --min-freq 400 --keytype initials --t2s-data tools/t2s-data",
                        ],
                    },
                    "t2s_data": {
                        "path": "tools/t2s-data",
                        "provenance": "tools/t2s-data/PROVENANCE.md",
                        "license": "Apache-2.0 for the OpenCC tables (tools/t2s-data/LICENSE-OpenCC)",
                        "effect": "traditional and variant forms merge into their simplified image with frequency merging",
                    },
                    "output_bins": bin_infos,
                    "zip_packaging": {
                        "file_order": [item[0] for item in OUTPUTS],
                        "timestamp_utc": "1980-01-01T00:00:00Z",
                        "unix_mode": "0644",
                        "compression": "zip_deflated_level_9",
                    },
                },
                "attestation": {
                    "status": "not_attested",
                    "reproducibility_status": "build_inputs_recorded_but_unsigned",
                    "missing": [
                        "signature or attestation",
                        "independent external rebuild verification",
                    ],
                },
            }
        ],
        "external_resource_references": [
            {
                "kind": "grammar_model",
                "physical_asset": {
                    "name": "wanxiang-lts-zh-hans.gram",
                    "url": "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram",
                    "release_tag": "LTS",
                    "release_url": "https://github.com/amzxyz/RIME-LMDG/releases/tag/LTS",
                    "prerelease": False,
                    "published_at": None,
                    "sha256": None,
                    "size_bytes": 420538412,
                },
                "source": {
                    "repo": "https://github.com/amzxyz/RIME-LMDG",
                    "branch": None,
                    "commit": None,
                },
                "attestation": {
                    "status": "external_resource_not_attested_by_aegis",
                },
            }
        ],
    }


def update_payload(build_info_json):
    dictionary = build_info_json["resources"][0]
    return {
        "schema_version": 1,
        "kind": "dictionary_update",
        "asset": dictionary["physical_asset"],
        "source": {
            "repo": dictionary["source"]["repo"],
            "ref_type": dictionary["source"]["ref_type"],
            "tag": dictionary["source"]["tag"],
            "branch": dictionary["source"]["branch"],
            "commit": dictionary["source"]["commit"],
        },
    }


def main(argv):
    parser = argparse.ArgumentParser(description="Build the latest Aegis full dictionary release pack.")
    parser.add_argument("--release-tag", required=True, help="GitHub release tag that will host the dictionary asset.")
    parser.add_argument("--output-dir", default="build/release-dictionary", help="Directory for generated artifacts.")
    parser.add_argument("--source-dir", help="Existing rime-wanxiang checkout to use instead of cloning.")
    parser.add_argument("--source-repo", default="https://github.com/amzxyz/rime-wanxiang.git")
    parser.add_argument("--source-repo-https", default="https://github.com/amzxyz/rime-wanxiang")
    parser.add_argument("--source-branch", default="wanxiang")
    parser.add_argument("--source-tag", help="Upstream release tag to pin (records source.tag and clones this tag instead of the branch HEAD). Prefer the latest stable tag that carries the dicts/ tables.")
    parser.add_argument("--asset-name", help="Dictionary ZIP asset name. Defaults to the debug.13 naming pattern.")
    parser.add_argument("--release", dest="prerelease", action="store_false", help="Mark generated metadata as a normal release.")
    parser.set_defaults(prerelease=True)
    args = parser.parse_args(argv)

    repo_root = Path(__file__).resolve().parents[2]
    output_dir = (repo_root / args.output_dir).resolve()
    work_dir = output_dir / "work"
    staging_dir = output_dir / "staging"
    work_dir.mkdir(parents=True, exist_ok=True)
    if staging_dir.exists():
        shutil.rmtree(staging_dir)
    staging_dir.mkdir(parents=True)

    source = ensure_source_checkout(args, work_dir)
    input_paths = [source / "dicts" / f"{table}.dict.yaml" for table in TABLES]
    missing = [str(path) for path in input_paths if not path.exists()]
    if missing:
        raise SystemExit("missing source tables:\n" + "\n".join(missing))

    run([str(repo_root / "gradlew"), ":tools:installDist"], cwd=repo_root)
    tool_bin = repo_root / "tools" / "build" / "install" / "tools" / "bin" / "tools"

    # Traditional and variant forms merge into their simplified image during the build; the
    # adjudicated conversion data lives in tools/t2s-data (see tools/t2s-data/PROVENANCE.md).
    t2s_dir = repo_root / "tools" / "t2s-data"
    if not t2s_dir.exists():
        raise SystemExit(f"t2s data dir missing: {t2s_dir}")

    bin_infos = []
    for zip_entry, runtime_name, key_type in OUTPUTS:
        out_path = staging_dir / zip_entry
        run(
            [
                str(tool_bin),
                "--out",
                str(out_path),
                "--min-freq",
                "1",
                "--keytype",
                key_type,
                "--t2s-data",
                str(t2s_dir),
                *[str(path) for path in input_paths],
            ],
            cwd=repo_root,
        )
        bin_infos.append(
            {
                "zip_entry": zip_entry,
                "runtime_name": runtime_name,
                "sha256": sha256_file(out_path),
                "size_bytes": out_path.stat().st_size,
            }
        )

    asset_name = args.asset_name or default_asset_name(args.release_tag)
    zip_path = output_dir / asset_name
    write_zip(zip_path, [(zip_entry, staging_dir / zip_entry) for zip_entry, _, _ in OUTPUTS])

    source_infos = [
        {
            "table": table,
            "path": f"dicts/{table}.dict.yaml",
            "sha256": sha256_file(path),
            "size_bytes": path.stat().st_size,
        }
        for table, path in zip(TABLES, input_paths)
    ]
    info = build_info(args, repo_root, source, asset_name, zip_path, bin_infos, source_infos)
    (output_dir / "aegis-build-info.json").write_text(json.dumps(info, ensure_ascii=True, indent=2) + "\n")
    (output_dir / "aegis-dictionary-update.json").write_text(json.dumps(update_payload(info), ensure_ascii=True, indent=2) + "\n")

    print("\nArtifacts:")
    print(zip_path)
    print(output_dir / "aegis-build-info.json")
    print(output_dir / "aegis-dictionary-update.json")
    print("\nUpload these files to the same GitHub release as the APK; this script does not upload or tag.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
