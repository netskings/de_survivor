from __future__ import annotations

import enum
import threading

from base_plugin import BasePlugin


class DebuggerPlatform(enum.Enum):
    PyCharm = "pycharm"
    VSCode = "vscode"


class Callback:
    def __init__(self, fn) -> None:
        self.fn = fn

    def run(self, arg) -> None:
        self.fn(arg)


class DebuggerEventListener(BasePlugin):
    def __init__(self, host: str, port: int, platform: DebuggerPlatform) -> None:
        super().__init__()
        self.host, self.port, self.platform = host, port, platform


class DevServer:
    DEFAULT_HOST = "127.0.0.1"
    DEFAULT_PORT = 5678
    SOCKET_TIMEOUT = 10
    BUFFER_SIZE = 8192
    PYCHARM_DEBUGGER_DIR = "pycharm-debugger"
    _thread = None

    @classmethod
    def start_server(cls, host: str = None, port: int = None) -> threading.Thread:
        thread = threading.Thread(target=lambda: None, name="PluginDevServer", daemon=True)
        thread.start()
        cls._thread = thread
        return thread

    @classmethod
    def stop_server(cls) -> bool:
        was_running = cls._thread is not None
        cls._thread = None
        return was_running

    @classmethod
    def setup_remote_debugging(cls, host: str, port: int, platform: DebuggerPlatform) -> bool:
        return False

    @classmethod
    def stop_remote_debugging(cls, platform: DebuggerPlatform) -> bool:
        return False
