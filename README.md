# FabricDash

**Version 5.0 - Material 3 Expressive interface and full language support**

FabricDash brings the Dash control surface to Fabric: live server operation, player care, files, verified backups, Guardian, mod maintenance and evidence-driven recovery in one self-hosted interface.

[![Version](https://img.shields.io/badge/version-5.0.0-22d3ee)](#release-status)
[![Loader](https://img.shields.io/badge/loader-Fabric-22c55e)](#requirements)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.x%20%7C%2026.2%20%7C%2026.3-22c55e)](#requirements)
[![Java](https://img.shields.io/badge/Java-21%20%7C%2025-f97316)](#requirements)

## New in 5.0

Dash 5.0 is the biggest release so far: a new Material 3 Expressive interface in 14 languages, a standard MCP server instead of a built-in AI, a much more careful Autopilot, a complete security layer, and operations and community tools that run your server for you.

- **Material 3 Expressive interface** on every page, with phone layout, dark and light themes and 14 languages
- **Autopilot**: explained, guarded, undoable automatic fixes with learning statistics and your own rules
- **AI access (MCP)**: connect Claude, ChatGPT-compatible clients, Cursor or scripts through scoped API tokens, off by default
- **Security**: two-factor login and passkeys for everyone, session overview with new-device alerts, IP allow-list, signed audit log, guided secret rotation
- **Operations**: scheduled restarts with countdown, one-click maintenance mode, lag detective, update manager with trial run and rollback, encrypted offsite backups with automatic restore test
- **Community**: public status page, whitelist applications, ban appeals, chat moderation, GDPR export and erase
- **No more beta**: Intelligence, Maintenance and Guardian are regular features; the internal Dash AI is gone, replaced by MCP

Not every build has everything yet. The table shows what each project contains in 5.0:

| Feature | Dash (Paper) | FabricDash / ForgeDash | NeoDash |
| --- | :---: | :---: | :---: |
| Material 3 Expressive UI, 14 languages | yes | yes | yes |
| Scoped API tokens and write API | yes | yes | yes |
| MCP server (AI access) | yes | yes | yes (fleet tools) |
| Autopilot core | yes | yes | fleet cards |
| Autopilot learning statistics, custom rules, snooze | yes | not yet | fleet warnings |
| 2FA, passkeys, sessions, new-device alerts | yes | yes | yes |
| IP allow-list, signed audit log, secret rotation | yes | yes | yes |
| Scheduled restarts, maintenance mode, lag detective | yes | not yet | not applicable |
| Update manager, offsite backups | yes | not yet | not applicable |
| Status page, applications, appeals, chat moderation, GDPR | yes | not yet | not applicable |

---
## Partner

Dash is proudly partnered with **EmeraldHost**, a Minecraft server host. Use the code **Frxme10** for **10% off** your server.

If Dash saves you time, you can support its development on [Buy Me a Coffee](https://buymeacoffee.com/frxme). Thank you!

---

## Languages

The whole interface is available in **English, German (Deutsch), Spanish (Español), French (Français), Italian, Portuguese, Dutch, Polish, Turkish, Russian, Japanese, Korean, Simplified Chinese (简体中文) and Traditional Chinese (繁體中文)**.

- Pick a language from the translate button in the top bar (or the drawer on phones), in *Settings → Language*, or choose **Automatic** to follow the browser. The sign-in page follows the browser or the last choice too.
- Every user keeps their own choice; it is saved with the account and a `dash_lang` cookie.
- Translations ship as one dictionary per language (`web/i18n/<code>.json`), served as an immutable, content-hashed asset, so a page only loads the language it needs.
- German, Spanish, French and both Chinese variants are translated by hand end to end; the other languages are hand-translated for all labels and buttons, with long explanatory sentences machine-translated with MADLAD-400 (Apache-2.0) and checked for placeholders. Anything still untranslated falls back to English.

## Intelligence Center

FabricDash 4.3 adds the same complete 25-feature Intelligence layer as Dash, connected to Fabric's real mod inventory, backup, scheduler and server lifecycle services.

| Workspace | Features |
|---|---|
| Investigate | Shadow Boot Lab, Root Cause Explorer, Performance Regression Radar, Plugin/Mod Resource Attribution, Guided Safe Mode, Dependency and Impact Map. |
| Change | State Time Machine, Backup Content Explorer, Adaptive Maintenance Window, Action Guardrails. |
| Players | Player Journey Replay, Support and Appeals Inbox, Cross-Server Player Identity, Player Experience Score. |
| Policy | Just-in-Time Staff Access, Dual-Control Actions, Retention Center. |
| Supply | Supply Chain Center, Config Schema Assistant, Universal Search. |
| Reliability | World Health Center, Storage Intelligence, Service Level Dashboard. |
| Response | Operator War Room, Public Status Page. |

State recovery is staged outside live configuration, verifies archive boundaries, CRC and SHA-256, creates a safety snapshot and can roll moved originals back if a restore fails. Mod supply-chain views correlate Fabric metadata, hashes, duplicate artifacts and dependency impact.

## Complete Fabric Dashboard

| Area | Capability |
|---|---|
| Live operations | TPS, memory, CPU, uptime, online players, console stream and authorized commands. |
| Players | Profiles, notes, freeze, kick, ban, teleport, whitelist and inventory tools. |
| Guardian | Cases, evidence, block/container history, incidents, scores, rollback previews and import tools. |
| Files | Whole-row navigation, upload and folder upload, edit, rename, download and guarded delete. |
| Backups | Verified backups, schedules, retention, restore points, download and delete. |
| Mods | Installed mod inventory, JAR upload, Modrinth browser, startup scan and update hints. |
| Intelligence | Diagnosis, guarded changes, recovery, player care, supply-chain checks and coordinated response. |
| Staff and delivery | Tickets, notes, Discord delivery tests, notifications, graphs and activity. |
| NeoDash bridge | Signed SSO, approval-aware users, HTTPS-aware return links and fleet restart routing. |

## Evolution Since 3.1

- **4.0:** introduced the shared fluid motion layer, paged Guardian, whole-row file interaction, in-place uploads, Fabric update flows and improved NeoDash handoff.
- **4.1:** completed Staff, delivery, profiler and Doctor workflows; hardened backups and path handling; preserved sessions and tested every visible beta selection.
- **4.2:** added persistent maintenance, incident, recovery, drift, compatibility, capacity, permission and automation workflows.
- **4.3:** adds 25 Intelligence features, transactional state recovery, action guardrails, service health publishing, strict validation and smoother page swaps.
- **4.4:** ports FabricDash to the official 26.2 namespace, Java 25, Fabric Loader 0.19.3 and Fabric API 0.158.0; Operations is removed in favor of Intelligence.
- **4.5:** restores native artifacts for every Minecraft 1.21 patch and 26.2, adds Dash AI, and expands English and Spanish across the current interface.
- **4.5.1:** adds a native Minecraft 26.3 artifact (Fabric API 0.160.7+26.3, Loom 1.17.21, Java 25) alongside the existing targets.

## Installation

1. Put the JAR matching the exact Minecraft version in `mods`, for example `fabricdash-mc1.21.8-5.0.0.jar` or `fabricdash-mc26.3-5.0.0.jar`.
2. Restart the server once.
3. Run `/dash register` as an operator.
4. Open the one-time setup URL and create the first web admin.

Configuration is stored in `config/fabricdash/config.yml`. The default web port is `8080`; use `panel-url` when the public HTTPS address differs from the bind address.

## Commands

| Command | Description |
|---|---|
| `/dash register` | Creates a short-lived setup or registration code. |
| `/dash update` | Stages the newest verified FabricDash release when updates are enabled. |
| `/dash port <port>` | Changes the dashboard port. |


## Hosting behind a reverse proxy

Set `base-path` when the panel is served below a sub-path, for example `/admin`
for `https://example.org/admin/`. Every link, form target and asset URL is then
emitted with that prefix. Configure the proxy to strip the prefix before
forwarding — `proxy_pass http://127.0.0.1:8080/` with the trailing slash in
nginx, or Traefik's `StripPrefix` middleware. When `base-path` is left blank, an
`X-Forwarded-Prefix` header sent by the proxy is honoured instead. Forward
`Host` (or `X-Forwarded-Host`) and `X-Forwarded-Proto` so the same-origin checks
see the browser-facing address.

## Security

FabricDash enforces authenticated sessions, CSRF checks, server-side permissions, bounded inputs, canonical path handling, protected files, guarded archives, SSRF-resistant downloads and audited high-value actions. Intelligence policies can require reasons, verified backups, quiet hours, temporary rights or dual approval.

Use TLS or a trusted reverse proxy and restrict the panel to staff.

## Requirements

- Minecraft 1.21 through 1.21.11 with the matching `mc1.21.x` artifact and Java 21.
- Minecraft 26.2 with `fabricdash-mc26.2-5.0.0.jar` and Java 25.
- Minecraft 26.3 with `fabricdash-mc26.3-5.0.0.jar` and Java 25.
- Fabric Loader 0.19.5 or newer.
- English and Spanish are selectable in Plugin Settings and stored per user; `en-GB` and `es-MX` locale variants normalize correctly.

Build one target with `./gradlew build -Pdash_target=1.21.8`, or build and test the full matrix on Windows with `./build-all-targets.ps1`.

## Release Status

Current release: **FabricDash 5.0.0**

See [RELEASE_NOTES.md](./RELEASE_NOTES.md), [GITHUB_RELEASE_4.5.md](./GITHUB_RELEASE_4.5.md) and [MODRINTH_README.md](./MODRINTH_README.md).
