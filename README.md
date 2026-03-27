# Slay the Spire 1 MCP Mod

This repository provides a playable `Slay the Spire 1` MCP mod setup.
It contains two cooperating parts:

- a Java bridge mod that runs inside `Slay the Spire 1`, exports game state, and simulates player input
- a standalone Python MCP server that exposes the bridge over stdio for clients such as Codex or OpenCode

The design goal is simple:

- read game state
- send one action at a time
- do not modify save files or inject gameplay results directly

## What this project is

This is a `Slay the Spire 1` bridge mod plus MCP server.
It is intended for local single-player automation, agent testing, and MCP-based gameplay.

Architecture:

- Java writes `state.json`
- Python writes `command.json`
- Java writes `response.json`

The Java mod and Python MCP server communicate through a shared runtime directory.

## Quick Start

1. Install `Slay the Spire 1`.
2. Install `ModTheSpire` and `BaseMod`.
3. Build the mod jar:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1
```

4. Install the jar into the game:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\install.ps1
```

5. Create the Python MCP environment:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\create-mcp-conda-env.ps1
```

6. Start the MCP server manually if you want to test it directly:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-sts-mcp.ps1
```

7. Or register it in your MCP client so the client starts it automatically.

## What has been tested

The current bridge has been tested through these flows:

- main menu to character select to run start
- Neow event flow
- map routing and whole-map export
- normal combat and elite combat
- card play, turn end, and hand/grid selection
- combat potion use
- normal rewards: gold, potion, card
- events and multi-step event dialogs
- campfire: rest, smith, confirm
- shop: open, buy card, buy relic, buy potion, purge, cancel, leave room
- treasure room: open chest, claim reward, leave room
- act boss reward flow:
  - boss combat ends
  - normal post-combat rewards
  - proceed into boss treasure room
  - open boss chest
  - choose one of three boss relics
  - proceed into the next act

## Runtime Contract

The Java bridge mod writes:

- `state.json`: latest exported game state
- `response.json`: last command result

The Python MCP server writes:

- `command.json`: the next action request for the game

Set `STS_MCP_RUNTIME_DIR` for both processes if you want a custom runtime path.
If unset, the Python server defaults to `./runtime` under the current working directory.

## Cross-Machine Configuration

The helper scripts are portable.
They resolve paths in this order:

1. explicit script parameters
2. environment variables
3. local auto-detection when possible

Useful environment variables:

- `STS_GAME_DIR`: local `SlayTheSpire` install directory
- `STS_BASEMOD_JAR`: optional explicit path to `BaseMod.jar`
- `STS_MAVEN_REPO`: optional Maven local repository path
- `STS_MCP_ENV_PREFIX`: optional conda env prefix for the MCP server
- `STS_MCP_RUNTIME_DIR`: optional runtime directory override
- `JAVA_HOME`: optional JDK root if `javac.exe` is not already discoverable
- `MAVEN_HOME`: optional Maven root if `mvn.cmd` is not already discoverable
- `CONDA_EXE`: optional explicit `conda.exe` path

On Windows, the scripts also try to discover the Steam root from the registry and then search Steam library folders for `SlayTheSpire`.

## Build the Java Mod

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1
```

With explicit overrides:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1 `
  -GameDir "<game-dir>" `
  -JdkHome "<jdk-home>" `
  -MavenHome "<maven-home>"
```

Direct Maven example:

```powershell
mvn "-Dsts.jar=<path-to-desktop-1.0.jar>" "-Dmts.jar=<path-to-ModTheSpire.jar>" "-Dbasemod.jar=<path-to-BaseMod.jar>" clean package
```

## Install the Java Mod

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\install.ps1
```

Optional explicit game path:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\install.ps1 -GameDir "<game-dir>"
```

## Launch ModTheSpire

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-mts.ps1
```

Debug launch:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\debug-mts.ps1
```

## Conda Setup on Windows

Create a dedicated conda environment and install the Python package in editable mode:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\create-mcp-conda-env.ps1
```

Default env resolution:

- `STS_MCP_ENV_PREFIX` if set
- otherwise `CONDA_ENVS_PATH\sts-game-mcp` if `CONDA_ENVS_PATH` exists
- otherwise a normal conda-style `envs\sts-game-mcp` under the detected conda root

Explicit override example:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\create-mcp-conda-env.ps1 `
  -CondaExe "<conda-exe>" `
  -EnvPrefix "<conda-env-prefix>"
```

## Start the MCP Server Manually

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-sts-mcp.ps1
```

With explicit overrides:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-sts-mcp.ps1 `
  -EnvPrefix "<conda-env-prefix>" `
  -RuntimeDir "<runtime-dir>"
