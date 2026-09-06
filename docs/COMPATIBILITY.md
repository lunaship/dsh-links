# DSH Links compatibility matrix

This is the single compatibility reference for the public `dsh-links`
repository (plugin plus Relay under `relay/`) and the private
`dsh-links-app`. Update this file when a source baseline, tag, release,
or verified combination changes.

## Current source baseline

These values describe the source snapshots used for the current Beta work.
They do not imply that a package, APK, or Relay deployment has been
published.

| Component | Source baseline | Published / released status | Verified compatibility status |
|---|---|---|---|
| DSH | `0.1.2-alpha.5` | Upstream dependency; npm dist-tag `alpha` as of 2026-09-02. `latest` remains `0.1.1-rc.2` | LAN smoke verification below; full phone end-to-end remains in the closed-beta scope |
| Plugin `dsh-links` | package `0.1.0-beta.14`; GitHub tag `v0.1.0-beta.14` | npm dist-tag `beta` is `0.1.0-beta.14` as of 2026-09-07 (`npm view` in the release environment). `latest` remains `0.1.0-beta.1` | Unit tests in the release workflow; LAN phone end-to-end not rerun in this round |
| Android `dsh-links-app` | `versionName 0.5.0-beta.16`; GitHub tag `v0.5.0-beta.16` | Official signed APK on the private App GitHub Release; SHA-256 `83a84b30236e586621721fb343ed7ef1800e902b4347f190242f9dd49c16556e` | Unit/lint/debug assemble plus this signed Release build; phone end-to-end remains in the closed-beta scope |
| Relay (`relay/` in this repo) | Same GitHub tag as the plugin (`v0.1.0-beta.14`) | No separate Relay package or public deployment is asserted here | DLR/1 remains invite-only test scope; source is public in this repository |

## Verified combination and scope

| DSH | Plugin | Android App | Relay | Verified path |
|---|---|---|---|---|
| `0.1.2-alpha.5` | `0.1.0-beta.14` | `0.5.0-beta.16` | `v0.1.0-beta.14` `relay/` | 2026-09-07 published source: plugin npm `beta` + GitHub Release, App signed APK `v0.5.0-beta.16`. Unit gates for catchup integrity, multi-question validation, approval grace/idempotency, history terminal state, and unidirectional Bridge idle. Trusted LAN smoke and Android→Relay→Plugin were **not** rerun. Previous LAN smoke remains the last phone-path evidence and used App `0.5.0-beta.14` with plugin `0.1.0-beta.13`. |
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
