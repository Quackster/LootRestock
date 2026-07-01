# LootRestock

## Description

**LootRestock** is a lightweight Fabric mod that tracks when chests are looted and automatically resets them after a configurable period. This ensures that loot can replenish over time, ideal for persistent servers, custom maps, and adventure-based gameplay.

### Links

Modrinth: https://modrinth.com/mod/lootrestock

CurseForge: https://curseforge.com/minecraft/mc-mods/lootrestock

## Key Features

- Tracks every lootable chest opened by players
- Resets chest contents based on original loot tables only when chunks are loaded
- **Performance-optimized**: Chest resets are processed only when players are nearby and chunks are active
- Configurable cooldown period using simple time units (e.g. `7 days`, `12 hours`, `30 minutes`)
- Persists data across server restarts
- Optionally reset only when chests are empty (or always) after timeout expires
- Shows players an action-bar reminder that loot chests restock and should be left in place unless removal is needed
- Supports protected chest breaking with optional crouch-and-hold removal

## Performance Design
LootRestock is designed with server performance in mind:
- Chest resets only occur when the containing chunk is loaded by a player
- Minimal memory footprint by only tracking opened chests

## Configuration

LootRestock generates a simple config file at `./lootrestock.properties` with the following options:

```properties
reset_time_value=7
reset_time_unit=days
only_reset_when_empty=true
include_barrels=false
allow_chest_breaking=op_only
require_crouch_to_break=true
crouch_break_seconds=4
```

- `reset_time_value`: Number of time units before a chest is eligible for reset
- `reset_time_unit: Units of time`: `seconds`, `minutes`, `hours`, or `days`
- `only_reset_when_empty`: (default value: `true`)
  - `true`: Chests will reset only if empty (after the cooldown)
  - `false`: Chests will reset regardless of contents (after the cooldown)
- `include_barrels`: Whether barrel loot should get reset (default value: `false`)
- `allow_chest_breaking`: Who can remove restocking containers: `op_only`, `anyone`, or `no_one`
- `require_crouch_to_break`: Whether permitted players must crouch to remove restocking containers
- `crouch_break_seconds`: Extra hold time required while crouching before a restocking container can be removed

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
