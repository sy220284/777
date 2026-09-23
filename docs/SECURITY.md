# Security

777 runs a local coding agent and can also control a remote Harness through an HTTPS relay.
Agent tools can read and modify workspace files and run processes. Approving a process grants the command access available to the app or remote host.

## Remote connections

- Remote control supports relay pairing only. LAN scanning, mDNS discovery and direct Harness connections have been removed.
- Pairing payloads, typed addresses and authenticated relay transports require HTTPS. Old plaintext endpoints require new secure pairing; credentials are not sent to them.
- A scanned pairing code can supply the relay certificate fingerprint. Typed pairing uses trust on first use, so verify the address and prefer a trusted QR code.
- Paired relay credentials are encrypted using Android Keystore. A changed pinned certificate requires renewed trust; credentials are not silently sent to a replacement key.
- HTTPS-to-HTTP redirect downgrade is disabled on authenticated relay clients. Relay pairing does not follow credential-bearing redirects.
- Revocation is managed on the relay. Pairing authenticates the device; relay and Harness policies determine the operations available to it.

## Local execution and automation

- Built-in tools use the same registry permission checks as plugin tools. Read-only subagents cannot invoke state-changing tools. Unknown tools fail closed.
- Safe automatic approval is a persisted local preference. It covers only path-confined workspace writes and read-only tools, including read-only inspection outside the workspace. Process, state-changing network, device and privileged operations keep explicit approval boundaries and are surfaced with an impact level.
- Background runs stop at interactive approval or question boundaries. External cancellation cancels and waits for the owned agent run.
- Webhook only listens on `127.0.0.1`, uses a bearer token and limits request sizes. Legacy LAN preferences cannot enable an external plaintext listener.
- Language intelligence automatically resolves an already-available language server from the project/file type or preserves a legacy explicit command for upgrade compatibility. A new external server process still requires process approval before first launch; no server is downloaded or installed automatically. Server file access stays within the workspace, queries have a response deadline, and notification storage is bounded.

## Local storage and network requests

- Local conversations, workspace files, user rules and scoped memories are stored in app-private storage. Platform backup and device extraction are disabled.
- Memory files keep a recovery backup and preserve corrupt primary files for diagnosis. Sensitive-data filtering reduces accidental credential retention; it is not a complete secret detector.
- Relay and model credentials are encrypted at rest. Model requests and web tools contact configured services or requested websites. Generic web fetching can use HTTP; this does not permit plaintext remote-control pairing.
- Updates are requested explicitly from Settings. Downloads are checked for checksum, package identity and signing certificate before opening the Android installer.
- Remote attachments are uploaded to the paired host. Local attachments are copied into the local workspace.

## Reporting

Use the repository Security tab to report a suspected vulnerability privately. Include the app version, reproduction steps and affected execution mode.
