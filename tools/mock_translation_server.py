#!/usr/bin/env python3
"""
Phase 11 test double. Supports THREE response shapes via ?shape=:
  shape=openai (default) - OpenAI-compatible chat completions
  shape=deepl             - DeepL /v2/translate shape
  shape=google_v2         - Google Cloud Translation v2 shape

And these failure modes via ?mode= (shape-appropriate status codes/bodies chosen automatically):
  mode=success | rate_limit | quota_exceeded | auth_fail | server_error | slow | flaky

Usage examples:
  http://127.0.0.1:8765/v1/chat/completions?shape=openai&mode=success
  http://127.0.0.1:8765/v2/translate?shape=deepl&mode=success
  http://127.0.0.1:8765/v2/translate?shape=deepl&mode=quota_exceeded   (-> HTTP 456, DeepL-specific)
  http://127.0.0.1:8765/language/translate/v2?shape=google_v2&mode=success
  http://127.0.0.1:8765/language/translate/v2?shape=google_v2&mode=rate_limit

NOTE (honesty about what this does and doesn't prove): this mocks the DOCUMENTED
response shape of DeepL / Google Cloud Translation v2 (verified 2026-08 against
developers.deepl.com and docs.cloud.google.com - CLAUDE.md §3), so it proves the
provider's PARSING and ERROR-CLASSIFICATION logic is correct for a conforming
response. It does NOT prove the real DeepL/Google endpoints behave identically in
practice (auth handshake details, undocumented edge cases, etc.) - that still needs
a real API key, which is not available. DEVELOPMENT_STATUS.md records this as a
know limitation.
"""
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

REQUEST_LOG = []

class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def do_POST(self):
        parsed = urlparse(self.path)
        params = parse_qs(parsed.query)
        mode = params.get("mode", ["success"])[0]
        shape = params.get("shape", ["openai"])[0]

        length = int(self.headers.get("Content-Length", 0))
        raw_body = self.rfile.read(length) if length else b"{}"
        try:
            body = json.loads(raw_body.decode("utf-8"))
        except json.JSONDecodeError:
            body = {}

        REQUEST_LOG.append({"time": time.strftime("%H:%M:%S"), "shape": shape, "mode": mode})
        print(f"[{time.strftime('%H:%M:%S')}] shape={shape} mode={mode} request #{len(REQUEST_LOG)}")

        if mode == "slow":
            delay = float(params.get("delay", ["15"])[0])
            time.sleep(delay)
            self._send_success(shape, body)
            return

        if mode == "flaky":
            fail_count = int(params.get("fail_count", ["2"])[0])
            if len(REQUEST_LOG) <= fail_count:
                self._send_error(shape, "server_error", params)
            else:
                self._send_success(shape, body)
            return

        # Phase 13 addition: verifies ApiState.CONFIG_ERROR_DISABLE_THRESHOLD (a
        # burst of HTTP 400 from a local LLM server under load should NOT
        # immediately DISABLED_PERMANENT the API - see ApiState.java's Javadoc).
        # config_error_flaky: returns 400 for fail_count requests, then succeeds -
        # simulates transient overload recovering before the threshold is hit.
        if mode == "config_error_flaky":
            fail_count = int(params.get("fail_count", ["2"])[0])
            if len(REQUEST_LOG) <= fail_count:
                self._send_error(shape, "config_error", params)
            else:
                self._send_success(shape, body)
            return

        if mode == "success":
            self._send_success(shape, body)
        else:
            self._send_error(shape, mode, params)

    def _send_success(self, shape, body):
        if shape == "openai":
            user_text = ""
            for message in body.get("messages", []):
                if message.get("role") == "user":
                    user_text = message.get("content", "")
            payload = {"choices": [{"message": {"role": "assistant", "content": f"[MOCK] {user_text.upper()}"}}]}
        elif shape == "deepl":
            texts = body.get("text", [""])
            payload = {"translations": [{"detected_source_language": "EN", "text": f"[MOCK-DEEPL] {t.upper()}"} for t in texts]}
        elif shape == "google_v2":
            texts = body.get("q", [""])
            payload = {"data": {"translations": [{"translatedText": f"[MOCK-GOOGLE-V2] {t.upper()}"} for t in texts]}}
        else:
            payload = {"error": "unknown shape"}
        self._write(200, payload)

    def _send_error(self, shape, mode, params):
        retry_after = params.get("retry_after", [None])[0]

        if shape == "deepl":
            # DeepL's DOCUMENTED status codes (developers.deepl.com/docs/best-practices/error-handling,
            # support.deepl.com "DeepL API error messages"): 403 auth, 429 rate limit,
            # 456 quota (DeepL-SPECIFIC, not a generic HTTP code), 5xx internal.
            status_map = {"auth_fail": 403, "rate_limit": 429, "quota_exceeded": 456, "server_error": 500}
            status = status_map.get(mode, 500)
            payload = {"message": f"mock deepl error: {mode}"}
        elif shape == "google_v2":
            # Google Cloud's documented error envelope: {"error": {"code", "message", "status"}}.
            # Google does NOT cleanly distinguish quota vs rate-limit (both surface as 429
            # RESOURCE_EXHAUSTED per docs.cloud.google.com/docs/quotas/troubleshoot) - so
            # quota_exceeded is simulated as the same 429 a real rate limit would give.
            status_map = {"auth_fail": 403, "rate_limit": 429, "quota_exceeded": 429, "server_error": 500}
            status = status_map.get(mode, 500)
            rpc_status = {"auth_fail": "PERMISSION_DENIED", "rate_limit": "RESOURCE_EXHAUSTED",
                          "quota_exceeded": "RESOURCE_EXHAUSTED", "server_error": "INTERNAL"}.get(mode, "UNKNOWN")
            payload = {"error": {"code": status, "message": f"mock google error: {mode}", "status": rpc_status}}
        else:  # openai
            status_map = {"auth_fail": 401, "rate_limit": 429, "quota_exceeded": 429,
                           "server_error": 500, "config_error": 400}
            status = status_map.get(mode, 500)
            error_code = "insufficient_quota" if mode == "quota_exceeded" else mode
            payload = {"error": {"message": error_code, "type": error_code, "code": error_code}}

        self._write(status, payload, retry_after)

    def _write(self, status, payload, retry_after=None):
        data = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        if retry_after:
            self.send_header("Retry-After", str(retry_after))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    port = 8765
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"Mock translation server listening on http://127.0.0.1:{port}")
    print("Examples:")
    print(f"  http://127.0.0.1:{port}/v1/chat/completions?shape=openai&mode=success")
    print(f"  http://127.0.0.1:{port}/v2/translate?shape=deepl&mode=success")
    print(f"  http://127.0.0.1:{port}/v2/translate?shape=deepl&mode=quota_exceeded")
    print(f"  http://127.0.0.1:{port}/language/translate/v2?shape=google_v2&mode=success")
    print(f"  http://127.0.0.1:{port}/language/translate/v2?shape=google_v2&mode=rate_limit")
    server.serve_forever()
