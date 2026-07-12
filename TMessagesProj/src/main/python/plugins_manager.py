"""Historical exteragram-utils 0.1.x facade over the shared runtime."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from base_plugin import HookResult
from plugin_runtime.context import get_manager, set_manager
from plugin_runtime.manager import PluginManager
from plugin_runtime.metadata import read_metadata


class PluginsManager:
    INJECTED_MODULE_DICT: Any = {}

    @classmethod
    def validate_plugin_from_file(cls, file_path: str) -> dict[str, str]:
        return read_metadata(file_path).to_dict()

    @classmethod
    def load_plugin(cls, file_name: str, file_path: str | None = None) -> str | None:
        try:
            manager = get_manager()
            path = Path(file_path) if file_path is not None else manager.plugins_dir / file_name
            descriptor = manager.install(path)
            manager.set_enabled(descriptor["id"], True)
            return None
        except BaseException as exc:
            return str(exc)

    @classmethod
    def load_plugin_from_file(cls, file_path: str) -> str | None:
        return cls.load_plugin(Path(file_path).name, file_path)

    @classmethod
    def unload_plugin(cls, plugin_id: str, keep_entry: bool = False) -> bool:
        manager = get_manager()
        if plugin_id not in manager.records:
            return False
        manager.set_enabled(plugin_id, False)
        if not keep_entry:
            manager.records.pop(plugin_id, None)
        return True

    @classmethod
    def delete_plugin(cls, plugin_id: str) -> bool:
        return get_manager().delete_plugin(plugin_id)

    @classmethod
    def set_plugin_enabled(cls, plugin_id: str, enabled: bool) -> bool:
        get_manager().set_enabled(plugin_id, enabled)
        return True

    @classmethod
    def init(cls, plugins_directory: str):
        manager = PluginManager(
            plugins_directory,
            Path(plugins_directory) / ".runtime",
            app_version="12.8.1",
        )
        set_manager(manager)
        manager.discover()
        return None

    @classmethod
    def shutdown(cls) -> None:
        manager = get_manager(required=False)
        if manager is not None:
            manager.shutdown()

    @classmethod
    def cleanup_plugin(cls, plugin_id: str):
        return get_manager()._cleanup_plugin_registrations(plugin_id)

    @classmethod
    def load_settings(cls, plugin_id: str) -> list[Any] | None:
        try:
            return get_manager().create_settings(plugin_id)
        except BaseException:
            return None

    @classmethod
    def get_plugin_setting(cls, plugin_id: str, key: str, default: Any = None) -> Any:
        return get_manager().settings.get(plugin_id, key, default)

    @classmethod
    def set_plugin_setting(cls, plugin_id: str, key: str, value: Any):
        return get_manager().settings.set(plugin_id, key, value)

    @classmethod
    def execute_event(cls, event_type: str):
        return get_manager().invoke_app_event(event_type)

    @classmethod
    def execute_pre_request_hook(
        cls, interested_plugin_ids: list[str], request_name: str, account: int, request: Any
    ) -> HookResult:
        return get_manager().dispatch_pre_request(request_name, account, request)

    @classmethod
    def execute_post_request_hook(
        cls,
        interested_plugin_ids: list[str],
        request_name: str,
        account: int,
        response: Any,
        error: Any,
    ) -> HookResult:
        return get_manager().dispatch_post_request(request_name, account, response, error)

    @classmethod
    def execute_on_update_hook(
        cls, interested_plugin_ids: list[str], update_name: str, account: int, update: Any
    ) -> HookResult:
        return get_manager().dispatch_update(update_name, account, update)

    @classmethod
    def execute_on_updates_hook(
        cls, interested_plugin_ids: list[str], container_name: str, account: int, updates: Any
    ) -> HookResult:
        return get_manager().dispatch_updates(container_name, account, updates)

    @classmethod
    def execute_on_send_message_hook(
        cls, interested_plugin_ids: list[str], account: int, params: Any
    ) -> HookResult:
        return get_manager().dispatch_send(account, params)


__all__ = ["PluginsManager"]
