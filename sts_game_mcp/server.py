from __future__ import annotations

import argparse
import json
import os
import sys
import time
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional

from . import __version__

LATEST_PROTOCOL_VERSION = "2025-11-25"
SUPPORTED_PROTOCOL_VERSIONS = (
    "2025-11-25",
    "2025-06-18",
    "2025-03-26",
    "2024-11-05",
)
SERVER_NAME = "sts-game-mcp"
STATE_RESOURCE_URI = "sts-game://state"
RUNTIME_STATUS_RESOURCE_URI = "sts-game://runtime-status"
STATE_SETTLE_MS = 250


class StsRuntime:
    def __init__(self, runtime_dir: Path) -> None:
        self.runtime_dir = runtime_dir
        self.state_path = runtime_dir / "state.json"
        self.command_path = runtime_dir / "command.json"
        self.response_path = runtime_dir / "response.json"

    def read_state(self) -> Dict[str, Any]:
        return self._normalize_state(json.loads(self.read_state_text()))

    def read_state_text(self) -> str:
        if not self.state_path.exists():
            raise RuntimeError(
                "State file not found. Start the game with the bridge mod first: "
                f"{self.state_path}"
            )
        return self.state_path.read_text(encoding="utf-8")

    def send_command(self, payload: Dict[str, Any], timeout_ms: int = 5000) -> Dict[str, Any]:
        before_state_text = self.read_state_text() if self.state_path.exists() else ""
        command_id = str(uuid.uuid4())
        command = {"id": command_id, **payload}
        if self.response_path.exists():
            try:
                self.response_path.unlink()
            except OSError:
                pass
        self._atomic_write(self.command_path, command)
        deadline = time.time() + (timeout_ms / 1000.0)
        while time.time() < deadline:
            if self.response_path.exists():
                response = json.loads(self.response_path.read_text(encoding="utf-8"))
                if response.get("id") == command_id:
                    latest_state = self._wait_for_latest_state(before_state_text, deadline)
                    response["ack_state"] = response.get("state")
                    response["state"] = latest_state
                    response["state_changed"] = latest_state != json.loads(before_state_text) if before_state_text else True
                    return response
            time.sleep(0.05)
        raise RuntimeError(f"Timed out waiting for game response after {timeout_ms} ms")

    def _wait_for_latest_state(self, before_state_text: str, deadline: float) -> Dict[str, Any]:
        latest_text = before_state_text
        # step_game 杩斿洖鏃朵紭鍏堢粰璋冪敤鏂规渶鏂扮殑 state.json锛岃€屼笉鏄悓甯ф棫蹇収銆?
        while time.time() < deadline:
            try:
                current_text = self.read_state_text()
            except RuntimeError:
                time.sleep(0.05)
                continue
            latest_text = current_text
            if not before_state_text or current_text != before_state_text:
                return self._normalize_state(json.loads(current_text))
            time.sleep(0.05)
        if latest_text:
            return self._normalize_state(json.loads(latest_text))
        return self.read_state()

    def _normalize_state(self, state: Dict[str, Any]) -> Dict[str, Any]:
        if "grid_cards" not in state:
            state["grid_cards"] = state.get("grid_select_cards") or []
        if "hand_cards" not in state:
            state["hand_cards"] = state.get("hand_select_cards") or []
        actions = state.get("available_actions") or []
        state["action_names"] = [action.get("action") for action in actions if isinstance(action, dict) and action.get("action")]
        hand = state.get("hand") or []
        playable_hand = [card for card in hand if isinstance(card, dict) and card.get("playable")]
        state["playable_hand"] = playable_hand
        state["playable_hand_count"] = state.get("playable_hand_count", len(playable_hand))
        state["has_playable_cards"] = state.get("has_playable_cards", bool(playable_hand))
        rewards = state.get("room_rewards") or []
        state["reward_types"] = [reward.get("type") for reward in rewards if isinstance(reward, dict) and reward.get("type")]
        map_state = state.get("map") or {}
        if "map_available_nodes" not in state:
            state["map_available_nodes"] = map_state.get("available_nodes")
        return state

    def status(self) -> Dict[str, Any]:
        return {
            "runtime_dir": str(self.runtime_dir),
            "state_path": str(self.state_path),
            "state_exists": self.state_path.exists(),
            "command_path": str(self.command_path),
            "response_path": str(self.response_path),
        }

    def read_resource(self, uri: str) -> Dict[str, Any]:
        if uri == STATE_RESOURCE_URI:
            return self.read_state()
        if uri == RUNTIME_STATUS_RESOURCE_URI:
            return self.status()
        raise RuntimeError(f"Unknown resource URI: {uri}")

    def _atomic_write(self, path: Path, payload: Dict[str, Any]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        temp_path = path.with_suffix(path.suffix + ".tmp")
        temp_path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        os.replace(temp_path, path)


class SimpleMcpServer:
    def __init__(self, runtime: StsRuntime) -> None:
        self.runtime = runtime
        self.running = True
        self.protocol_version = LATEST_PROTOCOL_VERSION
        self.initialized = False

    def serve(self) -> None:
        while self.running:
            message = self._read_message()
            if message is None:
                return
            self._handle_message(message)

    def _handle_message(self, message: Dict[str, Any]) -> None:
        method = message.get("method")
        msg_id = message.get("id")
        params = message.get("params", {})
        if method == "initialize":
            requested_version = str((params or {}).get("protocolVersion") or "")
            self.protocol_version = self._negotiate_protocol_version(requested_version)
            self._send_result(
                msg_id,
                {
                    "protocolVersion": self.protocol_version,
                    "capabilities": {
                        "tools": {"listChanged": False},
                        "resources": {"subscribe": False, "listChanged": False},
                    },
                    "serverInfo": {"name": SERVER_NAME, "version": __version__},
                },
            )
            return
        if method == "notifications/initialized":
            self.initialized = True
            return
        if method == "ping":
            self._send_result(msg_id, {})
            return
        if method == "tools/list":
            self._send_result(msg_id, {"tools": self._tool_definitions()})
            return
        if method == "tools/call":
            self._handle_tool_call(msg_id, params)
            return
        if method == "resources/list":
            self._send_result(msg_id, {"resources": self._resource_definitions()})
            return
        if method == "resources/read":
            self._handle_resource_read(msg_id, params)
            return
        if method == "resources/templates/list":
            self._send_result(msg_id, {"resourceTemplates": []})
            return
        if method == "shutdown":
            self._send_result(msg_id, {})
            return
        if method == "exit":
            self.running = False
            return
        self._send_error(msg_id, -32601, f"Method not found: {method}")

    def _handle_tool_call(self, msg_id: Optional[int], params: Dict[str, Any]) -> None:
        name = params.get("name")
        arguments = dict(params.get("arguments") or {})
        try:
            if name == "get_game_state":
                self._tool_success(msg_id, self.runtime.read_state())
                return
            if name == "step_game":
                timeout_ms = int(arguments.pop("timeout_ms", 5000))
                self._tool_success(msg_id, self.runtime.send_command(arguments, timeout_ms=timeout_ms))
                return
            if name == "runtime_status":
                self._tool_success(msg_id, self.runtime.status())
                return
            self._tool_error(msg_id, f"Unknown tool: {name}")
        except Exception as exc:
            self._tool_error(msg_id, str(exc))

    def _handle_resource_read(self, msg_id: Optional[int], params: Dict[str, Any]) -> None:
        try:
            uri = str((params or {}).get("uri") or "")
            payload = self.runtime.read_resource(uri)
            self._send_result(
                msg_id,
                {
                    "contents": [
                        {
                            "uri": uri,
                            "mimeType": "application/json",
                            "text": json.dumps(payload, ensure_ascii=False, indent=2),
                        }
                    ]
                },
            )
        except Exception as exc:
            self._send_error(msg_id, -32000, str(exc))

    def _tool_success(self, msg_id: Optional[int], payload: Dict[str, Any]) -> None:
        self._send_result(
            msg_id,
            {
                "content": [{"type": "text", "text": json.dumps(payload, ensure_ascii=False, indent=2)}],
                "isError": False,
            },
        )

    def _tool_error(self, msg_id: Optional[int], message: str) -> None:
        self._send_result(
            msg_id,
            {
                "content": [{"type": "text", "text": message}],
                "isError": True,
            },
        )

    def _tool_definitions(self) -> List[Dict[str, Any]]:
        return [
            {
                "name": "get_game_state",
                "description": "Read the latest Slay the Spire state snapshot exported by the companion mod.",
                "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
            },
            {
                "name": "step_game",
                "description": "Ask the companion mod to execute exactly one high-level game action.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "action": {
                            "type": "string",
                            "enum": [
                                "ping",
                                "play_card",
                                "end_turn",
                                "use_potion",
                                "proceed",
                                "enter_boss",
                                "choose_main_menu_option",
                                "select_character",
                                "claim_room_reward",
                                "choose_reward",
                                "skip_reward",
                                "choose_boss_reward",
                                "choose_event_option",
                                "choose_campfire_option",
                                "open_treasure_chest",
                                "buy_shop_card",
                                "buy_shop_relic",
                                "buy_shop_potion",
                                "buy_shop_purge",
                                "choose_map_node",
                                "select_hand_card",
                                "select_grid_card",
                                "confirm",
                                "cancel",
                            ],
                        },
                        "card_uuid": {"type": "string"},
                        "card_index": {"type": "integer"},
                        "target_index": {"type": "integer"},
                        "reward_index": {"type": "integer"},
                        "option_index": {"type": "integer"},
                        "x": {"type": "integer"},
                        "y": {"type": "integer"},
                        "timeout_ms": {"type": "integer", "minimum": 100, "default": 5000},
                    },
                    "required": ["action"],
                    "additionalProperties": False,
                },
            },
            {
                "name": "runtime_status",
                "description": "Inspect where the bridge reads and writes runtime files.",
                "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
            },
        ]

    def _resource_definitions(self) -> List[Dict[str, Any]]:
        return [
            {
                "uri": STATE_RESOURCE_URI,
                "name": "Current game state",
                "description": "Latest state snapshot exported by the Slay the Spire bridge mod.",
                "mimeType": "application/json",
            },
            {
                "uri": RUNTIME_STATUS_RESOURCE_URI,
                "name": "Bridge runtime status",
                "description": "Paths and existence checks for the bridge runtime files.",
                "mimeType": "application/json",
            },
        ]

    def _read_message(self) -> Optional[Dict[str, Any]]:
        headers: Dict[str, str] = {}
        while True:
            line = sys.stdin.buffer.readline()
            if not line:
                return None
            if line in (b"\r\n", b"\n"):
                break
            decoded = line.decode("utf-8").strip()
            if ":" in decoded:
                key, value = decoded.split(":", 1)
                headers[key.strip().lower()] = value.strip()
        content_length = int(headers.get("content-length", "0"))
        body = sys.stdin.buffer.read(content_length)
        if not body:
            return None
        return json.loads(body.decode("utf-8"))

    def _send_result(self, msg_id: Optional[int], result: Dict[str, Any]) -> None:
        self._send_message({"jsonrpc": "2.0", "id": msg_id, "result": result})

    def _send_error(self, msg_id: Optional[int], code: int, message: str) -> None:
        self._send_message({"jsonrpc": "2.0", "id": msg_id, "error": {"code": code, "message": message}})

    def _send_message(self, payload: Dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        header = f"Content-Length: {len(body)}\r\n\r\n".encode("ascii")
        sys.stdout.buffer.write(header)
        sys.stdout.buffer.write(body)
        sys.stdout.buffer.flush()

    def _negotiate_protocol_version(self, requested_version: str) -> str:
        if requested_version in SUPPORTED_PROTOCOL_VERSIONS:
            return requested_version
        return LATEST_PROTOCOL_VERSION


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="sts-game-mcp",
        description="Start the Slay the Spire MCP bridge server over stdio.",
    )
    parser.add_argument(
        "runtime_dir",
        nargs="?",
        help="Optional runtime directory containing state.json / command.json / response.json.",
    )
    parser.add_argument(
        "--runtime-dir",
        dest="runtime_dir_flag",
        help="Runtime directory override. Takes precedence over the positional argument.",
    )
    return parser.parse_args(argv)


def resolve_runtime_dir(args: argparse.Namespace) -> Path:
    runtime_arg = args.runtime_dir_flag or args.runtime_dir
    if runtime_arg:
        return Path(runtime_arg).expanduser().resolve()
    env_value = os.environ.get("STS_MCP_RUNTIME_DIR")
    if env_value:
        return Path(env_value).expanduser().resolve()
    return (Path.cwd() / "runtime").resolve()


def main(argv: Optional[List[str]] = None) -> None:
    args = parse_args(argv)
    runtime_dir = resolve_runtime_dir(args)
    runtime = StsRuntime(runtime_dir)
    SimpleMcpServer(runtime).serve()



