# Contributing

Thanks for helping improve Codex Remote / Codex 随行.

1. Read `README.md`, `SECURITY.md` and `docs/PROTOCOL.md` before changing the
   desktop adapter or control protocol.
2. Use a separate development clone, an unpaired test profile, and disposable
   test tasks. Do not send, interrupt, approve or fork a real user's conversation
   as an automated smoke test.
3. Keep host identity, backend instance, thread and turn checks intact. New
   mutations must use the persistent operation journal. Never automatically retry
   an uncertain operation or route it to another PC.
4. Do not add generic RPC, shell, direct file-write endpoints, public networking,
   automatic approvals, credential export or version-gate bypasses.
5. Add regression tests, run `node tools/audit-source.mjs`, Android JVM tests and
   Windows self-tests. Follow `docs/ACCEPTANCE.md` for device validation; simulation
   is not a substitute for two physical PCs.
6. Preserve the established Chinese UI and compact conversation presentation.
   Document incomplete tests rather than presenting them as successful.

Build instructions are in `docs/BUILDING.md`. Release signing keys and pairing
credentials never belong in Git, Actions logs, issues, artifacts or pull requests.
Use your own development signing key; do not request the maintainer's private key.

By submitting a contribution, you agree that your contribution is made available
under this repository's MIT license. Third-party license notices must be retained.
