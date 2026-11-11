# Security policy

Codex Remote is an experimental, unofficial controller for the Codex desktop
application. It is not affiliated with or endorsed by OpenAI. It has not undergone
an independent security audit. Do not treat a successful build or test suite as a
security certification.

## Trust boundary

- Every paired phone is a trusted operator of that PC's Codex tasks. A paired
  device can read task output, submit instructions and answer supported approvals.
  There is no read-only account or per-project access control in this preview.
- Since 0.1.5, a trusted local network can be used without Tailscale. The bridge
  listens on active Ethernet/Wi-Fi RFC1918 IPv4 addresses and the Tailscale IPv4
  address. LAN listeners accept RFC1918 sources; Tailscale listeners accept the
  Tailscale IPv4 range. Private IP checks do not authenticate a LAN device or a
  Tailscale node. Use trusted networks and restrictive tailnet access policies.
- The bridge never binds wildcard, public, loopback or IPv6 addresses. Do not use
  Funnel, public reverse proxies, public port forwarding, or disable the Windows
  firewall. The app does not change firewall rules, network profiles or VPNs.
- Switching a paired PC's address requires the same pinned leaf certificate,
  existing device credential and host ID before saving. The connection is
  replaced for that PC; its pending operations, drafts and trust remain intact.
  A new IP address does not establish new trust or cause automatic resubmission.
  Both listeners use one operation journal and mutex, including network rebinds.
- Pairing requires a five-minute one-use credential and a PC confirmation. Android
  pins the certificate and protects local credentials with Keystore; Windows uses
  current-user DPAPI. Protect both devices and their signed-in OS accounts.
- Each PC owns its identity, credentials, history, operation journal and live
  connection. Credentials are not a substitute for protecting a compromised PC.
- Image uploads are bounded and bound to the device, thread and backend instance.
  Only the paired phone's staged attachment IDs can enter native image inputs.
  Viewing resolves opaque IDs against real conversation references, checks image
  bytes and dimensions, and never accepts a caller-supplied path or proxies an
  external URL. UNC/network shares and reparse paths are refused. Attachments are
  stored in the current user's application data, outside project directories;
  the index uses DPAPI, while image files use OS access protection, not encryption.
  Uploaded images may remain referenced by desktop history and are retained after
  dispatch, including uncertain outcomes. See docs/IMAGES.md for quotas/cleanup.
- Native desktop permissions, tools and sandbox settings remain in effect. The
  mobile bridge does not make an otherwise unsafe Codex instruction safe.
- The native IPC adapter is an unstable, version-dependent interface. Since
  0.1.4, version strings and archive hashes are diagnostic, not an allowlist.
  A runtime handshake, the advertised native tool argument schemas, and live
  conversation identity/state are checked instead. Unsupported operations are
  rejected individually; incompatible handshake or backend identity still
  prevents connection. No alternate backend or automatic protocol downgrade is
  used. Passing these checks is not a full compatibility or security certification.
- A native router's explicit pre-dispatch version/handler rejection is recorded
  as rejected. Timeouts, disconnects and ambiguous native errors remain uncertain
  and must not trigger automatic resubmission. Permissions, approvals, certificate
  pinning, pairing and host/instance/turn checks are not relaxed by this policy.

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
