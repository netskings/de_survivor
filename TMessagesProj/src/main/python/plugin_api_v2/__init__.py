"""Additive v2 API backed by the same lifecycle and dispatcher core."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from base_plugin import HookResult, HookStrategy
from plugin_runtime.context import get_manager
from plugin_runtime.metadata import StaticPluginMetadata, read_metadata

API_VERSION = 2


@dataclass(frozen=True)
class PluginState:
    id: str
    enabled: bool
    initialized: bool
    error_message: str | None
    crash_count: int


def list_plugins() -> list[PluginState]:
    return [
        PluginState(
            item["id"],
            item["enabled"],
            item["initialized"],
            item.get("error_message"),
            item.get("crash_count", 0),
        )
        for item in get_manager().list_plugins()
    ]


def set_enabled(plugin_id: str, enabled: bool) -> PluginState:
    item = get_manager().set_enabled(plugin_id, enabled)
    return PluginState(
        item["id"], item["enabled"], item["initialized"],
        item.get("error_message"), item.get("crash_count", 0)
    )


__all__ = [
    "API_VERSION",
    "HookResult",
    "HookStrategy",
    "PluginState",
    "StaticPluginMetadata",
    "list_plugins",
    "read_metadata",
    "set_enabled",
]
