"""exteraGram plugin SDK 1.4.3.10 compatible high-level public API."""

from __future__ import annotations

import abc
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum, auto
from typing import Any, Callable, final

from plugin_runtime.context import get_manager
from plugin_runtime.errors import reflection_phase_error


@dataclass
class PluginMetadata:
    id: str
    name: str
    description: str
    author: str
    version: str
    icon: str
    min_version: str
    requirements: list[str] = field(default_factory=list)


class HookStrategy(Enum):
    CANCEL = auto()
    # AyuGram's older public examples used BLOCK/CONTINUE. Enum aliases keep
    # the stable exteraGram values and bridge names unchanged.
    BLOCK = CANCEL
    MODIFY = auto()
    DEFAULT = auto()
    CONTINUE = DEFAULT
    MODIFY_FINAL = auto()


@dataclass
class HookResult:
    strategy: HookStrategy = HookStrategy.DEFAULT
    request: Any = None
    response: Any = None
    update: Any = None
    updates: Any = None
    error: Any = None
    params: Any = None


class PluginError(Exception):
    plugin_id: str | None

    def __init__(self, message: str, plugin_id: str | None = None) -> None:
        super().__init__(message)
        self.plugin_id = plugin_id


class XposedHook(ABC):
    """Compatibility marker; Java/Xposed binding is intentionally phase-gated."""


class MethodReplacement(XposedHook, metaclass=abc.ABCMeta):
    @abstractmethod
    def replace_hooked_method(self, param) -> Any:
        raise reflection_phase_error("MethodReplacement")


class MethodHook(XposedHook):
    def before_hooked_method(self, param):
        raise reflection_phase_error("MethodHook.before_hooked_method")

    def after_hooked_method(self, param):
        raise reflection_phase_error("MethodHook.after_hooked_method")


def fn_hook_filters(field_name: str):
    def decorator(fn):
        setattr(fn, "__hook_filter_field__", field_name)
        return fn

    return decorator


class BaseHook(MethodHook):
    def __init__(
        self,
        plugin: BasePlugin | None = None,
        *,
        before: Callable | None = None,
        after: Callable | None = None,
        before_filters: list[Any] | None = None,
        after_filters: list[Any] | None = None,
    ) -> None:
        self.plugin = plugin
        self.before = before
        self.after = after
        self.before_filters = before_filters
        self.after_filters = after_filters

    def before_hooked_method(self, param) -> None:
        raise reflection_phase_error("BaseHook.before_hooked_method")

    def after_hooked_method(self, param) -> None:
        raise reflection_phase_error("BaseHook.after_hooked_method")


class AppEvent(Enum):
    START = auto()
    STOP = auto()
    PAUSE = auto()
    RESUME = auto()


class MenuItemType(Enum):
    MESSAGE_CONTEXT_MENU = auto()
    DRAWER_MENU = auto()
    CHAT_ACTION_MENU = auto()
    PROFILE_ACTION_MENU = auto()


@dataclass
class MenuItemData:
    menu_type: MenuItemType
    text: str
    on_click: Callable[[dict[str, Any]], None]
    item_id: str | None = None
    icon: str | None = None
    subtext: str | None = None
    condition: str | None = None
    priority: int = 0


@dataclass
class HookFilterData:
    filter_type: str
    arg_index: int | None = None
    or_filters: Any | None = None
    mvel_expression: str | None = None
    instance_of: Any | None = None
    object: Any = None

    def to_java_filter(self):
        raise reflection_phase_error("HookFilterData.to_java_filter")


class HookFilter(Enum):
    RESULT_IS_NULL = HookFilterData("RESULT_IS_NULL")
    RESULT_IS_TRUE = HookFilterData("RESULT_IS_TRUE")
    RESULT_IS_FALSE = HookFilterData("RESULT_IS_FALSE")
    RESULT_NOT_NULL = HookFilterData("RESULT_NOT_NULL")

    @property
    def filter_data(self):
        return self.value

    @staticmethod
    def ResultIsInstanceOf(clazz):
        return HookFilterData("RESULT_IS_INSTANCE_OF", instance_of=clazz)

    @staticmethod
    def ResultEqual(value):
        return HookFilterData("RESULT_EQUAL", object=value)

    @staticmethod
    def ResultNotEqual(value):
        return HookFilterData("RESULT_NOT_EQUAL", object=value)

    @staticmethod
    def ArgumentIsNull(index: int):
        return HookFilterData("ARGUMENT_IS_NULL", arg_index=index)

    @staticmethod
    def ArgumentIsTrue(index: int):
        return HookFilterData("ARGUMENT_IS_TRUE", arg_index=index)

    @staticmethod
    def ArgumentIsFalse(index: int):
        return HookFilterData("ARGUMENT_IS_FALSE", arg_index=index)

    @staticmethod
    def ArgumentNotNull(index: int):
        return HookFilterData("ARGUMENT_NOT_NULL", arg_index=index)

    @staticmethod
    def ArgumentIsInstanceOf(index: int, clazz):
        return HookFilterData("ARGUMENT_IS_INSTANCE_OF", arg_index=index, instance_of=clazz)

    @staticmethod
    def ArgumentEqual(index: int, value):
        return HookFilterData("ARGUMENT_EQUAL", arg_index=index, object=value)

    @staticmethod
    def ArgumentNotEqual(index: int, value):
        return HookFilterData("ARGUMENT_NOT_EQUAL", arg_index=index, object=value)

    @staticmethod
    def Condition(condition: str, object: Any = None):
        return HookFilterData("CONDITION", mvel_expression=condition, object=object)

    @staticmethod
    def Or(*filters):
        return HookFilterData("OR", or_filters=filters)


