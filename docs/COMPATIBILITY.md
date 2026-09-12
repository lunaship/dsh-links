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
| DSH | `0.1.5-rc.1` | Upstream dependency; npm dist-tag `latest` as of 2026-09-12 (`next` is `0.1.5-rc.2`). Host smoke below; full phone end-to-end remains in the closed-beta scope |
| Plugin `dsh-links` | package `0.1.0-beta.15`; GitHub tag `v0.1.0-beta.15`; working tree follows DSH `0.1.5-rc.1` (V3 session log path; omit null session fields; produced files + workspace file GET; terminal approvals/questions session-bound; mobile archive-set contract `archivedSessionIds` on bootstrap/sessions/search; `@deepseek-ai/schemastery` 3.18.2) | npm dist-tag `beta` is `0.1.0-beta.15` as of 2026-09-12 (`npm view` verified; GitHub Release triggered publish-npm Trusted Publishing, green). `latest` remains `0.1.0-beta.1` | Unit tests 159 green locally 2026-09-12; 2026-09-12 host smoke on 0.1.5-rc.1 (below); 2026-09-12 real-device LAN e2e (below) |
| Android `dsh-links-app` | `versionName 0.5.0-beta.17`; GitHub tag `v0.5.0-beta.17`; working tree adds mobile archive-set consumption (bootstrap/sessions parse with availability flag; cold start applies the archive set before selection; restore-override reconciliation), subagent/stale-blank auto-selection filter, bounded share-image intake + export pruning, and a `.debug` applicationIdSuffix for device tests | Official signed APK on the private App GitHub Release `v0.5.0-beta.17`; SHA-256 `c5ac1d3730a0fa8926e0f1cb3b0b2df8a7ef8af8c4ee979c61c68007141af2e5` | Unit tests green 2026-09-12; instrumented tests OK (5: MathRenderer + AccessibilitySemantics) on a physical Xiaomi 15 2026-09-12; phone end-to-end with this build: sessions/history render, host-restart auto-reconnect (below) |
| Relay (`relay/` in this repo) | Same GitHub tag as the plugin (`v0.1.0-beta.15`) | No separate Relay package or public deployment is asserted here | DLR/1 remains invite-only test scope; source is public in this repository |

## Verified combination and scope

