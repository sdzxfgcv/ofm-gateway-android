# OFM Gateway (Android)

Minimal OpenAI-compatible local gateway for the OpenCode public free tier.

- Pure Java, **zero third-party dependencies** (`org.json` + `HttpURLConnection` from the Android platform).
- No opencode binary, no Node.js runtime, no Termux, no root.
- Reproduces the OpenCode v2 / opencode2api v1.4.x client wire shape: UA, session headers, 13 official tool definitions, official system prompt, slow-clock session id.
- Foreground service, loopback-only HTTP server on `127.0.0.1:18900`, protected by a random API key.

Build with GitHub Actions (workflow_dispatch) or locally with Gradle + Android SDK 34.
