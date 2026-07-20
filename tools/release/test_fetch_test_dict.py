#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only
#

import io
import sys
import unittest
import urllib.error
from http.client import HTTPResponse, IncompleteRead
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import fetch_test_dict as ftd


def http_error(code):
    return urllib.error.HTTPError("https://example.invalid/asset", code, "stub", {}, io.BytesIO(b""))


class RecordingClock:
    def __init__(self, step=0.0):
        self.now = 0.0
        self.step = step
        self.slept = []

    def sleep(self, seconds):
        self.slept.append(seconds)
        self.now += seconds

    def monotonic(self):
        self.now += self.step
        return self.now

class BoundedRetryTest(unittest.TestCase):
    URL = "https://example.invalid/asset"

    def test_a_transient_404_is_retried_until_it_succeeds(self):
        clock = RecordingClock()
        responses = [http_error(404), http_error(404), io.BytesIO(b"payload")]

        def urlopen(request, timeout=None):
            outcome = responses.pop(0)
            if isinstance(outcome, Exception):
                raise outcome
            return outcome

        with mock.patch.object(ftd.urllib.request, "urlopen", side_effect=urlopen):
            self.assertEqual(
                b"payload", ftd.http_get(self.URL, 11, sleep=clock.sleep, clock=clock.monotonic)
            )
        self.assertEqual([], responses)
        self.assertEqual([2.0, 4.0], clock.slept)

    def test_every_retryable_status_and_transport_error_is_retried(self):
        for failure in [
            http_error(404),
            http_error(408),
            http_error(425),
            http_error(429),
            http_error(500),
            http_error(502),
            http_error(503),
            http_error(504),
            urllib.error.URLError("connection reset"),
            TimeoutError("read timed out"),
            ConnectionResetError("connection reset"),
            IncompleteRead(b"partial", 12),
        ]:
            with self.subTest(failure=type(failure).__name__ + str(getattr(failure, "code", ""))):
                clock = RecordingClock()
                responses = [failure, io.BytesIO(b"ok")]

                def urlopen(request, timeout=None):
                    outcome = responses.pop(0)
                    if isinstance(outcome, Exception):
                        raise outcome
                    return outcome

                with mock.patch.object(ftd.urllib.request, "urlopen", side_effect=urlopen):
                    self.assertEqual(
                        b"ok", ftd.http_get(self.URL, 11, sleep=clock.sleep, clock=clock.monotonic)
                    )
                self.assertEqual([2.0], clock.slept)

    def test_a_non_retryable_status_fails_on_the_first_attempt(self):
        clock = RecordingClock()
        with mock.patch.object(
            ftd.urllib.request, "urlopen", side_effect=http_error(403)
        ) as urlopen:
            with self.assertRaisesRegex(SystemExit, "failed: HTTP Error 403"):
                ftd.http_get(self.URL, 11, sleep=clock.sleep, clock=clock.monotonic)
        self.assertEqual(1, urlopen.call_count)
        self.assertEqual([], clock.slept)

    def test_persistent_failure_still_exits_non_zero_after_a_bounded_number_of_attempts(self):
        clock = RecordingClock()
        with mock.patch.object(
            ftd.urllib.request, "urlopen", side_effect=http_error(404)
        ) as urlopen:
            with self.assertRaisesRegex(SystemExit, "failed after bounded retries"):
                ftd.http_get(self.URL, 11, sleep=clock.sleep, clock=clock.monotonic)
        self.assertEqual(ftd.RETRY_ATTEMPTS, urlopen.call_count)
        self.assertEqual(ftd.RETRY_ATTEMPTS - 1, len(clock.slept))
        self.assertLessEqual(sum(clock.slept), ftd.RETRY_WINDOW_SECONDS)

    def test_no_request_starts_when_backoff_reaches_the_deadline(self):
        clock = RecordingClock()
        with mock.patch.object(ftd.urllib.request, "urlopen", side_effect=http_error(503)) as urlopen:
            with self.assertRaisesRegex(SystemExit, "failed after bounded retries"):
                ftd.http_get(
                    self.URL,
                    30,
                    attempts=40,
                    base_delay=8.0,
                    window=20.0,
                    sleep=clock.sleep,
                    clock=clock.monotonic,
                )
        self.assertEqual([8.0, 12.0], clock.slept)
        self.assertEqual(2, urlopen.call_count)
        self.assertEqual([20.0, 12.0], [call.kwargs["timeout"] for call in urlopen.call_args_list])

    def test_an_expired_window_prevents_the_first_request(self):
        clock = RecordingClock(step=30.0)
        with mock.patch.object(ftd.urllib.request, "urlopen") as urlopen:
            with self.assertRaisesRegex(SystemExit, "failed after bounded retries"):
                ftd.http_get(
                    self.URL,
                    11,
                    attempts=9,
                    base_delay=1.0,
                    window=20.0,
                    sleep=clock.sleep,
                    clock=clock.monotonic,
                )
        self.assertEqual([], clock.slept)
        self.assertEqual(0, urlopen.call_count)

    def test_a_failed_attempt_that_spends_the_window_does_not_retry(self):
        clock = RecordingClock()

        def urlopen(request, timeout=None):
            clock.now += 21.0
            raise TimeoutError("read timed out")

        with mock.patch.object(ftd.urllib.request, "urlopen", side_effect=urlopen) as opened:
            with self.assertRaisesRegex(SystemExit, "failed after bounded retries"):
                ftd.http_get(self.URL, 60, window=20, sleep=clock.sleep, clock=clock.monotonic)
        self.assertEqual(1, opened.call_count)
        self.assertEqual(20, opened.call_args.kwargs["timeout"])
        self.assertEqual([], clock.slept)

    def test_each_attempt_caps_socket_timeout_by_remaining_budget(self):
        clock = RecordingClock()
        responses = [http_error(503), io.BytesIO(b"ok")]

        def urlopen(request, timeout=None):
            clock.now += 5.0
            outcome = responses.pop(0)
            if isinstance(outcome, Exception):
                raise outcome
            return outcome

        with mock.patch.object(ftd.urllib.request, "urlopen", side_effect=urlopen) as opened:
            self.assertEqual(
                b"ok",
                ftd.http_get(self.URL, 15, window=20, sleep=clock.sleep, clock=clock.monotonic),
            )
        self.assertEqual([15, 13], [call.kwargs["timeout"] for call in opened.call_args_list])

    def test_a_transfer_already_running_can_finish_after_the_retry_start_window(self):
        clock = RecordingClock()

        def urlopen(request, timeout=None):
            clock.now += 21.0
            return io.BytesIO(b"ok")

        with mock.patch.object(ftd.urllib.request, "urlopen", side_effect=urlopen) as opened:
            self.assertEqual(
                b"ok",
                ftd.http_get(self.URL, 60, window=20, sleep=clock.sleep, clock=clock.monotonic),
            )
        self.assertEqual(1, opened.call_count)
        self.assertEqual(20, opened.call_args.kwargs["timeout"])

if __name__ == "__main__":
    unittest.main(verbosity=2)