| DSH | Plugin | Android App | Relay | Verified path |
|---|---|---|---|---|
| `0.1.5-rc.1` | `0.1.0-beta.15` (schemastery 3.18.2, session-bound terminals, archive-set contract) | `0.5.0-beta.17` | `v0.1.0-beta.15` `relay/` | 2026-09-12: host smoke on a scratch profile. Note: the smoke ran with the plugin's default global state dir, which shared pairing state with the operator's real profile and revoked two real phone pairings during teardown (see warning below; recovered by re-pairing) and reset the workspace registry (recovered by resetting `storages/workspace.json` to `initialized: false` so the host re-bootstraps from session history). Verified: plugin load + port bind, loopback `pair-info`/`qr.png`, LAN pairing with one-time code + requestId replay + host approval, `bootstrap`/`sessions`/`models`/`llm-models`/`workspaces`/`settings`/`agent-presets`/`devices` 200, session history tail + `maxMessages` paging + `nextBeforeSeq` on V3 `session.v3.jsonl.zstd` logs, prompt accepted with model reply, workspace register (absolute path, existing dir) + list, session file GET with 403 on workspace-escape, SSE `ready` frame with protocol caps, client bundle serves the `settings.section` panel. `session.list` items may omit `projections` for cold sessions (projection cache miss), so paged history must not rely on `list.projections.asOfSeq`; the plugin's list+page fallback handles it. Content `session.search` degrades to title match when the query provider is absent (by design). 2026-09-12 (evening), real-device LAN e2e with the release builds: phone paired and online in the panel, session list + history render on the device, and the app auto-reconnected after a host restart; instrumented tests OK (5) on the device. Live archive-set follow-up (archive on Web, observe the app) remains in the closed-beta scope. |
| `0.1.5-alpha.2` | `0.1.0-beta.14` working tree (V3 log path + omit null + produced files) | `0.5.0-beta.16` working tree | `v0.1.0-beta.14` `relay/` | 2026-09-09: host upgraded to alpha.2; plugin/App source aligned for produced files, workspace file GET, `/feedback`. Phone APK still `0.5.0-beta.15` until a new build is installed. |
| `0.1.5-alpha.1` | `0.1.0-beta.14` working tree (V3 log path + omit null session fields) | `0.5.0-beta.16` | `v0.1.0-beta.14` `relay/` | 2026-09-09: host smoke plus LAN phone (installed `0.5.0-beta.15`): pairing persisted, session list, send prompt, SSE/history, V3 log. Approval not exercised (full access). Model 401 is host API key, not plugin. |
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
- **Private testing:** DSH Links Relay, invite-only. Self-host issues codes
  from the single `admin` Control login. A hosted maintainer may provision
  Control tenants who then issue and revoke their own codes; there is no
  public signup and the Android App still pairs with the plugin, not
  Control. Access codes are never stored in source, releases, or npm
  packages. Hosted tenants are capped (default 4 unused invites / 8 live
  hosts); occupying slots and unused invites are the Control overview
  totals for tenants (not revoked-inclusive history) and appear on the admin
  tenant ledger (`hostFull` / `inviteFull`); full tenants sort first so the
  maintainer can tell them to revoke or mint from their own console. The ledger can recopy the Control URL; loopback is never handed out. Admin may focus one tenant to see only that user's hosts, invites, and events; tenants still self-serve revoke. `dsh-links-relay tenant hosts --login` lists occupying machines the same way over the loopback admin API.
  Minting without `replaceOldestUnused` at the unused-invite
  cap still returns 409; Control confirms, then voids the oldest live unused
  code and issues a new one so unused count stays at the cap. When a tenant is at the live-host cap, Control shows
  a banner, labels offline Hosts as still occupying a slot, keeps occupying
  machines in the main Host table (revoked records fold away), sorts those
  offline occupying machines first, marks the longest-unseen one as the
  suggested revoke, and asks
  before minting a code that can only re-enroll the same computer. Control records who minted or revoked invites and
  hosts (`GET /v1/events`), including console login success/failure
  for known accounts; invite codes, passwords, and session content are
  not stored. Unknown login names are not written, so guessing cannot
  flood the ledger. A hosted maintainer provisions tenants on loopback Control
  (`dsh-links-relay tenant` or the UI) and may put a TLS reverse
  proxy in front of `127.0.0.1:8080` so those tenants can log in;
  port 8080 is not published to the public internet. HTTP reverse
  proxies set `admin_secure_cookies`; Control TLS already marks
  cookies Secure. Loopback reverse proxies may send
  `X-Forwarded-Proto` / `X-Forwarded-For` so HTTPS Origin checks and
  login rate limits see the browser, not 127.0.0.1.   The self-host
  `admin` ledger is not capped. A valid invite that cannot mint another
  live Host returns DLR `QUOTA_EXCEEDED` (not `AUTH_FAILED`); the plugin
  asks the tenant to revoke an unused computer. The invite is not consumed,
  so the same paste can enroll after that revoke. At live-host cap with unused invites, Control relabels mint as same-host re-enroll so it does not void a code a new computer already pasted. After revoke, if unused invites remain, Control tells the operator to retry that paste rather than mint. Invalid invites stay
  `AUTH_FAILED`. Host 在线/离线 comes from Agent
  `REGISTER`/`PING` (`last_seen_at`); plugin disconnect clears it
  immediately. Quota `liveHosts` is unrevoked enrolled slots, not
  heartbeat online. Plugin 「断开」 pauses the Agent and keeps route
  credentials so it can reconnect without a new invite. Same-host re-enroll
  rotates the route without an extra quota slot; a REVOKED for the old
  route does not wipe the new plugin creds. Switching Relays returns 409
  `relay_switch` unless confirmed. After the new enroll succeeds, the
  plugin tries DLR `REVOKE_SELF` against the previous Relay so the old
  Control slot is freed without opening that console; if the old Relay
  is unreachable it keeps a `replacedRelayHost` reminder and, when the
  previous enroll token carried `c=`, a `replacedRelayControlUrl` so the
  plugin can open the old Control (not the App). Control no longer tells
  tenants to revoke before switching Relays. At live-host cap the Control
  banner names the stalest offline host and can revoke it in one click.
  A hosted enroll paste that still carries `c=` can open Control even when
  ENROLL fails with `QUOTA_EXCEEDED`, so a new computer can free a slot
  without App login. The plugin persists that public Control URL (never
  loopback) so the panel can reopen it after remount; pair/bootstrap
  snapshots still omit it. After a live route rotates, the plugin tells the
  operator that same-network phones pick up the new snapshot on the next
  open and remote-only phones must rescan the cloud QR. The cloud QR is
  shown as soon as the plugin has a live route, not only after Agent
  heartbeat; pause and revoke hide it. After a route rotation the cloud QR
  URL includes a non-secret `pairStamp` so the image is not a stale cached
  route. After a verified CONNECT MAC, a
  revoked or replaced route returns `REVOKED` so the App drops the stale
  cloud route and keeps the LAN pairing. The App stores a local
  “rescan for cloud” hint on the device list (scan the plugin QR; no
  Control login). The
  device-list health probe must not treat that `REVOKED` as a temporary
  offline; LAN may still be tried. Plugin pause stays `AGENT_OFFLINE`
  and keeps the Control slot.
  Plugin "release slot" sends DLR `REVOKE_SELF` so a hosted tenant can free
  that slot without opening Control; the App later sees `REVOKED` and
  demotes the cloud pairing to LAN. After a successful LAN bootstrap,
  `GET /dsh-link/mobile/bootstrap` may carry the live `relay` snapshot so
  the phone can pick up a replacement route without a new QR. Anonymous daily-budget
  holds return retryable `RATE_LIMITED` (not `REVOKED`); the plugin
  keeps route creds and the App keeps the pairing until UTC midnight.
  After Control
  revokes a Host, the plugin stops reconnecting and asks for a new
  access code; sessions stay on the Mac. Control copies the plugin-pasteable
  enroll URI (host included) when `public_host` is set; a bare invite alone
  is only enough for the official Relay. After a self-host revoke, a bare
  paste returns to the remembered Relay instead of the official host.
  A newly provisioned or password-reset tenant must change the Control
  password before minting or revoking; the self-host `admin` still uses
  only the config password.   Tenants can purge expired or revoked invites
  and consumed codes whose computer no longer occupies a slot, plus
  revoked Hosts, without touching other ledgers. The Host list shows
  Host ID and enroll time so same-named computers can be revoked
  without guessing; consumed invites name that computer too (kept after
  Host delete) and can revoke it while the Host still occupies a slot. Audit events include the computer name, not the
  invite code. Invite codes default to 8 hours and cannot exceed 24
  hours. Control offers 30-minute, 2-hour, 8-hour, and 24-hour presets;
  the mint response includes `expiresAt` so the UI can show when the
  code dies. A valid unused code that hits the live-host cap stays
  unused and, if it would die sooner than the default, is held until
  8 hours from that attempt (never past 24 hours from mint). After provisioning a tenant, Control prefers
  `public_control_url` (the HTTPS reverse proxy in front of loopback
  8080), then a non-loopback browser origin; `127.0.0.1` is not a tenant
  login URL. When `public_control_url` is set, the enroll paste token
  includes query `c=` so the plugin can open Control after a revoke;
  pair-info and mobile bootstrap snapshots never include it. The Android App still does not log in to Control.
