"""Configurable persistent state, settings, and append-only error journal."""

from __future__ import annotations

import json
import os
import tempfile
import threading
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Protocol


def _atomic_write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temp_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=str(path.parent)
    )
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
            json.dump(value, output, ensure_ascii=False, indent=2, sort_keys=True)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temp_name, path)
    except BaseException:
        try:
            os.unlink(temp_name)
        except OSError:
            pass
        raise


class StateBackend(Protocol):
    def get_global(self, key: str, default: Any = None) -> Any: ...

    def set_global(self, key: str, value: Any) -> None: ...

    def get_plugin(self, plugin_id: str) -> dict[str, Any]: ...

    def update_plugin(self, plugin_id: str, **values: Any) -> None: ...


class SettingsBackend(Protocol):
    def get(self, plugin_id: str, key: str, default: Any = None) -> Any: ...

    def set(self, plugin_id: str, key: str, value: Any) -> None: ...

    def clear(self, plugin_id: str) -> None: ...

    def get_all(self, plugin_id: str) -> dict[str, Any]: ...

    def set_all(self, plugin_id: str, settings: dict[str, Any]) -> None: ...


class MemoryStateBackend:
    def __init__(self) -> None:
        self._data: dict[str, Any] = {"global": {}, "plugins": {}}
        self._lock = threading.RLock()

    def get_global(self, key: str, default: Any = None) -> Any:
        with self._lock:
            return deepcopy(self._data["global"].get(key, default))

    def set_global(self, key: str, value: Any) -> None:
        with self._lock:
            self._data["global"][key] = deepcopy(value)

    def get_plugin(self, plugin_id: str) -> dict[str, Any]:
        with self._lock:
            return deepcopy(self._data["plugins"].get(plugin_id, {}))

    def update_plugin(self, plugin_id: str, **values: Any) -> None:
        with self._lock:
            self._data["plugins"].setdefault(plugin_id, {}).update(deepcopy(values))


class JsonStateBackend(MemoryStateBackend):
    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self._lock = threading.RLock()
        self._data = {"global": {}, "plugins": {}}
        if self.path.exists():
            try:
                with self.path.open("r", encoding="utf-8") as source:
                    loaded = json.load(source)
                if isinstance(loaded, dict):
                    self._data["global"] = dict(loaded.get("global", {}))
                    self._data["plugins"] = dict(loaded.get("plugins", {}))
            except (OSError, ValueError, TypeError):
                # A corrupt state file must not prevent starting without plugins.
                self._data["global"]["safe_mode"] = True

    def _save(self) -> None:
        _atomic_write_json(self.path, self._data)

    def set_global(self, key: str, value: Any) -> None:
        with self._lock:
            self._data["global"][key] = deepcopy(value)
            self._save()

    def update_plugin(self, plugin_id: str, **values: Any) -> None:
        with self._lock:
            self._data["plugins"].setdefault(plugin_id, {}).update(deepcopy(values))
            self._save()


class MemorySettingsBackend:
    def __init__(self) -> None:
        self._data: dict[str, dict[str, Any]] = {}
        self._lock = threading.RLock()

    @staticmethod
    def _validate(settings: dict[str, Any]) -> None:
        # This mirrors SharedPreferences' persistability boundary while retaining
        # list/dict values accepted by existing Python plugins.
        json.dumps(settings, ensure_ascii=False, allow_nan=False)

    def get(self, plugin_id: str, key: str, default: Any = None) -> Any:
        with self._lock:
            return deepcopy(self._data.get(plugin_id, {}).get(key, default))

    def set(self, plugin_id: str, key: str, value: Any) -> None:
        with self._lock:
            candidate = deepcopy(self._data.get(plugin_id, {}))
            candidate[key] = value
            self._validate(candidate)
            self._data[plugin_id] = deepcopy(candidate)

    def clear(self, plugin_id: str) -> None:
        with self._lock:
            self._data.pop(plugin_id, None)

    def get_all(self, plugin_id: str) -> dict[str, Any]:
        with self._lock:
            return deepcopy(self._data.get(plugin_id, {}))

    def set_all(self, plugin_id: str, settings: dict[str, Any]) -> None:
        if not isinstance(settings, dict):
            raise TypeError("settings must be a dict")
        self._validate(settings)
        with self._lock:
            self._data[plugin_id] = deepcopy(settings)