def hook_filters(*filters):
    def decorator(fn):
        setattr(fn, "__hook_filters__", filters)
        return fn

    return decorator


class BasePlugin:
    id: str = ""
    name: str = ""
    description: str = ""
    author: str = ""
    min_version: str = ""
    version: str = "1.0"
    requirements: list[str] = []
    icon: str | None = None
    error_message: str | None = None
    enabled: bool = False
    initialized: bool = False

    def __new__(cls):
        return super().__new__(cls)

    def __init__(self) -> None:
        self.error_message = None
        self.enabled = False
        self.initialized = False
        self._plugin_manager = None

    def _manager(self):
        manager = getattr(self, "_plugin_manager", None) or get_manager(required=False)
        if manager is None:
            raise PluginError("plugin is not attached to a runtime", self.id or None)
        return manager

    def on_plugin_load(self) -> None:
        return None

    def on_plugin_unload(self) -> None:
        return None

    def create_settings(self) -> list[Any]:
        return []

    def on_app_event(self, event_type: AppEvent):
        return None

    def pre_request_hook(self, request_name: str, account: int, request: Any) -> HookResult:
        return HookResult()

    def post_request_hook(
        self, request_name: str, account: int, response: Any, error: Any
    ) -> HookResult:
        return HookResult()

    def on_update_hook(self, update_name: str, account: int, update: Any) -> HookResult:
        return HookResult()

    def on_updates_hook(
        self, container_name: str, account: int, updates: Any
    ) -> HookResult:
        return HookResult()

    def on_send_message_hook(self, account: int, params: Any) -> HookResult:
        return HookResult()

    @final
    def add_hook(self, name: str, match_substring: bool = False, priority: int = 0):
        return self._manager().add_hook(self, name, match_substring, priority)

    @final
    def add_on_send_message_hook(self, priority: int = 0):
        return self._manager().add_send_hook(self, priority)

    @final
    def remove_hook(self, name: str):
        return self._manager().remove_hook(self, name)

    @final
    def get_setting(self, key: str, default: Any = None) -> Any:
        return self._manager().settings.get(self.id, key, default)

    @final
    def set_setting(self, key: str, value: Any, reload_settings: bool = False):
        self._manager().settings.set(self.id, key, value)
        if reload_settings:
            self._manager().reload_settings(self.id)

    @final
    def export_settings(self) -> dict:
        return self._manager().settings.get_all(self.id)

    @final
    def import_settings(self, settings: dict, reload_settings: bool = True):
        self._manager().settings.set_all(self.id, settings)
        if reload_settings:
            self._manager().reload_settings(self.id)

    @final
    def hook_method(
        self,
        method_or_constructor,
        xposed_hook: Any | None = None,
        priority: int | None = None,
        *,
        before: Callable | None = None,
        after: Callable | None = None,
        before_filters: list[Any] | None = None,
        after_filters: list[Any] | None = None,
    ):
        raise reflection_phase_error("BasePlugin.hook_method")

    @final
    def hook_all_methods(
        self,
        hook_class,
        method_name: str,
        xposed_hook: Any | None = None,
        priority: int | None = None,
        *,
        before: Callable | None = None,
        after: Callable | None = None,
        before_filters: list[Any] | None = None,
        after_filters: list[Any] | None = None,
    ):
        raise reflection_phase_error("BasePlugin.hook_all_methods")

    @final
    def hook_all_constructors(
        self,
        hook_class,
        xposed_hook: Any | None = None,
        priority: int | None = None,
        *,
        before: Callable | None = None,
        after: Callable | None = None,
        before_filters: list[Any] | None = None,
        after_filters: list[Any] | None = None,
    ):
        raise reflection_phase_error("BasePlugin.hook_all_constructors")

    @final
    def unhook_method(self, unhook):
        raise reflection_phase_error("BasePlugin.unhook_method")

    @final
    def log(self, message: str):
        return self._manager().log(self.id, message)

    @final
    def add_menu_item(self, menu_item_data: MenuItemData) -> str | None:
        return self._manager().add_menu_item(self, menu_item_data)

    @final
    def remove_menu_item(self, item_id: str) -> bool:
        return self._manager().remove_menu_item(self, item_id)


__all__ = [
    "AppEvent",
    "BaseHook",
    "BasePlugin",
    "HookFilter",
    "HookFilterData",
    "HookResult",
    "HookStrategy",
    "MenuItemData",
    "MenuItemType",
    "MethodHook",
    "MethodReplacement",
    "PluginError",
    "PluginMetadata",
    "XposedHook",
    "fn_hook_filters",
    "hook_filters",
]
