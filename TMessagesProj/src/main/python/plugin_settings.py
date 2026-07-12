"""Legacy settings module backed by the active shared runtime."""

from __future__ import annotations

from typing import Any

from plugin_runtime.context import get_manager
from plugin_runtime.storage import MemorySettingsBackend

_fallback = MemorySettingsBackend()


def _backend():
    manager = get_manager(required=False)
    return manager.settings if manager is not None else _fallback


def init(plugins_dir_path: str, all_shared_prefs):
    # Kept for binary/source compatibility. The Android owner configures the
    # shared backend during plugin_runtime.android_bridge.initialize.
    return None


def get_setting(plugin_id: str, key: str, default: Any) -> Any:
    return _backend().get(plugin_id, key, default)


def set_setting(plugin_id: str, key: str, value: Any):
    return _backend().set(plugin_id, key, value)


def clear_settings(plugin_id: str):
    return _backend().clear(plugin_id)


def get_all_settings(plugin_id: str) -> Any:
    return _backend().get_all(plugin_id)


def set_all_settings(plugin_id: str, settings: dict):
    return _backend().set_all(plugin_id, settings)
