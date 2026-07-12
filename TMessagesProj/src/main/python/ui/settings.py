"""SDK 1.4.3.10 settings dataclasses, usable on Android and in host tests."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable

from plugin_runtime.context import get_active_plugin_id, isolate_callback

try:
    from android.view import View
    from com.exteragram.messenger.plugins import PluginsConstants
    from com.exteragram.messenger.plugins.models import (
        CustomSetting,
        SimpleSettingFactoryAdapter,
    )
    from org.telegram.ui.Components import UItem
    _ANDROID_TYPES = True
except (ImportError, ModuleNotFoundError):
    View = UItem = CustomSetting = PluginsConstants = None
    SimpleSettingFactoryAdapter = None
    _ANDROID_TYPES = False

TYPE_SWITCH = "switch"
TYPE_SELECTOR = "selector"
TYPE_INPUT = "input"
TYPE_TEXT = "text"
TYPE_HEADER = "header"
TYPE_DIVIDER = "divider"
TYPE_EDIT_TEXT = "edit_text"
TYPE_CUSTOM = "custom"

# Historical implementation details exported by the compiled SDK module.
# They belonged to its DexMaker factory generator; the compatible native
# adapter above replaces that implementation. Keep the names importable until
# class-proxy API v2 supplies corresponding reflection objects.
pyobject_type = None
object_array_type = None
typehelper = None
pyobject_call_method = None


@dataclass
class Switch:
    key: str
    text: str
    default: bool
    subtext: str = ""
    icon: str = ""
    on_change: Callable[[bool], None] = field(default=None, compare=False, repr=False)
    type: str = field(default=TYPE_SWITCH, init=False)
    on_long_click: Callable[[Any], None] = field(
        default=None, compare=False, repr=False
    )
    link_alias: str = ""


@dataclass
class Selector:
    key: str
    text: str
    default: int
    items: list[str]
    icon: str = ""
    on_change: Callable[[int], None] = field(default=None, compare=False, repr=False)
    type: str = field(default=TYPE_SELECTOR, init=False)
    on_long_click: Callable[[Any], None] = field(
        default=None, compare=False, repr=False
    )
    link_alias: str = ""


@dataclass
class Input:
    key: str
    text: str
    default: str = ""
    subtext: str = ""
    icon: str = ""
    on_change: Callable[[str], None] = field(default=None, compare=False, repr=False)
    type: str = field(default=TYPE_INPUT, init=False)
    on_long_click: Callable[[Any], None] = field(
        default=None, compare=False, repr=False
    )
    link_alias: str = ""


@dataclass
class Text:
    text: str
    subtext: str = ""
    icon: str = ""
    accent: bool = False
    red: bool = False
    on_click: Callable[[Any], None] = field(default=None, compare=False, repr=False)
    create_sub_fragment: Callable[[], list[Any]] = field(
        default=None, compare=False, repr=False
    )
    type: str = field(default=TYPE_TEXT, init=False)
    on_long_click: Callable[[Any], None] = field(
        default=None, compare=False, repr=False
    )
    link_alias: str = ""

    def __post_init__(self) -> None:
        # exteragram-utils 0.1.x predates `subtext` and used the positional
        # shape Text(text, icon, accent, red, ...). When its third positional
        # value is present, the bool in the canonical `icon` slot identifies
        # that typed legacy overload without inspecting icon names.
        if isinstance(self.icon, bool):
            legacy_icon = self.subtext
            legacy_accent = self.icon
            legacy_red = self.red if self.red is True else self.accent
            if isinstance(self.red, bool):
                # No fifth positional argument: identically named callback
                # keywords are already bound to their canonical slots.
                legacy_on_click = self.on_click
                legacy_create_sub_fragment = self.create_sub_fragment
                legacy_on_long_click = self.on_long_click
                legacy_link_alias = self.link_alias
            else:
                legacy_on_click = self.red
                legacy_create_sub_fragment = self.on_click
                legacy_on_long_click = self.create_sub_fragment
                legacy_link_alias = (
                    self.link_alias
                    if self.link_alias
                    else "" if self.on_long_click is None else self.on_long_click
                )
            self.subtext = ""
            self.icon = legacy_icon
            self.accent = legacy_accent
            self.red = legacy_red
            self.on_click = legacy_on_click
            self.create_sub_fragment = legacy_create_sub_fragment
            self.on_long_click = legacy_on_long_click
            self.link_alias = legacy_link_alias


@dataclass
class Header:
    text: str
    type: str = field(default=TYPE_HEADER, init=False)


@dataclass
class Divider:
    text: str = ""
    type: str = field(default=TYPE_DIVIDER, init=False)


@dataclass
class EditText:
    key: str
    hint: str
    default: str = ""
    multiline: bool = False
    max_length: int = 0
    mask: str = ""
    on_change: Callable[[str], None] = field(default=None, compare=False, repr=False)
    type: str = field(default=TYPE_EDIT_TEXT, init=False)


@dataclass
class Custom:
    type: str = field(default=TYPE_CUSTOM, init=False)
    item: Any = field(default=None, compare=False, repr=False)
    view: Any = field(default=None, compare=False, repr=False)
    factory: Any = field(default=None, compare=False, repr=False)
    factory_args: Any = field(default=None, compare=False, repr=False)
    on_click: Callable[[Any], None] = field(default=None, compare=False, repr=False)
    on_long_click: Callable[[Any], None] = field(
        default=None, compare=False, repr=False
    )
    create_sub_fragment: Callable[[], list[Any]] = field(
        default=None, compare=False, repr=False
    )
    link_alias: str = ""

    def __post_init__(self) -> None:
        if not _ANDROID_TYPES:
            return
        if self.item is not None and not isinstance(self.item, UItem):
            raise ValueError(
                f"item must be an instance of UItem, but got {type(self.item)}"
            )
        if self.view is not None and not isinstance(self.view, View):
            raise ValueError(
                f"view must be an instance of View, but got {type(self.view)}"
            )
        if self.factory is not None and not isinstance(
            self.factory, CustomSetting.Factory
        ):
            raise ValueError(
                "factory must be an instance of CustomSetting.Factory, "
                f"but got {type(self.factory)}"
            )


class _FactoryPeer:
    def __init__(self, java) -> None:
        self.java = java


class SimpleSettingFactory:
    def __init__(
        self,
        create_view: callable,
        bind_view: callable,
        *,
        is_clickable: bool = False,
        is_shadow: bool = False,
        create_item: callable | None = None,
        on_click: callable | None = None,
        on_long_click: callable | None = None,
        attached_view: callable | None = None,
        equals: callable | None = None,
        content_equals: callable | None = None,
    ) -> None:
        if not callable(create_view) or not callable(bind_view):
            raise TypeError("create_view and bind_view must be callable")
        self.create_view = isolate_callback(create_view, "ui.settings.factory.create_view")
        self.bind_view = isolate_callback(bind_view, "ui.settings.factory.bind_view")
        self.is_clickable = is_clickable
        self.is_shadow = is_shadow
        self.create_item = isolate_callback(create_item, "ui.settings.factory.create_item")
        self.on_click = isolate_callback(on_click, "ui.settings.factory.on_click")
        self.on_long_click = isolate_callback(on_long_click, "ui.settings.factory.on_long_click")
        self.attached_view = isolate_callback(attached_view, "ui.settings.factory.attached_view")
        self.equals = isolate_callback(equals, "ui.settings.factory.equals")
        self.content_equals = isolate_callback(content_equals, "ui.settings.factory.content_equals")
        java_factory = self
        if SimpleSettingFactoryAdapter is not None:
            java_factory = SimpleSettingFactoryAdapter(
                self.create_view,
                self.bind_view,
                bool(self.is_clickable),
                bool(self.is_shadow),
                self.create_item,
                self.on_click,
                self.on_long_click,
                self.attached_view,
                self.equals,
                self.content_equals,
                get_active_plugin_id(),
            )
        self.instance = _FactoryPeer(java_factory)
        self.java = java_factory

    def __call__(self, *args, create_sub_fragment=None, link_alias=None):
        return Custom(
            factory=self.instance.java,
            factory_args=args,
            create_sub_fragment=create_sub_fragment,
            link_alias="" if link_alias is None else link_alias,
        )


__all__ = [
    "CustomSetting",
    "Custom",
    "Divider",
    "EditText",
    "Header",
    "Input",
    "PluginsConstants",
    "Selector",
    "SimpleSettingFactory",
    "Switch",
    "Text",
    "TYPE_CUSTOM",
    "TYPE_DIVIDER",
    "TYPE_EDIT_TEXT",
    "TYPE_HEADER",
    "TYPE_INPUT",
    "TYPE_SELECTOR",
    "TYPE_SWITCH",
    "TYPE_TEXT",
    "UItem",
    "View",
    "object_array_type",
    "pyobject_call_method",
    "pyobject_type",
    "typehelper",
]
