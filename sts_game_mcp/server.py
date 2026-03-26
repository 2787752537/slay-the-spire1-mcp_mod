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

PROTOCOL_VERSION = "2024-11-05"
SERVER_NAME = "sts-game-mcp"


class StsRuntime:
    def __init__(self, runtime_dir: Path) -> None:
        self.runtime_dir = runtime_dir
        self.state_path = runtime_dir / "state.json"
        self.command_path = runtime_dir / "command.json"
        self.response_path = runtime_dir / "response.json"
        self.runtime_dir.mkdir(parents=True, exist_ok=True)

    def read_state(self) -> Dict[str, Any]:
        if not self.state_path.exists():
            raise RuntimeError(
                "State file not found. Start the game with the bridge mod first: "
                f"{self.state_path}"
            )
        return json.loads(self.state_path.read_text(encoding="utf-8"))

    def send_command(self, payload: Dict[str, Any], timeout_ms: int = 5000) -> Dict[str, Any]:
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
                    return response
            time.sleep(0.05)
        raise RuntimeError(f"Timed out waiting for game response after {timeout_ms} ms")

    def status(self) -> Dict[str, Any]:
        return {
            "runtime_dir": str(self.runtime_dir),
            "state_path": str(self.state_path),
            "state_exists": self.state_path.exists(),
            "command_path": str(self.command_path),
            "response_path": str(self.response_path),
        }

    def _atomic_write(self, path: Path, payload: Dict[str, Any]) -> None:
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
            self._send_result(
                msg_id,
                {
                    "protocolVersion": PROTOCOL_VERSION,
                    "capabilities": {"tools": {}},
                    "serverInfo": {"name": SERVER_NAME, "version": __version__},
                },
            )
            return
        if method == "notifications/initialized":
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
                                "proceed",
                                "claim_room_reward",
                                "choose_reward",
                                "skip_reward",
                                "choose_event_option",
                                "choose_campfire_option",
                                "choose_map_node",
                                "select_hand_card",
                                "select_grid_card",
                                "confirm",
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