class JsonSettingsBackend(MemorySettingsBackend):
    def __init__(self, directory: str | Path) -> None:
        super().__init__()
        self.directory = Path(directory)

    def _path(self, plugin_id: str) -> Path:
        return self.directory / f"{plugin_id}.json"

    def _load(self, plugin_id: str) -> dict[str, Any]:
        path = self._path(plugin_id)
        if not path.exists():
            return {}
        try:
            with path.open("r", encoding="utf-8") as source:
                value = json.load(source)
            return value if isinstance(value, dict) else {}
        except (OSError, ValueError, TypeError):
            return {}

    def get(self, plugin_id: str, key: str, default: Any = None) -> Any:
        with self._lock:
            return deepcopy(self._load(plugin_id).get(key, default))

    def set(self, plugin_id: str, key: str, value: Any) -> None:
        with self._lock:
            settings = self._load(plugin_id)
            settings[key] = value
            self._validate(settings)
            _atomic_write_json(self._path(plugin_id), settings)

    def clear(self, plugin_id: str) -> None:
        with self._lock:
            try:
                self._path(plugin_id).unlink()
            except FileNotFoundError:
                pass

    def get_all(self, plugin_id: str) -> dict[str, Any]:
        with self._lock:
            return deepcopy(self._load(plugin_id))

    def set_all(self, plugin_id: str, settings: dict[str, Any]) -> None:
        if not isinstance(settings, dict):
            raise TypeError("settings must be a dict")
        self._validate(settings)
        with self._lock:
            _atomic_write_json(self._path(plugin_id), settings)


class ErrorJournal:
    def record(
        self,
        plugin_id: str,
        callback: str,
        exception: BaseException,
        traceback_text: str,
        failure_count: int,
        auto_disabled: bool,
    ) -> None:
        raise NotImplementedError


class MemoryErrorJournal(ErrorJournal):
    def __init__(self) -> None:
        self.entries: list[dict[str, Any]] = []
        self._lock = threading.RLock()

    def record(
        self,
        plugin_id: str,
        callback: str,
        exception: BaseException,
        traceback_text: str,
        failure_count: int,
        auto_disabled: bool,
    ) -> None:
        entry = _error_entry(
            plugin_id,
            callback,
            exception,
            traceback_text,
            failure_count,
            auto_disabled,
        )
        with self._lock:
            self.entries.append(entry)


class JsonLinesErrorJournal(ErrorJournal):
    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self._lock = threading.RLock()

    def record(
        self,
        plugin_id: str,
        callback: str,
        exception: BaseException,
        traceback_text: str,
        failure_count: int,
        auto_disabled: bool,
    ) -> None:
        entry = _error_entry(
            plugin_id,
            callback,
            exception,
            traceback_text,
            failure_count,
            auto_disabled,
        )
        self.path.parent.mkdir(parents=True, exist_ok=True)
        line = json.dumps(entry, ensure_ascii=False, sort_keys=True) + "\n"
        with self._lock, self.path.open("a", encoding="utf-8", newline="\n") as output:
            output.write(line)
            output.flush()


def _error_entry(
    plugin_id: str,
    callback: str,
    exception: BaseException,
    traceback_text: str,
    failure_count: int,
    auto_disabled: bool,
) -> dict[str, Any]:
    return {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "plugin_id": plugin_id,
        "callback": callback,
        "exception_type": type(exception).__name__,
        "message": str(exception),
        "traceback": traceback_text,
        "failure_count": failure_count,
        "auto_disabled": auto_disabled,
    }
