# Slay the Spire Bridge Mod + Python MCP

This repository contains two cooperating parts:

- a `Slay the Spire 1` bridge mod that exports game state and accepts high-level commands
- a standalone Python MCP server that reads those runtime files and exposes them over stdio

## Architecture

The Java mod and the Python MCP server communicate through a shared runtime directory:

- Java writes `state.json`
- Python writes `command.json`
- Java writes `response.json`

That means another user can clone this repo, build the mod, install the jar, and run the MCP server on their own machine.
They do **not** need your drive letters, but they do need to point the scripts at their own game and tool paths.

## What the Python MCP package does

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

## Cross-machine configuration

The helper scripts are now portable. They resolve paths in this order:

1. explicit script parameters
2. environment variables
3. local auto-detection when possible

Useful environment variables:

- `STS_GAME_DIR`: the local `SlayTheSpire` install directory
- `STS_BASEMOD_JAR`: optional explicit path to `BaseMod.jar`
- `STS_MAVEN_REPO`: optional Maven local repository path
- `STS_MCP_ENV_PREFIX`: optional conda env prefix for the MCP server
- `STS_MCP_RUNTIME_DIR`: optional runtime directory override
- `JAVA_HOME`: optional JDK root if `javac.exe` is not already discoverable
- `MAVEN_HOME`: optional Maven root if `mvn.cmd` is not already discoverable
- `CONDA_EXE`: optional explicit `conda.exe` path

On Windows, the scripts also try to discover the Steam root from the registry and then search Steam library folders for `SlayTheSpire`.

## Build the Java mod

The easiest path is still the helper script:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1
```

You can also override values explicitly:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1 `
  -GameDir "<game-dir>" `
  -JdkHome "<jdk-home>" `
  -MavenHome "<maven-home>"
```

If you want to run Maven directly instead of using the script, pass the local jar paths yourself:

```powershell
mvn "-Dsts.jar=<path-to-desktop-1.0.jar>" "-Dmts.jar=<path-to-ModTheSpire.jar>" "-Dbasemod.jar=<path-to-BaseMod.jar>" clean package
```

## Install the Java mod into the game

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

## Conda setup on Windows

This repo includes a helper script that creates a dedicated conda environment and installs the Python package in editable mode:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\create-mcp-conda-env.ps1
```

By default it uses:

- `STS_MCP_ENV_PREFIX` if set
- otherwise `CONDA_ENVS_PATH\sts-game-mcp` if `CONDA_ENVS_PATH` exists
- otherwise a normal conda-style `envs\sts-game-mcp` under the detected conda root

You can also override both values explicitly:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\create-mcp-conda-env.ps1 `
  -CondaExe "<conda-exe>" `
  -EnvPrefix "<conda-env-prefix>"
```

## Start the MCP server manually

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-sts-mcp.ps1
```

With explicit overrides:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-sts-mcp.ps1 `
  -EnvPrefix "<conda-env-prefix>" `
  -RuntimeDir "<runtime-dir>"
```

## Install from GitHub on another machine

1. Clone the repo.
2. Install `Slay the Spire`, `ModTheSpire`, and `BaseMod` on that machine.
3. Make sure the helper scripts can find the local game/tool paths, or pass them explicitly.
4. Build the Java mod jar with `scripts\build.ps1`.
5. Install the jar with `scripts\install.ps1`.
6. Create a Python environment.
7. Run `pip install .` or `pip install -e .`.
8. Start `sts-game-mcp --runtime-dir <runtime-dir>`.

The Python package itself has no third-party runtime dependencies.

## Repository scripts

- `scripts\Resolve-StsBridgeConfig.ps1`: shared path resolution helpers for the other scripts
- `scripts\build.ps1`: build the Java mod jar with Maven
- `scripts\install.ps1`: copy the built jar into the game's `mods` directory
- `scripts\run-mts.ps1`: launch `ModTheSpire`
- `scripts\debug-mts.ps1`: launch `ModTheSpire` with debug port `5005`
- `scripts\create-mcp-conda-env.ps1`: create the MCP conda env and install the Python package
- `scripts\run-sts-mcp.ps1`: manually start the Python MCP server from that env

## Supported game actions

- `choose_main_menu_option`
- `select_character`
- `play_card`
- `end_turn`
- `proceed`
- `claim_room_reward`
- `choose_reward`
- `skip_reward`
- `choose_boss_reward`
- `choose_event_option`
- `choose_campfire_option`
- `buy_shop_card`
- `buy_shop_relic`
- `buy_shop_potion`
- `buy_shop_purge`
- `choose_map_node`
- `select_hand_card`
- `select_grid_card`
- `confirm`
- `cancel`

## Debugging the Java mod in IntelliJ IDEA

1. Open this folder in IntelliJ IDEA.
2. Run `scripts\debug-mts.ps1`.
3. Create a `Remote JVM Debug` configuration on `localhost:5005`.
4. Set breakpoints in `src/main/java/stsmodstarter/`.
5. Start the debug config, then launch the game from the `ModTheSpire` window.