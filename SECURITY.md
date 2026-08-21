# Security

DSH Links connects a phone to [DeepSeek Harness (dsh)](https://github.com/deepseek-ai) instances that can run tools and execute code on the host machine. Treat a paired device as a privileged remote console.

## Threat model (short)

- Pairing yields a long-lived device token (`x-dsh-link-token`). Anyone with the token can call the mobile API on that host.
- Port `18640` is an HTTPS reverse proxy with self-signed TLS. On LAN, the app pins the certificate fingerprint from pairing.
- dsh itself is powerful; this plugin does not sandbox the agent.

## Do

- Pair only on networks you trust; prefer QR / pairing code over sharing URLs widely.
- Revoke lost or unused devices from the Web UI「手机连接」panel.
- Keep `18640` off the public Internet unless you put Cloudflare Tunnel **and** Access (or equivalent VPN) in front of it. The example in [`remote/cloudflared.yml.example`](remote/cloudflared.yml.example) is fail-closed: Access JWT validation is required. See [`remote/README.md`](remote/README.md).
- Prefer short-lived pairing codes; do not paste tokens into chat logs or screenshots.

## Do not

- Expose `0.0.0.0:18640` to untrusted networks without Access / VPN / equivalent. Do not copy the tunnel example with Access placeholders left unreplaced.
- Commit `local.properties`, keystores, `state.json`, or any `*.token` / `*.pem` files.
- Rely on Host/Origin rewriting as authentication — auth is the device token.

## Reporting

If you find a vulnerability in this repository, open a private report to the maintainer of [`lunaship/dsh-links`](https://github.com/lunaship/dsh-links) (or email the owner listed on the GitHub profile). Please include repro steps and impact; avoid filing public issues for exploitable auth or proxy bypasses until a fix is available.