- **Experimental:** a Tailscale or Cloudflare Tunnel path operated by the
  user. It is not a supported Beta path and carries no project compatibility
  promise.
- **Not supported:** direct public exposure of plugin port `18640`, frp, or
  public self-service Relay enrollment.

## Smoke-isolation warning (plugin state is global by default)

The plugin stores pairing, device, and Relay route state in
`~/.dsh/dsh-links/state.json` **globally** — not per DSH profile — and
migrates legacy dirs into it. Any profile that loads the plugin (including a
throwaway smoke profile) therefore shares devices, Relay credentials, and the
workspace-facing pairing surface with the operator's real setup:

- Revoking devices or clicking teardown actions in a smoke host revokes the
  real phones too; phone pairings cannot be restored from disk (tokens are
  HMAC-hashed at rest), so the phones must re-scan the QR.
- The Relay Agent in a smoke host connects to the production Relay with the
  real route credentials under the real host identity.
- The host-level `~/.dsh/storages/workspace.json` registry is also global; a
  smoke host that registers workspaces writes it. Reset it to
  `initialized: false` with empty `workspaceIds`/`workspaces` to make the
  host re-derive workspaces from session history on the next boot.

Any future host smoke must set the plugin's `stateDir` config (scratch
directory, 0700) via a `--patch` config overlay in the smoke profile, and
must not exercise device revocation against shared state.

## Release-status rule

Repository tags, package registry metadata, APK releases, and running Relay
deployments are separate facts. A tag or a passing local test must not be
described as a published release or a production deployment. Confirm each
external fact in the relevant release or deployment environment before
changing the status above. For every verification or release record, lock the
exact checked-out revision of each repository in that evidence; this matrix
does not hard-code moving `main` revisions or ahead counts.
