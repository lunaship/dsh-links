# Security

DSH Links connects a phone to [DeepSeek Harness (dsh)](https://github.com/deepseek-ai) instances that can run tools and execute code on the host machine. Treat a paired device as a privileged remote console.

This public Beta supports **trusted LAN** as the documented product path. DSH Links Relay is in **private testing**: remote pairing has been exercised end-to-end, but enrollment requires a maintainer-issued invite code. Invite codes, Relay host credentials, and `state.json` must not appear in this repository, GitHub Releases, or the npm package.

If you use an intranet-tunnelling product yourself, treat it as an **experimental personal deployment**: it is not a supported Beta configuration and receives no compatibility or security guarantee. Do not expose port `18640` directly to the public Internet.

## Threat model (short)

- Pairing yields a long-lived device token (`x-dsh-link-token`). Anyone with an **active** token can call the mobile API on that host. Optional host confirmation keeps a newly paired token inert until you approve it on the「手机连接」panel.
- Port `18640` is an HTTPS reverse proxy with self-signed TLS. On LAN, the app **must pin the certificate fingerprint from the QR / pair-info payload before sending the pairing code**. A first connection that submits the code over an unpinned TLS session can be MITM'd on the same LAN.
- Private-network requests fail closed if the certificate fingerprint is missing or does not match. After a successful pair, the app should persist that pin (Keystore / prefs) for later requests.
- The loopback/same-origin fence on the desktop panel does not stop another process running as the same user from calling `127.0.0.1`. If the host is compromised, this plugin cannot save you; run dsh as a least-privilege user and keep `18640` off untrusted networks.
- dsh itself is powerful; this plugin does not sandbox the agent.

## Do

- Pair only on networks you trust; prefer QR / pairing code over sharing URLs widely.
- For untrusted LANs, enable「配对需本机确认」so a scanned code still needs a click on this computer. Turning the setting off does not activate devices already waiting for approval.
- Revoke lost or unused devices from the Web UI「手机连接」panel immediately; use「吊销全部设备」if a token may have leaked.
- Keep `18640` off the public Internet. The panel shows the listen address and reachable networks — treat a red warning as “this is not a trusted LAN”.
- Prefer short-lived pairing codes; do not paste tokens into chat logs or screenshots.
- After uninstalling the app, still revoke the device on the host.

## Do not

- Expose `0.0.0.0:18640` to untrusted networks.
- Treat Cloudflare Tunnel, Tailscale, frp, or invite-only Relay testing as a supported public Beta feature.
- Commit `local.properties`, keystores, `state.json`, invite codes, Relay host credentials, or any `*.token` / `*.pem` files.
- Paste invite codes into README, issues, screenshots, or pull requests.
- Screenshot or share the **cloud pairing QR**: it embeds the Relay route credential (`routeSecret`) and is as sensitive as an invite code. If it leaks, disconnect Relay on the「手机连接」panel to invalidate the credential.
- Rely on Host/Origin rewriting as authentication — auth is the device token.

## Android pairing (client checklist)

The plugin already puts `certFingerprint` in the QR / loopback `pair-info` payload. The official app must:

1. Compare that fingerprint with the live TLS peer **before** `POST /dsh-link/pair`.
2. Abort on mismatch — do not “continue with a warning”.
3. Persist the pin after a successful pair and reuse it for later requests.

This repository cannot enforce those steps. A pairing code sent over an unpinned first connection is the one LAN attack that does not need a stolen token.

On the host, enable「配对需本机确认」so an unexpected device still needs a click on this computer.

## Source and APK trust

- The public repository opens the `dsh-links` plugin and docs (MIT). The Android client is not published as source; install only signed APKs from this project's GitHub Releases.
- Do not trust third-party rebuilds or sideloaded APKs that claim to be DSH Links.

## Reporting

If you find a vulnerability in the plugin, docs, or the official APK, open a private report to the maintainer of [`lunaship/dsh-links`](https://github.com/lunaship/dsh-links) (or email the owner listed on the GitHub profile). Please include repro steps and impact; avoid filing public issues for exploitable auth or proxy bypasses until a fix is available.
