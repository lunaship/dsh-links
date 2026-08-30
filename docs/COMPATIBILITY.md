# DSH Links compatibility matrix

This is the single compatibility reference for `dsh-links`,
`dsh-links-app`, and `dsh-links-relay`. The companion repositories link here
instead of maintaining their own version tables. Update this file when a
source baseline, tag, release, or verified combination changes.

## Current source baseline

These values describe the source snapshots used for the current Beta work.
They do not imply that a package, APK, or Relay deployment has been
published.

| Component | Source baseline | Published / released status | Verified compatibility status |
|---|---|---|---|
| DSH | `0.1.1-rc.2` | Upstream dependency; release status is not managed by this project | LAN smoke verification below; full phone end-to-end remains in the RC closed-beta scope |
| Plugin `dsh-links` | package `0.1.0-beta.12`; source tag `v0.1.0-beta.12` | npm registry status is not asserted here; verify it with `npm view` in the release environment | LAN combination below is the current documented verification |
| Android `dsh-links-app` | `versionName 0.5.0-beta.14`; source tag `v0.5.0-beta.14` | APK release status is not asserted here; install only an official signed release whose version and SHA-256 are published with it | LAN combination below is the current documented verification |
| Relay `dsh-links-relay` | Current `main` contains unpublished changes; no release tag | No public release or deployment status is asserted here | DLR/1 implementation is private, invite-only test scope only |

## Verified combination and scope

| DSH | Plugin | Android App | Relay | Verified path |
|---|---|---|---|---|
| `0.1.1-rc.2` | `0.1.0-beta.12` | `0.5.0-beta.14` | not required | Trusted LAN smoke, 2026-08-30: plugin load, `/dsh-link/*` routes, `session.list` / `session.history` / `llm.models` / `workspace.list` / `settings.describe` RPCs, `events.mux` WebSocket frames, and the settings panel slot all verified against `@deepseek-ai/dsh@0.1.1-rc.2`. Phone end-to-end (pairing, SSE push, approval) not yet rerun on this DSH version. |
| `0.1.0-rc.8` | `0.1.0-beta.9` | `0.5.0-beta.14` | not required | Trusted LAN; the previously documented Beta combination |

The row above is a LAN compatibility statement. It is not a claim that the
same source snapshots have passed a public production deployment, a public
CA/TLS check, capacity testing, or a real Android → Relay → plugin end-to-end
run. Relay use remains a separate private test path until those checks are
recorded explicitly.

## Support boundary

- **Public supported:** Android and DSH on the same trusted LAN.
- **Private testing:** DSH Links Relay, invite-only; access codes are issued
  only by the maintainer and are never stored in source, releases, or npm
  packages.
- **Experimental:** a Tailscale or Cloudflare Tunnel path operated by the
  user. It is not a supported Beta path and carries no project compatibility
  promise.
- **Not supported:** direct public exposure of plugin port `18640`, frp, or
  public self-service Relay enrollment.

## Release-status rule

Repository tags, package registry metadata, APK releases, and running Relay
deployments are separate facts. A tag or a passing local test must not be
described as a published release or a production deployment. Confirm each
external fact in the relevant release or deployment environment before
changing the status above. For every verification or release record, lock the
exact checked-out revision of each repository in that evidence; this matrix
does not hard-code moving `main` revisions or ahead counts.
