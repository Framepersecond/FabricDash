# FabricDash Release Notes

## Latest (Fabric stabilization update)

### Core fixes
- Fixed command execution wiring from dashboard/home.
- Fixed player profile/action routing regressions.
- Reworked freeze to a true movement lock with title feedback.
- Fixed item-give/enderchest give action mapping.

### Files + paths
- Unified server-root resolution across panel features.
- Fixed file browser showing empty results when run-directory resolution was wrong.
- File upload/download/edit/delete/rename now use the same resolved server root.

### Settings
- MOTD now persists to the correct `server.properties` and applies live.
- View/simulation distance persistence and runtime application improved.
- Gamerule action handling aligned with UI controls.

### Backups + datapacks
- Backup content collection fixed for resolved server root and world paths.
- Empty/tiny invalid backup archives are now prevented (explicit failure instead).
- Datapack upload/toggle/delete flow stabilized.

### Updater
- Updater repository target is `Framepersecond/FabricDash`.
- Update staging/apply path uses Fabric `mods/` workflow.
- GitHub rate-limit detection now pauses **automatic checks for 24 hours**.
- Manual **Scan for Updates** bypasses backoff and can force a retry immediately.

### UI / Fabric alignment
- Sidebar and settings wording updated to **Mods** (Fabric context).
- Fabric-only settings cleanup (removed Spark/DiscordSRV-specific UI expectations).

