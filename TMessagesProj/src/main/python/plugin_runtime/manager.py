"""Plugin discovery, lifecycle, hook dispatch, isolation, and safe-mode policy."""

from __future__ import annotations

import ast
import hashlib
import logging
import os
import shutil
import sys
import tempfile
import threading
import tokenize
import traceback
import types
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

from base_plugin import AppEvent, BasePlugin, HookResult, HookStrategy, PluginError

from . import services
from .context import enter_plugin, leave_plugin
from .errors import CompatibilityError, MetadataError, PluginLoadError
from .metadata import StaticPluginMetadata, assert_compatible, read_metadata
from .requirements import PurePythonRequirementInstaller, install_pure_wheel_atomic
from .storage import (
    ErrorJournal,
    JsonLinesErrorJournal,
    JsonSettingsBackend,
    JsonStateBackend,
    SettingsBackend,
    StateBackend,
)

LOGGER = logging.getLogger("telegram.plugins")


@dataclass(frozen=True)
class HookRegistration:
    plugin_id: str
    name: str | None
    match_substring: bool
    priority: int
    order: int


@dataclass
class PluginRecord:
    metadata: StaticPluginMetadata
    path: Path
    instance: BasePlugin | None = None
    module_key: str | None = None
    enabled: bool = False
    initialized: bool = False
    error_message: str | None = None
    load_order: int = 0
    settings_objects: list[Any] = field(default_factory=list)
    settings_registry: dict[str, Any] = field(default_factory=dict)