```

You can also run it directly:

```powershell
python -m sts_game_mcp --runtime-dir <runtime-dir>
```

or:

```powershell
sts-game-mcp --runtime-dir <runtime-dir>
```

## MCP Client Configuration

### Codex

Add this to `~/.codex/config.toml` and adjust the paths:

```toml
[mcp_servers.sts-game]
command = "D:/Anaconda/Anaconda/envs/sts-game-mcp/python.exe"
args = ["-m", "sts_game_mcp", "--runtime-dir", "D:/path/to/runtime"]
```

### OpenCode

Example `opencode.json` or `opencode.jsonc` entry:

```json
{
  "mcp": {
    "sts-game": {
      "type": "local",
      "enabled": true,
      "command": [
        "D:/Anaconda/Anaconda/envs/sts-game-mcp/python.exe",
        "-m",
        "sts_game_mcp",
        "--runtime-dir",
        "D:/path/to/runtime"
      ],
      "environment": {
        "STS_MCP_RUNTIME_DIR": "D:/path/to/runtime"
      }
    }
  }
}
```

## Available MCP Tools

The Python MCP server is implemented in [`sts_game_mcp/server.py`](sts_game_mcp/server.py).
It exposes:

- `get_game_state`
- `step_game`
- `runtime_status`

It also exposes resources:

- `sts-game://state`
- `sts-game://runtime-status`

## Supported Game Actions

The main high-level actions currently supported are:

- `choose_main_menu_option`
- `select_character`
- `play_card`
- `end_turn`
- `use_potion`
- `proceed`
- `enter_boss`
- `claim_room_reward`
- `choose_reward`
- `skip_reward`
- `choose_boss_reward`
- `choose_event_option`
- `choose_campfire_option`
- `open_treasure_chest`
- `open_shop`
- `buy_shop_card`
- `buy_shop_relic`
- `buy_shop_potion`
- `buy_shop_purge`
- `choose_map_node`
- `select_hand_card`
- `select_grid_card`
- `confirm`
- `cancel`

## State Quality Notes

The exported state is designed for agents, so the bridge also includes helper fields such as:

- `action_names`
- `reward_types`
- `reward_stage`
- `playable_hand`
- `playable_hand_count`
- `has_playable_cards`
- `map.all_nodes`
- `map.available_nodes`
- `treasure_chest.reward_screen_open`

The intended control loop is:

1. read state
2. choose one action
3. send one MCP command
4. read state again

Do not chain assumptions across multiple UI transitions without reading the next state.

## Included Skill for Other Models

This repo also includes a lightweight skill for models that should learn how to play through this MCP bridge:

- [`skills/sts-mcp-player/SKILL.md`](skills/sts-mcp-player/SKILL.md)
- [`skills/sts-mcp-player/references/strategy-notes.md`](skills/sts-mcp-player/references/strategy-notes.md)

That skill explains:

- how to read this repo's MCP state safely
- how to plan routes before clicking map nodes
- how to handle reward flow, shops, campfires, and boss chests
- a simple Silent-oriented beginner-safe play style

## Install from GitHub on Another Machine

1. Clone the repo.
2. Install `Slay the Spire 1`, `ModTheSpire`, and `BaseMod`.
3. Make sure the helper scripts can find the local game/tool paths, or pass them explicitly.
4. Build the Java mod jar with `scripts\build.ps1`.
5. Install the jar with `scripts\install.ps1`.
6. Create a Python environment.
7. Run `pip install .` or `pip install -e .`.
8. Start `sts-game-mcp --runtime-dir <runtime-dir>`.
9. Register the MCP server in your client.

The Python package itself has no third-party runtime dependencies.

## Repository Scripts

- `scripts\Resolve-StsBridgeConfig.ps1`: shared path resolution helpers for the other scripts
- `scripts\build.ps1`: build the Java mod jar with Maven
- `scripts\install.ps1`: copy the built jar into the game's `mods` directory
- `scripts\run-mts.ps1`: launch `ModTheSpire`
- `scripts\debug-mts.ps1`: launch `ModTheSpire` with debug port `5005`
- `scripts\create-mcp-conda-env.ps1`: create the MCP conda env and install the Python package
- `scripts\run-sts-mcp.ps1`: manually start the Python MCP server from that env

## Debugging in IntelliJ IDEA

1. Open this folder in IntelliJ IDEA.
2. Run `scripts\debug-mts.ps1`.
3. Create a `Remote JVM Debug` configuration on `localhost:5005`.
4. Set breakpoints in `src/main/java/stsmodstarter/`.
5. Start the debug config, then launch the game from the `ModTheSpire` window.

