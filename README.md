# FabricDash

**Modern web-based admin dashboard for Fabric Minecraft servers**

FabricDash gives you a full web panel for server administration from any browser: live stats, player moderation, console access, backups, file management, settings, and updates.

## Version

- Current branch: **FabricDash (Fabric edition)**

## Features

### 📊 Real-Time Monitoring
- Live server statistics (TPS, memory, CPU)
- Historical performance graphs
- Console output streaming
- Online/offline player tracking

### 👥 Player Management
- Player profiles and moderation actions
- Kick, ban, freeze/unfreeze, teleport
- Player notes
- Inventory and Ender Chest management

### ⚙️ Server Settings
- MOTD editing
- View/simulation distance controls
- Gamerule toggles
- Whitelist controls
- Server icon upload

### 📁 Files, Backups, Datapacks
- File browser/editor/uploader
- Folder upload support
- Manual + scheduled backups
- Backup download/delete
- Datapack upload/toggle/delete

### 🧩 Fabric-Friendly Mod Workflow
- Mod upload/delete through the panel (`mods/`)
- Fabric-focused wording/UI (Mods, not Plugins)

### 🔄 Updater
- Checks latest release from `Framepersecond/FabricDash`
- Download + stage update from the web panel
- Applies staged update on restart
- If GitHub rate-limit is hit, automatic checks pause for **24 hours**
- Manual **Scan for Updates** can still force a retry

## Installation

1. Download the latest FabricDash `.jar` from the [releases page](../../releases).
2. Put it in your server's `mods/` folder.
3. Start/restart the server.
4. Open the panel at `http://<server-ip>:<port>` (default port: `8080`).

## Initial Setup

1. Join as OP.
2. Run `/dash register`.
3. Open the setup URL from chat or console.
4. Enter the generated code and create your web admin login.

## Commands

| Command | Description |
|---------|-------------|
| `/dash register` | Generate setup/registration code |
| `/dash update` | Download/stage latest FabricDash update |
| `/dash port <port>` | Change web panel port |

## Configuration

Config file location:

- `config/fabricdash/config.yml`

Important keys:

- `port` (default `8080`)
- `backups.max-backups`
- `panel-url` (optional public URL override for setup links)
- `bridge.*` settings (if using NeoBridge/NeoDash integration)
- `updater.github-repo` (default `Framepersecond/FabricDash`)

## Supported Environment

- **Minecraft:** 1.21+ (Fabric server)
- **Loader:** Fabric
- **Java:** 21+

## Security

- Time-limited registration codes
- Session-based web auth
- Action/audit logging
- Path-safe file management and upload checks

## Release Notes

See [RELEASE_NOTES.md](./RELEASE_NOTES.md) for FabricDash changes and recent fixes.

## License

BSD 3-Clause License (see `LICENSE`).