class PluginManager:
    def __init__(
        self,
        plugins_dir: str | Path,
        state_dir: str | Path,
        app_version: str,
        sdk_version: str = "1.4.3.10",
        *,
        state_backend: StateBackend | None = None,
        settings_backend: SettingsBackend | None = None,
        error_journal: ErrorJournal | None = None,
        failure_threshold: int = 3,
    ) -> None:
        if failure_threshold < 1:
            raise ValueError("failure_threshold must be at least 1")
        self.plugins_dir = Path(plugins_dir).resolve()
        self.state_dir = Path(state_dir).resolve()
        self.dependencies_dir = self.state_dir / "python-dependencies"
        self.app_version = app_version
        self.sdk_version = sdk_version
        self.failure_threshold = failure_threshold
        self.plugins_dir.mkdir(parents=True, exist_ok=True)
        self.state_dir.mkdir(parents=True, exist_ok=True)
        self.dependencies_dir.mkdir(parents=True, exist_ok=True)
        self.state = state_backend or JsonStateBackend(self.state_dir / "plugins-state.json")
        self.settings = settings_backend or JsonSettingsBackend(self.state_dir / "settings")
        self.error_journal = error_journal or JsonLinesErrorJournal(
            self.state_dir / "plugin-errors.jsonl"
        )
        self.requirement_installer = PurePythonRequirementInstaller(self.dependencies_dir)
        self.records: dict[str, PluginRecord] = {}
        self.discovery_errors: list[dict[str, str]] = []
        self._named_hooks: list[HookRegistration] = []
        self._send_hooks: list[HookRegistration] = []
        self._menus: dict[str, tuple[str, Any]] = {}
        self._counter = 0
        self._load_counter = 0
        self._lock = threading.RLock()
        if self.state.get_global("startup_loading", False):
            # A prior process died while third-party code was loading. Persist
            # safe mode before doing any discovery or execution this launch.
            self.state.set_global("safe_mode", True)
            self.state.set_global("startup_loading", False)
        self._activate_dependency_paths()

    @property
    def safe_mode(self) -> bool:
        return bool(self.state.get_global("safe_mode", False))

    def set_safe_mode(self, enabled: bool) -> None:
        with self._lock:
            self.state.set_global("safe_mode", bool(enabled))
            if enabled:
                for plugin_id in list(self.records):
                    record = self.records[plugin_id]
                    if record.instance is not None:
                        self._unload(record, persist_enabled=False)

    def _activate_dependency_paths(self) -> None:
        for path in self.requirement_installer.active_paths():
            if str(path) not in sys.path:
                sys.path.insert(0, str(path))

    def discover(self) -> list[PluginRecord]:
        with self._lock:
            loaded_records = {
                plugin_id: record
                for plugin_id, record in self.records.items()
                if record.instance is not None
            }
            discovered: dict[str, PluginRecord] = dict(loaded_records)
            self.discovery_errors = []
            candidates = sorted(
                (
                    path
                    for path in self.plugins_dir.iterdir()
                    if path.is_file() and path.suffix.lower() in {".py", ".plugin"}
                ),
                key=lambda path: path.name.lower(),
            )
            for path in candidates:
                try:
                    metadata = read_metadata(path)
                    assert_compatible(metadata, self.app_version, self.sdk_version)
                except (MetadataError, CompatibilityError) as exc:
                    self.discovery_errors.append({"path": str(path), "error": str(exc)})
                    continue
                existing = discovered.get(metadata.id)
                if existing is not None and existing.path != path:
                    self.discovery_errors.append(
                        {
                            "path": str(path),
                            "error": f"duplicate plugin id {metadata.id!r} also used by {existing.path}",
                        }
                    )
                    continue
                if existing is None:
                    saved = self.state.get_plugin(metadata.id)
                    discovered[metadata.id] = PluginRecord(
                        metadata=metadata,
                        path=path,
                        enabled=bool(saved.get("enabled", False)),
                        error_message=saved.get("error_message"),
                    )
                else:
                    existing.metadata = metadata
                    existing.path = path
            self.records = discovered
            return list(discovered.values())

    def install(self, source_path: str | Path) -> dict[str, Any]:
        source = Path(source_path).resolve()
        metadata = read_metadata(source)
        assert_compatible(metadata, self.app_version, self.sdk_version)
        dependency_paths = self.requirement_installer.install_for_plugin(
            metadata.id, metadata.requirements
        )
        for path in dependency_paths:
            if str(path) not in sys.path:
                sys.path.insert(0, str(path))
        suffix = source.suffix.lower()
        destination = self.plugins_dir / f"{metadata.id}{suffix}"
        alternate_suffix = ".plugin" if suffix == ".py" else ".py"
        alternate = self.plugins_dir / f"{metadata.id}{alternate_suffix}"
        if alternate.exists() and alternate.resolve() != source:
            raise PluginError(
                f"plugin {metadata.id!r} is already installed as {alternate.name}", metadata.id
            )
        if source != destination.resolve():
            descriptor, temp_name = tempfile.mkstemp(
                prefix=f".{destination.stem}.", suffix=suffix, dir=str(self.plugins_dir)
            )
            os.close(descriptor)
            try:
                shutil.copyfile(source, temp_name)
                # Re-parse the exact copied bytes before the atomic publication.
                copied_metadata = read_metadata(temp_name)
                if copied_metadata != metadata:
                    raise PluginError("plugin changed while it was being installed", metadata.id)
                os.replace(temp_name, destination)
            finally:
                try:
                    os.unlink(temp_name)
                except FileNotFoundError:
                    pass
        with self._lock:
            existing = self.records.get(metadata.id)
            if existing is not None and existing.instance is not None:
                self._unload(existing, persist_enabled=False)
            self.state.update_plugin(
                metadata.id,
                enabled=False,
                auto_disabled=False,
                crash_count=0,
                error_message=None,
            )
            self.discover()
        return self.describe(metadata.id)

    def install_wheels(self, wheel_paths: Iterable[str | Path]) -> list[str]:
        installed = [
            install_pure_wheel_atomic(wheel_path, self.dependencies_dir)
            for wheel_path in wheel_paths
        ]
        for path in installed:
            if str(path) not in sys.path:
                sys.path.insert(0, str(path))
        return [str(path) for path in installed]

    def load_enabled_plugins(self) -> list[dict[str, Any]]:
        self.discover()
        if self.safe_mode:
            return self.list_plugins()
        self.state.set_global("startup_loading", True)
        try:
            for record in list(self.records.values()):
                saved = self.state.get_plugin(record.metadata.id)
                record.enabled = bool(saved.get("enabled", False))
                if record.enabled and not saved.get("auto_disabled", False):
                    self._load(record)
        finally:
            self.state.set_global("startup_loading", False)
        return self.list_plugins()

    def set_enabled(self, plugin_id: str, enabled: bool) -> dict[str, Any]:
        with self._lock:
            if plugin_id not in self.records:
                self.discover()
            record = self.records.get(plugin_id)
            if record is None:
                raise PluginError("plugin not found", plugin_id)
            if enabled:
                self.state.update_plugin(
                    plugin_id,
                    enabled=True,
                    auto_disabled=False,
                    crash_count=0,
                    error_message=None,
                )
                record.enabled = True
                record.error_message = None
                if not self.safe_mode and record.instance is None:
                    self._load(record)
            else:
                if record.instance is not None:
                    self._unload(record, persist_enabled=False)
                record.enabled = False
                self.state.update_plugin(plugin_id, enabled=False, auto_disabled=False)
            return self.describe(plugin_id)

    @staticmethod
    def _class_names(source: str, path: Path) -> list[str]:
        tree = ast.parse(source, filename=str(path))
        return [node.name for node in tree.body if isinstance(node, ast.ClassDef)]

    def _load(self, record: PluginRecord) -> bool:
        if record.instance is not None:
            return True
        module_key: str | None = None
        try:
            dependency_paths = self.requirement_installer.install_for_plugin(
                record.metadata.id, record.metadata.requirements
            )
            for dependency_path in dependency_paths:
                if str(dependency_path) not in sys.path:
                    sys.path.insert(0, str(dependency_path))
            with tokenize.open(record.path) as source_file:
                source = source_file.read()
            class_names = self._class_names(source, record.path)
            digest = hashlib.sha256(str(record.path).encode("utf-8")).hexdigest()[:12]
            module_key = f"_telegram_legacy_plugin_{record.metadata.id}_{digest}"
            module = types.ModuleType(module_key)
            module.__file__ = str(record.path)
            module.__package__ = None
            module.__loader__ = None
            sys.modules[module_key] = module
            if str(self.plugins_dir) not in sys.path:
                sys.path.insert(0, str(self.plugins_dir))
            token = enter_plugin(record.metadata.id, self)
            try:
                code = compile(source, str(record.path), "exec", dont_inherit=True)
                exec(code, module.__dict__, module.__dict__)
            finally:
                leave_plugin(token)
            classes = []
            for class_name in class_names:
                candidate = module.__dict__.get(class_name)
                if (
                    isinstance(candidate, type)
                    and candidate is not BasePlugin
                    and issubclass(candidate, BasePlugin)
                ):
                    classes.append(candidate)
            if len(classes) != 1:
                raise PluginLoadError(
                    f"expected exactly one BasePlugin subclass, found {len(classes)}"
                )
            plugin_class = classes[0]
            # Legacy plugins commonly create listeners, request delegates and
            # SimpleSettingFactory objects in __init__. Give that constructor
            # the same owner context as module execution/on_plugin_load, and
            # publish metadata on the class first so get_setting/log can be
            # used before BasePlugin._plugin_manager is attached.
            plugin_class.id = record.metadata.id
            plugin_class.name = record.metadata.name
            plugin_class.description = record.metadata.description
            plugin_class.author = record.metadata.author
            plugin_class.version = record.metadata.version
            plugin_class.icon = record.metadata.icon
            plugin_class.min_version = record.metadata.min_version or ""
            plugin_class.requirements = list(record.metadata.requirements)
            token = enter_plugin(record.metadata.id, self)
            try:
                instance = plugin_class()
            finally:
                leave_plugin(token)
            if not isinstance(instance, BasePlugin):
                raise PluginLoadError("plugin class did not create a BasePlugin instance")
            instance._plugin_manager = self
            instance.id = record.metadata.id
            instance.name = record.metadata.name
            instance.description = record.metadata.description
            instance.author = record.metadata.author
            instance.version = record.metadata.version
            instance.icon = record.metadata.icon
            instance.min_version = record.metadata.min_version or ""
            instance.requirements = list(record.metadata.requirements)
            instance.enabled = True
            instance.initialized = False
            instance.error_message = None
            record.instance = instance
            record.module_key = module_key
            record.enabled = True
            self._load_counter += 1
            record.load_order = self._load_counter
            ok, _ = self._invoke(record, "on_plugin_load", validate_hook=False)
            if not ok:
                self._cleanup_plugin_registrations(record.metadata.id)
                record.instance = None
                record.initialized = False
                instance.enabled = False
                sys.modules.pop(module_key, None)
                return False
            instance.initialized = True
            record.initialized = True
            record.error_message = None
            self.state.update_plugin(
                record.metadata.id, enabled=True, error_message=None
            )
            return True
        except BaseException as exc:
            if module_key:
                sys.modules.pop(module_key, None)
            record.instance = None
            record.initialized = False
            self._record_failure(record, "plugin_load", exc)
            return False

    def _unload(self, record: PluginRecord, *, persist_enabled: bool) -> None:
        instance = record.instance
        if instance is not None:
            self._invoke(record, "on_plugin_unload", validate_hook=False)
            instance.enabled = False
            instance.initialized = False
        self._cleanup_plugin_registrations(record.metadata.id)
        if record.module_key:
            sys.modules.pop(record.module_key, None)
        record.instance = None
        record.module_key = None
        record.initialized = False
        record.settings_objects.clear()
        record.settings_registry.clear()
        if persist_enabled:
            self.state.update_plugin(record.metadata.id, enabled=False)

    def shutdown(self) -> None:
        with self._lock:
            for record in sorted(
                (record for record in self.records.values() if record.instance is not None),
                key=lambda item: item.load_order,
                reverse=True,
            ):
                self._unload(record, persist_enabled=False)
            self.state.set_global("startup_loading", False)

    def delete_plugin(self, plugin_id: str) -> bool:
        with self._lock:
            record = self.records.get(plugin_id)
            if record is None:
                self.discover()
                record = self.records.get(plugin_id)
            if record is None:
                return False
            if record.instance is not None:
                self._unload(record, persist_enabled=True)
            try:
                record.path.unlink()
            except FileNotFoundError:
                pass
            self.settings.clear(plugin_id)
            self.state.update_plugin(
                plugin_id,
                enabled=False,
                auto_disabled=False,
                crash_count=0,
                error_message=None,
            )
            self.requirement_installer.unregister_plugin(plugin_id)
            self.records.pop(plugin_id, None)
            return True

    def _invoke(
        self,
        record: PluginRecord,
        callback: str,
        *args,
        validate_hook: bool,
    ) -> tuple[bool, Any]:
        # Chaquopy can release the GIL while a callback invokes Java. Keep the
        # same re-entrant runtime lock across that boundary so another thread
        # cannot unload or replace this instance mid-callback.
        with self._lock:
            instance = record.instance
            if instance is None:
                return False, None
            token = enter_plugin(record.metadata.id, self)
            try:
                result = getattr(instance, callback)(*args)
                if validate_hook:
                    if result is None:
                        result = HookResult()
                    if not isinstance(result, HookResult):
                        raise TypeError(
                            f"{callback} must return HookResult, got {type(result).__name__}"
                        )
                    if not isinstance(result.strategy, HookStrategy):
                        raise TypeError("HookResult.strategy must be HookStrategy")
                return True, result
            except BaseException as exc:
                self._record_failure(record, callback, exc)
                return False, None
            finally:
                leave_plugin(token)

    def _record_failure(
        self, record: PluginRecord, callback: str, exception: BaseException
    ) -> None:
        plugin_id = record.metadata.id
        saved = self.state.get_plugin(plugin_id)
        crash_count = int(saved.get("crash_count", 0)) + 1
        auto_disabled = crash_count >= self.failure_threshold
        message = f"{type(exception).__name__}: {exception}"
        record.error_message = message
        if record.instance is not None:
            record.instance.error_message = message
        if auto_disabled:
            record.enabled = False
            record.initialized = False
            if record.instance is not None:
                record.instance.enabled = False
                record.instance.initialized = False
            self._cleanup_plugin_registrations(plugin_id)
            if record.module_key:
                sys.modules.pop(record.module_key, None)
            record.instance = None
            record.module_key = None
            record.settings_objects.clear()
            record.settings_registry.clear()
        self.state.update_plugin(
            plugin_id,
            crash_count=crash_count,
            auto_disabled=auto_disabled,
            enabled=False if auto_disabled else bool(saved.get("enabled", record.enabled)),
            error_message=message,
        )
        traceback_text = "".join(
            traceback.format_exception(type(exception), exception, exception.__traceback__)
        )
        self.error_journal.record(
            plugin_id,
            callback,
            exception,
            traceback_text,
            crash_count,
            auto_disabled,
        )
        LOGGER.error("plugin %s failed in %s: %s", plugin_id, callback, message)
        if auto_disabled:
            self.reload_settings(plugin_id)

    def add_hook(
        self, plugin: BasePlugin, name: str, match_substring: bool, priority: int
    ):
        if not isinstance(name, str) or not name:
            raise ValueError("hook name must be a non-empty string")
        registration = self._new_registration(
            plugin.id, name, bool(match_substring), int(priority)
        )
        if not any(
            item.plugin_id == registration.plugin_id
            and item.name == registration.name
            and item.match_substring == registration.match_substring
            for item in self._named_hooks
        ):
            self._named_hooks.append(registration)
        return None

    def add_send_hook(self, plugin: BasePlugin, priority: int):
        registration = self._new_registration(plugin.id, None, False, int(priority))
        if not any(item.plugin_id == plugin.id for item in self._send_hooks):
            self._send_hooks.append(registration)
        return None

    def remove_hook(self, plugin: BasePlugin, name: str):
        before = len(self._named_hooks)
        self._named_hooks = [
            item
            for item in self._named_hooks
            if not (item.plugin_id == plugin.id and item.name == name)
        ]
        return before != len(self._named_hooks)

    def _new_registration(
        self, plugin_id: str, name: str | None, match_substring: bool, priority: int
    ) -> HookRegistration:
        self._counter += 1
        return HookRegistration(plugin_id, name, match_substring, priority, self._counter)

    def _cleanup_plugin_registrations(self, plugin_id: str) -> None:
        self._named_hooks = [item for item in self._named_hooks if item.plugin_id != plugin_id]
        self._send_hooks = [item for item in self._send_hooks if item.plugin_id != plugin_id]
        self._cleanup_plugin_menu_items(plugin_id)
        try:
            from com.exteragram.messenger.plugins.models import SimpleSettingFactoryAdapter

            SimpleSettingFactoryAdapter.disposePlugin(plugin_id)
        except (ImportError, ModuleNotFoundError):
            pass
        except BaseException:
            LOGGER.exception("failed to dispose setting factories for %s", plugin_id)

    def _cleanup_plugin_menu_items(self, plugin_id: str) -> None:
        self._menus = {
            item_id: value
            for item_id, value in self._menus.items()
            if value[0] != plugin_id
        }

    @staticmethod
    def _sort_hooks(items: Iterable[HookRegistration]) -> list[HookRegistration]:
        return sorted(items, key=lambda item: (-item.priority, item.order))

    def _matching_named_hooks(self, event_name: str) -> list[HookRegistration]:
        return self._sort_hooks(
            item
            for item in self._named_hooks
            if item.name == event_name
            or (item.match_substring and item.name is not None and item.name in event_name)
        )

    def _dispatch_single(
        self,
        registrations: Iterable[HookRegistration],
        callback: str,
        callback_prefix_args: tuple[Any, ...],
        value: Any,
        result_field: str,
    ) -> HookResult:
        changed = False
        current = value
        for registration in self._sort_hooks(registrations):
            record = self.records.get(registration.plugin_id)
            if (
                record is None
                or record.instance is None
                or not record.enabled
                or not record.initialized
            ):
                continue
            ok, result = self._invoke(
                record,
                callback,
                *callback_prefix_args,
                current,
                validate_hook=True,
            )
            if not ok:
                continue
            if result.strategy == HookStrategy.DEFAULT:
                continue
            if result.strategy == HookStrategy.CANCEL:
                return HookResult(strategy=HookStrategy.CANCEL, **{result_field: current})
            current = getattr(result, result_field)
            changed = True
            if result.strategy == HookStrategy.MODIFY_FINAL:
                return HookResult(
                    strategy=HookStrategy.MODIFY_FINAL, **{result_field: current}
                )
        return HookResult(
            strategy=HookStrategy.MODIFY if changed else HookStrategy.DEFAULT,
            **{result_field: current},
        )

    def dispatch_send(self, account: int, params: Any) -> HookResult:
        return self._dispatch_single(
            self._send_hooks, "on_send_message_hook", (account,), params, "params"
        )

    def dispatch_pre_request(self, name: str, account: int, request: Any) -> HookResult:
        return self._dispatch_single(
            self._matching_named_hooks(name),
            "pre_request_hook",
            (name, account),
            request,
            "request",
        )

    def dispatch_update(self, name: str, account: int, update: Any) -> HookResult:
        return self._dispatch_single(
            self._matching_named_hooks(name),
            "on_update_hook",
            (name, account),
            update,
            "update",
        )

    def dispatch_updates(self, name: str, account: int, updates: Any) -> HookResult:
        return self._dispatch_single(
            self._matching_named_hooks(name),
            "on_updates_hook",
            (name, account),
            updates,
            "updates",
        )

    def dispatch_post_request(
        self, name: str, account: int, response: Any, error: Any
    ) -> HookResult:
        changed = False
        current_response = response
        current_error = error
        for registration in self._matching_named_hooks(name):
            record = self.records.get(registration.plugin_id)
            if (
                record is None
                or record.instance is None
                or not record.enabled
                or not record.initialized
            ):
                continue
            ok, result = self._invoke(
                record,
                "post_request_hook",
                name,
                account,
                current_response,
                current_error,
                validate_hook=True,
            )
            if not ok:
                continue
            if result.strategy == HookStrategy.DEFAULT:
                continue
            if result.strategy == HookStrategy.CANCEL:
                return HookResult(
                    strategy=HookStrategy.CANCEL,
                    response=current_response,
                    error=current_error,
                )
            current_response, current_error = result.response, result.error
            changed = True
            if result.strategy == HookStrategy.MODIFY_FINAL:
                return HookResult(
                    strategy=HookStrategy.MODIFY_FINAL,
                    response=current_response,
                    error=current_error,
                )
        return HookResult(
            strategy=HookStrategy.MODIFY if changed else HookStrategy.DEFAULT,
            response=current_response,
            error=current_error,
        )

    def invoke_app_event(self, event: str | AppEvent) -> None:
        if isinstance(event, str):
            try:
                event = AppEvent[event.upper()]
            except KeyError as exc:
                raise ValueError(f"unknown app event {event!r}") from exc
        if not isinstance(event, AppEvent):
            raise TypeError("event must be AppEvent or its member name")
        for record in sorted(self.records.values(), key=lambda item: item.load_order):
            if record.instance is not None and record.enabled and record.initialized:
                self._invoke(record, "on_app_event", event, validate_hook=False)

    def create_settings(self, plugin_id: str) -> list[Any]:
        record = self.records.get(plugin_id)
        if record is None or record.instance is None:
            raise PluginError("plugin is not loaded", plugin_id)
        ok, result = self._invoke(record, "create_settings", validate_hook=False)
        if not ok:
            return []
        if not isinstance(result, list):
            self._record_failure(
                record,
                "create_settings",
                TypeError(f"create_settings must return list, got {type(result).__name__}"),
            )
            return []
        record.settings_objects = result
        record.settings_registry = {str(index): item for index, item in enumerate(result)}
        return result

    @staticmethod
    def _setting_kind(setting: Any) -> str:
        kind = getattr(setting, "type", None)
        if isinstance(kind, str):
            return kind
        return type(setting).__name__.lower()

    def _serialize_setting(
        self, plugin_id: str, token: str, setting: Any
    ) -> dict[str, Any]:
        descriptor: dict[str, Any] = {
            "kind": self._setting_kind(setting),
            "index": int(token) if token.isdigit() else token,
            "has_subpage": callable(getattr(setting, "create_sub_fragment", None)),
            "has_click": callable(getattr(setting, "on_click", None)),
            "has_long_click": callable(getattr(setting, "on_long_click", None)),
        }
        for field_name in (
            "key",
            "text",
            "default",
            "subtext",
            "icon",
            "items",
            "accent",
            "red",
            "hint",
            "multiline",
            "max_length",
            "mask",
            "link_alias",
        ):
            if hasattr(setting, field_name):
                value = getattr(setting, field_name)
                if value is not None and isinstance(
                    value, (str, int, float, bool, list, tuple)
                ):
                    if field_name == "items" and isinstance(value, (list, tuple)):
                        descriptor[field_name] = [str(item) for item in value]
                    else:
                        descriptor[field_name] = (
                            list(value) if isinstance(value, tuple) else value
                        )
        key = getattr(setting, "key", None)
        if isinstance(key, str):
            descriptor["value"] = self.settings.get(
                plugin_id, key, getattr(setting, "default", None)
            )
        if descriptor["kind"] == "custom":
            for field_name in ("item", "view", "factory", "factory_args"):
                value = getattr(setting, field_name, None)
                if value is not None:
                    descriptor[field_name] = value
        return descriptor

    def serialize_settings(self, plugin_id: str) -> list[dict[str, Any]]:
        objects = self.create_settings(plugin_id)
        record = self.records[plugin_id]
        record.settings_registry = {str(index): item for index, item in enumerate(objects)}
        result = []
        for index, item in enumerate(objects):
            ok, descriptor = self._invoke_setting_callable(
                record,
                "serialize",
                self._serialize_setting,
                plugin_id,
                str(index),
                item,
            )
            if ok:
                result.append(descriptor)
            if record.instance is None:
                break
        return result

    def serialize_sub_settings(
        self, plugin_id: str, index: int | str
    ) -> list[dict[str, Any]]:
        record = self.records.get(plugin_id)
        if record is None or record.instance is None:
            raise PluginError("plugin is not loaded", plugin_id)
        token = str(index)
        setting = record.settings_registry.get(token)
        if setting is None:
            raise PluginError(f"unknown setting index {index!r}", plugin_id)
        callback = getattr(setting, "create_sub_fragment", None)
        if not callable(callback):
            return []
        ok, objects = self._invoke_setting_callable(
            record, "create_sub_fragment", callback
        )
        if not ok:
            return []
        if not isinstance(objects, list):
            self._record_failure(
                record,
                "setting.create_sub_fragment",
                TypeError(
                    f"create_sub_fragment must return list, got {type(objects).__name__}"
                ),
            )
            return []
        result = []
        for child_index, item in enumerate(objects):
            child_token = f"{token}/{child_index}"
            record.settings_registry[child_token] = item
            ok, descriptor = self._invoke_setting_callable(
                record,
                "serialize_sub_setting",
                self._serialize_setting,
                plugin_id,
                child_token,
                item,
            )
            if ok:
                result.append(descriptor)
            if record.instance is None:
                break
        return result

    def setting_changed(
        self, plugin_id: str, index: int | str, value: Any, reload_settings: bool = False
    ) -> bool:
        record = self.records.get(plugin_id)
        if record is None or record.instance is None:
            raise PluginError("plugin is not loaded", plugin_id)
        setting = record.settings_registry.get(str(index))
        if setting is None:
            raise PluginError(f"unknown setting index {index!r}", plugin_id)
        key = getattr(setting, "key", None)
        if not isinstance(key, str):
            return False
        self.settings.set(plugin_id, key, value)
        callback = getattr(setting, "on_change", None)
        callback_ok = True
        if callable(callback):
            callback_ok, _ = self._invoke_setting_callable(
                record, "on_change", callback, value
            )
        if reload_settings:
            self.reload_settings(plugin_id)
        return callback_ok

    def setting_clicked(
        self,
        plugin_id: str,
        index: int | str,
        kind: str = "click",
        view: Any = None,
        item: Any = None,
    ) -> bool:
        record = self.records.get(plugin_id)
        if record is None or record.instance is None:
            raise PluginError("plugin is not loaded", plugin_id)
        setting = record.settings_registry.get(str(index))
        if setting is None:
            raise PluginError(f"unknown setting index {index!r}", plugin_id)
        callback_name = "on_long_click" if kind == "long_click" else "on_click"
        callback = getattr(setting, callback_name, None)
        callback_args = (view,)
        factory_callback = False
        if not callable(callback) and getattr(setting, "factory", None) is not None:
            factory = setting.factory
            owner = getattr(factory, "owner", factory)
            java_callback_name = (
                "onLongClick" if kind == "long_click" else "onClick"
            )
            callback = getattr(owner, callback_name, None)
            if not callable(callback):
                callback = getattr(owner, java_callback_name, None)
            plugin_argument = record.instance
            try:
                from com.exteragram.messenger.plugins import PluginsController

                plugin_argument = PluginsController.getInstance().plugins.get(plugin_id)
            except (ImportError, ModuleNotFoundError):
                pass
            callback_args = (plugin_argument, item, view)
            factory_callback = True
        if not callable(callback):
            return False
        ok, result = self._invoke_setting_callable(
            record, callback_name, callback, *callback_args
        )
        if kind == "long_click" and factory_callback:
            return ok and bool(result)
        return ok

    def create_custom_view(
        self,
        plugin_id: str,
        index: int | str,
        context: Any,
        list_view: Any,
        current_account: int,
        class_guid: int,
        resources_provider: Any,
    ) -> Any:
        record = self.records.get(plugin_id)
        if record is None or record.instance is None:
            raise PluginError("plugin is not loaded", plugin_id)
        setting = record.settings_registry.get(str(index))
        if setting is None:
            raise PluginError(f"unknown setting index {index!r}", plugin_id)
        ready_view = getattr(setting, "view", None)
        if ready_view is not None:
            return ready_view
        factory = getattr(setting, "factory", None)
        if factory is None:
            return None
        owner = getattr(factory, "owner", factory)
        create = getattr(owner, "create_view", None)
        if not callable(create):
            create = getattr(owner, "createView", None)
        if not callable(create):
            return None
        ok, view = self._invoke_setting_callable(
            record,
            "factory.create_view",
            create,
            context,
            list_view,
            current_account,
            class_guid,
            resources_provider,
        )
        if not ok or view is None:
            return None
        bind = getattr(owner, "bind_view", None)
        if not callable(bind):
            bind = getattr(owner, "bindView", None)
        if callable(bind):
            item = None
            try:
                from org.telegram.ui.Components import UItem
                item = UItem.asCustom(view)
            except (ImportError, ModuleNotFoundError):
                pass
            self._invoke_setting_callable(
                record,
                "factory.bind_view",
                bind,
                view,
                item,
                False,
                getattr(list_view, "adapter", None),
                list_view,
            )
        return view

    def create_custom_item(self, plugin_id: str, index: int | str) -> Any:
        record = self.records.get(plugin_id)
        if record is None or record.instance is None:
            raise PluginError("plugin is not loaded", plugin_id)
        setting = record.settings_registry.get(str(index))
        if setting is None:
            raise PluginError(f"unknown setting index {index!r}", plugin_id)
        ready_item = getattr(setting, "item", None)
        if ready_item is not None:
            return ready_item
        factory = getattr(setting, "factory", None)
        owner = getattr(factory, "owner", factory)
        create = getattr(owner, "create_item", None) if owner is not None else None
        if not callable(create):
            create = getattr(owner, "create", None)
        if not callable(create):
            return None
        plugin_argument = record.instance
        setting_argument = setting
        try:
            from com.exteragram.messenger.plugins import PluginsController
            from com.exteragram.messenger.plugins.models import CustomSetting

            plugin_argument = PluginsController.getInstance().plugins.get(plugin_id)
            setting_argument = CustomSetting(
                setting, getattr(setting, "factory_args", None)
            )
        except (ImportError, ModuleNotFoundError):
            pass
        ok, item = self._invoke_setting_callable(
            record,
            "factory.create_item",
            create,
            plugin_argument,
            setting_argument,
            getattr(setting, "factory_args", None),
        )
        return item if ok else None

    def _invoke_setting_callable(
        self, record: PluginRecord, callback_name: str, callback, *args
    ) -> tuple[bool, Any]:
        with self._lock:
            if record.instance is None or not record.enabled:
                return False, None
            token = enter_plugin(record.metadata.id, self)
            try:
                return True, callback(*args)
            except BaseException as exc:
                self._record_failure(record, f"setting.{callback_name}", exc)
                return False, None
            finally:
                leave_plugin(token)

    def invoke_callback(
        self, plugin_id: str, setting: Any, callback_name: str, *args
    ) -> Any:
        with self._lock:
            record = self.records.get(plugin_id)
            if record is None or record.instance is None:
                raise PluginError("plugin is not loaded", plugin_id)
            callback = getattr(setting, callback_name, None)
            if callback is None:
                return None
            if not callable(callback):
                raise TypeError(f"setting attribute {callback_name!r} is not callable")
            token = enter_plugin(plugin_id, self)
            try:
                return callback(*args)
            except BaseException as exc:
                self._record_failure(record, f"setting.{callback_name}", exc)
                return None
            finally:
                leave_plugin(token)

    def invoke_external_callback(
        self, plugin_id: str, label: str, callback, *args, **kwargs
    ) -> Any:
        with self._lock:
            record = self.records.get(plugin_id)
            if record is None or record.instance is None:
                return None
            token = enter_plugin(plugin_id, self)
            try:
                return callback(*args, **kwargs)
            except BaseException as exc:
                self._record_failure(record, label, exc)
                return None
            finally:
                leave_plugin(token)

    def invoke_discovered_callback(
        self, label: str, callback, *args, **kwargs
    ) -> Any:
        """Invoke a raw Java-round-tripped callable under its plugin owner.

        Legacy Java facades accept PyObject callbacks directly, bypassing the
        Python helper functions which normally wrap callbacks at registration
        time. Resolve those callbacks by bound instance or plugin module so the
        same crash journal and auto-disable policy still applies.
        """
        with self._lock:
            bound_owner = getattr(callback, "__self__", None)
            module_name = getattr(callback, "__module__", None)
            record = next(
                (
                    candidate
                    for candidate in self.records.values()
                    if candidate.instance is not None
                    and (
                        bound_owner is candidate.instance
                        or module_name == candidate.module_key
                    )
                ),
                None,
            )
            if record is not None:
                return self.invoke_external_callback(
                    record.metadata.id, label, callback, *args, **kwargs
                )
            try:
                return callback(*args, **kwargs)
            except BaseException:
                LOGGER.exception("unowned plugin callback failed in %s", label)
                return None

    def reload_settings(self, plugin_id: str) -> None:
        services.call("reload_plugin_settings", plugin_id, default=None)

    def log(self, plugin_id: str, message: str):
        if not isinstance(message, str):
            message = str(message)
        if services.has("plugin_log"):
            return services.call("plugin_log", plugin_id, message)
        LOGGER.info("[%s] %s", plugin_id, message)
        return None

    def add_menu_item(self, plugin: BasePlugin, menu_item_data: Any) -> str | None:
        if services.has("add_menu_item"):
            return services.call("add_menu_item", plugin.id, menu_item_data)
        item_id = menu_item_data.item_id or f"{plugin.id}:{len(self._menus) + 1}"
        self._menus[item_id] = (plugin.id, menu_item_data)
        return item_id

    def remove_menu_item(self, plugin: BasePlugin, item_id: str) -> bool:
        if services.has("remove_menu_item"):
            return bool(services.call("remove_menu_item", plugin.id, item_id))
        owner = self._menus.get(item_id)
        if owner is None or owner[0] != plugin.id:
            return False
        del self._menus[item_id]
        return True

    def describe(self, plugin_id: str) -> dict[str, Any]:
        record = self.records.get(plugin_id)
        if record is None:
            raise PluginError("plugin not found", plugin_id)
        saved = self.state.get_plugin(plugin_id)
        metadata = record.metadata
        return {
            "id": metadata.id,
            "name": metadata.name,
            "description": metadata.description,
            "author": metadata.author,
            "version": metadata.version,
            "icon": metadata.icon,
            "app_version": metadata.app_version,
            "min_version": metadata.min_version,
            "sdk_version": metadata.sdk_version,
            "requirements": list(metadata.requirements),
            "enabled": bool(saved.get("enabled", record.enabled)),
            "initialized": record.initialized,
            "error_message": record.error_message or saved.get("error_message"),
            "crash_count": int(saved.get("crash_count", 0)),
            "path": str(record.path),
        }

    def list_plugins(self) -> list[dict[str, Any]]:
        return [self.describe(plugin_id) for plugin_id in sorted(self.records)]
