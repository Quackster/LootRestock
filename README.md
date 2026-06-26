# LootRestock

## Description

**LootRestock** is a lightweight Fabric mod that tracks when chests are looted and automatically resets them after a configurable period. This ensures that loot can replenish over time, ideal for persistent servers, custom maps, and adventure-based gameplay.

### Links

Modrinth: https://modrinth.com/mod/lootrestock

CurseForge: https://curseforge.com/minecraft/mc-mods/lootrestock

## Key Features

- Tracks every lootable chest opened by players
- Resets chest contents based on original loot tables only when chunks are loaded
- Optionally respawns items removed from item frames, such as Elytras in End Cities
- Optionally lets trial chamber vaults reward the same player again after a separate vault reset interval
- **Performance-optimized**: Chest resets are processed only when players are nearby and chunks are active
- Configurable cooldown period using simple time units (e.g. `7 days`, `12 hours`, `30 minutes`)
- Persists data across server restarts
- Optionally reset only when chests are empty (or always) after timeout expires

## Performance Design
LootRestock is designed with server performance in mind:
- Chest resets only occur when the containing chunk is loaded by a player
- Minimal memory footprint by only tracking opened chests

## Configuration

LootRestock generates a simple config file at `./lootrestock.properties` with the following options:

```properties
reset_time_value=7
reset_time_unit=days
reset_cron=
vault_reset_time_value=7
vault_reset_time_unit=days
vault_reset_cron=
only_reset_when_empty=true
include_barrels=false
include_item_frames=false
include_vaults=false
allow_chest_breaking=op_only
allow_item_frame_breaking=op_only
```

- `reset_time_value`: Number of time units before a chest is eligible for reset
- `reset_time_unit: Units of time`: `seconds`, `minutes`, `hours`, or `days`
- `reset_cron`: Optional cron expression that overrides `reset_time_value`/`reset_time_unit`
- `vault_reset_time_value`: Number of time units before a tracked vault removes an individual rewarded-player UUID
- `vault_reset_time_unit`: Vault timer unit: `seconds`, `minutes`, `hours`, or `days`
- `vault_reset_cron`: Optional cron expression that overrides `vault_reset_time_value`/`vault_reset_time_unit`
- `only_reset_when_empty`: (default value: `true`)
  - `true`: Chests will reset only if empty (after the cooldown)
  - `false`: Chests will reset regardless of contents (after the cooldown)
- `include_barrels`: Whether barrel loot should get reset (default value: `false`)
- `include_item_frames`: Whether item frame contents should respawn after the reset interval (default value: `false`)
- `include_vaults`: Whether trial chamber vaults should become reusable by the same player after the vault reset interval (default value: `false`)
- `allow_chest_breaking`: Who can break tracked loot containers and chest minecarts: `op_only`, `anyone`, or `no_one` (default value: `op_only`)
- `allow_item_frame_breaking`: Who can break tracked item frames and their supporting blocks: `op_only`, `anyone`, or `no_one` (default value: `op_only`)

## Data Persistence

- Chest interaction data is stored in the world save folder (`chest_reset_data.json`)
- Safe to shut down or reload server without losing tracked state

## Use Case Examples

- Keep dungeon loot fresh in multiplayer worlds
- Refill treasure chests in adventure maps
- Ensure players always have a reason to explore

## Installation

1. Requires **Fabric Loader**
2. Install **Fabric API**
3. Place the mod JAR in your `mods/` folder
4. Start the game and a default config will be generated

## Metadata

- **Mod ID**: `lootrestock`
- **License**: GPL v3
- **Game Versions**: `1.21+` 
- **Mod Loaders**: `Fabric`
- **Dependencies**: `fabric-api`
