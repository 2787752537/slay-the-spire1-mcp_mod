# Slay the Spire Bridge Mod + Python MCP

This repository contains two cooperating parts:

- a `Slay the Spire 1` bridge mod that exports game state and accepts high-level commands
- a standalone Python MCP server that reads those runtime files and exposes them over stdio

## What the Python MCP package does

The Python package is installable on its own and can be published to GitHub as a normal repo.
After installation, users can start it manually with:

```powershell
python -m sts_game_mcp --runtime-dir <runtime-dir>
```

or:

```powershell
sts-game-mcp --runtime-dir <runtime-dir>
```

The MCP server expects the bridge mod and the MCP process to share the same runtime directory.

## Runtime contract

The Java bridge mod writes:

- `state.json`: latest exported game state
- `response.json`: last command result

The Python MCP server writes:

- `command.json`: the next action request for the game

Set `STS_MCP_RUNTIME_DIR` for both processes if you want a custom runtime path. If unset, the Python server defaults to `./runtime` under the current working directory.

## Conda setup on Windows

This repo includes a Windows helper script that creates a dedicated conda environment under your requested envs root and installs the Python package in editable mode:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\create-mcp-conda-env.ps1
```

By default that creates:

- conda env: `D:\Anaconda\Anaconda\envs\sts-game-mcp`
- Python version: `3.11`

You can also do it manually:

```powershell
D:\Anaconda\Anaconda\Scripts\conda.exe create --prefix D:\Anaconda\Anaconda\envs\sts-game-mcp python=3.11 pip -y
D:\Anaconda\Anaconda\envs\sts-game-mcp\python.exe -m pip install -e .
```

## Start the MCP server manually

Once the env exists, start the server with:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-sts-mcp.ps1
```

To point it at another runtime directory:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-sts-mcp.ps1 -RuntimeDir D:\path\to\runtime
```

## Install from GitHub

For another machine or another user:

1. Clone the repo.
2. Create a Python env.
3. Run `pip install .` or `pip install -e .`.
4. Start `sts-game-mcp --runtime-dir <runtime-dir>`.

The Python package itself has no third-party runtime dependencies.

## Repository scripts

- `scripts\build.ps1`: build the Java mod jar with Maven
- `scripts\install.ps1`: copy the built jar into the game's `mods` directory
- `scripts\run-mts.ps1`: launch `ModTheSpire`
- `scripts\debug-mts.ps1`: launch `ModTheSpire` with debug port `5005`
- `scripts\create-mcp-conda-env.ps1`: create the MCP conda env and install the Python package
- `scripts\run-sts-mcp.ps1`: manually start the Python MCP server from that env

## Supported game actions

- `play_card`
- `end_turn`
- `proceed`
- `claim_room_reward`
- `choose_reward`
- `skip_reward`
- `choose_event_option`
- `choose_campfire_option`
- `choose_map_node`
- `select_hand_card`
- `select_grid_card`
- `confirm`

## Debugging the Java mod in IntelliJ IDEA

1. Open this folder in IntelliJ IDEA.
2. Run `scripts\debug-mts.ps1`.
3. Create a `Remote JVM Debug` configuration on `localhost:5005`.
4. Set breakpoints in `src/main/java/stsmodstarter/`.
5. Start the debug config, then launch the game from the `ModTheSpire` window.
