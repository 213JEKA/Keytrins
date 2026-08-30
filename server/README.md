# Live Research Server

Server migration target for the current Android Live Research Bybit trading runtime.

**Do not implement from memory.** Start with `../codex-handoff/00_START_HERE.md` and follow the parity contract.

Initial state of this directory is intentionally minimal. Codex Phase 1 should scaffold an OBSERVE-only server here, port the Android strategy exactly, add PostgreSQL migrations, parity tests, health/readiness, Docker deployment and a persisted scan runtime.

LIVE order execution is intentionally deferred until parity is demonstrated and accepted.