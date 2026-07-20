#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only

import json
import os
import re
import time
import urllib.request
from http.client import IncompleteRead
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit

RETRY_ATTEMPTS = 6
RETRY_BASE_DELAY_SECONDS = 2.0
RETRY_WINDOW_SECONDS = 120.0
RETRYABLE_STATUS = frozenset({404, 408, 425, 429, 500, 502, 503, 504})

DICT_LATEST_TAG = "dict-latest"
MANIFEST_URL = (
    f"https://github.com/lurixo/Aegis/releases/download/{DICT_LATEST_TAG}/aegis-dictionary-update.json"
)
GRAMMAR_TAG = "LTS"
GRAMMAR_NAME = "wanxiang-lts-zh-hans.gram"
GRAMMAR_RELEASE_API = (
    f"https://api.github.com/repos/amzxyz/RIME-LMDG/releases/tags/{GRAMMAR_TAG}"
)
GRAMMAR_URL = (
    f"https://github.com/amzxyz/RIME-LMDG/releases/download/{GRAMMAR_TAG}/{GRAMMAR_NAME}"
)
PACK_NAME = f"aegis_dict_pack_{DICT_LATEST_TAG}.zip"
PACK_URL = f"https://github.com/lurixo/Aegis/releases/download/{DICT_LATEST_TAG}/{PACK_NAME}"


def normalize_sha256(value):
    if not isinstance(value, str):
        return None
    raw = value.strip().lower()
    if raw.startswith("sha256:"):
        raw = raw[len("sha256:"):]
    return raw if re.fullmatch(r"[0-9a-f]{64}", raw) else None

def retryable(error):
    if isinstance(error, HTTPError):
        return error.code in RETRYABLE_STATUS
    return isinstance(error, (URLError, TimeoutError, OSError, IncompleteRead))


def with_retry(
    description,
    attempt,
    attempts=RETRY_ATTEMPTS,
    base_delay=RETRY_BASE_DELAY_SECONDS,
    window=RETRY_WINDOW_SECONDS,
    sleep=None,
    clock=None,
):
    sleep = sleep or time.sleep
    clock = clock or time.monotonic
    deadline = clock() + window
    last = None
    for index in range(attempts):
        remaining = deadline - clock()
        if remaining <= 0:
            break
        try:
            return attempt(remaining)
        except (HTTPError, URLError, TimeoutError, OSError, IncompleteRead) as error:
            if isinstance(error, HTTPError):
                error.close()
            if not retryable(error):
                raise SystemExit(f"{description} failed: {error}") from error
            last = error
            if index == attempts - 1:
                break
            remaining = deadline - clock()
            if remaining <= 0:
                break
            sleep(min(base_delay * (2 ** index), remaining))
    reason = last or "retry count or window exhausted before the first attempt"
    raise SystemExit(f"{description} failed after bounded retries: {reason}")


def open_once(url, timeout):
    headers = {"User-Agent": "Aegis-test-dict-fetch"}
    token = os.environ.get("GITHUB_TOKEN")
    parsed = urlsplit(url)
    request = urllib.request.Request(url, headers=headers)
    if token and parsed.scheme == "https" and parsed.hostname == "api.github.com":
        request.add_unredirected_header("Authorization", f"Bearer {token}")
    return urllib.request.urlopen(request, timeout=timeout)


def http_get(url, timeout, **retry):
    def attempt(remaining):
        with open_once(url, min(timeout, remaining)) as response:
            return response.read()

    return with_retry(f"GET {url}", attempt, **retry)


def resolve_asset(manifest_url, **retry):
    manifest = json.loads(http_get(manifest_url, 60, **retry).decode("utf-8"))
    if manifest.get("schema_version") != 1 or manifest.get("kind") != "dictionary_update":
        raise SystemExit(f"unexpected dictionary manifest at {manifest_url}")
    asset = manifest["asset"]
    name = asset["name"]
    url = asset["url"]
    sha256 = normalize_sha256(asset["sha256"])
    if name != PACK_NAME or url != PACK_URL or sha256 is None:
        raise SystemExit("dictionary manifest does not describe the expected dict-latest pack")
    return url, sha256, name


def resolve_grammar_asset(release_api, **retry):
    release = json.loads(http_get(release_api, 60, **retry).decode("utf-8"))
    if release.get("tag_name") != GRAMMAR_TAG:
        raise SystemExit(f"unexpected grammar release at {release_api}")
    assets = [asset for asset in release.get("assets", []) if asset.get("name") == GRAMMAR_NAME]
    if len(assets) != 1:
        raise SystemExit(f"grammar release must carry exactly one {GRAMMAR_NAME}")
    asset = assets[0]
    sha256 = normalize_sha256(asset.get("digest"))
    size = asset.get("size")
    if asset.get("browser_download_url") != GRAMMAR_URL or sha256 is None:
        raise SystemExit("grammar release does not describe the expected LTS asset")
    if not isinstance(size, int) or size <= 1024:
        raise SystemExit("grammar release carries an invalid asset size")
    return GRAMMAR_URL, sha256, GRAMMAR_NAME, size
