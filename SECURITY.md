# Security policy

Codex Remote is an experimental, unofficial controller for the Codex desktop
application. It is not affiliated with or endorsed by OpenAI. It has not undergone
an independent security audit. Do not treat a successful build or test suite as a
security certification.

## Trust boundary

- Every paired phone is a trusted operator of that PC's Codex tasks. A paired
  device can read task output, submit instructions and answer supported approvals.
  There is no read-only account or per-project access control in this preview.
- Use your own trusted Tailscale devices and restrictive tailnet access policies.
  The bridge accepts the Tailscale IPv4 range, but this is not an additional
  cryptographic verification of the source Tailscale node identity.
- The bridge binds only to its Tailscale address. Do not use Funnel, public
  reverse proxies, public port forwarding, or disable the Windows firewall.
- Pairing requires a five-minute one-use credential and a PC confirmation. Android
  pins the certificate and protects local credentials with Keystore; Windows uses
  current-user DPAPI. Protect both devices and their signed-in OS accounts.
- Each PC owns its identity, credentials, history, operation journal and live
  connection. Credentials are not a substitute for protecting a compromised PC.
- Native desktop permissions, tools and sandbox settings remain in effect. The
  mobile bridge does not make an otherwise unsafe Codex instruction safe.
- The native IPC adapter is version-specific. An unknown desktop version or
  archive hash must fail closed, not silently bypass the compatibility check.

## Lost phone or suspected disclosure

1. Revoke that phone in each affected PC's bridge window. Removing a PC from the
   phone alone is not server-side revocation.
2. Revoke the lost device in Tailscale and review your tailnet access policies.
3. Inspect task history and pending approvals on the PC. Never resolve uncertainty
   by blindly resending an instruction or approving an old request.

## Reporting a vulnerability

Use the repository's **Security > Report a vulnerability** private reporting flow
when available. Do not publish pairing QR codes, device tokens, DPAPI files,
private signing keys, complete private chats, or unredacted logs in an issue.
If private reporting is unavailable, open a minimal issue asking for a private
contact channel without exploit details or credentials.

Only the latest published preview is maintained. A security report involving a
different Codex desktop version needs fresh compatibility validation. There is no
guaranteed response-time SLA.
