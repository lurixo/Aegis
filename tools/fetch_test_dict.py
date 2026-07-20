#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only

import os
import time
import urllib.request
from http.client import IncompleteRead
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit

RETRY_ATTEMPTS = 6
RETRY_BASE_DELAY_SECONDS = 2.0
RETRY_WINDOW_SECONDS = 120.0
RETRYABLE_STATUS = frozenset({404, 408, 425, 429, 500, 502, 503, 504})

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
