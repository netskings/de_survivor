"""Legacy Telegram bulletin helpers delegated to an Android host service."""

from __future__ import annotations

from typing import Callable

from plugin_runtime import services
from plugin_runtime.context import isolate_callback

try:
    from org.telegram.ui.ActionBar import BaseFragment, Theme
except (ImportError, ModuleNotFoundError):
    BaseFragment = Theme = object


class BulletinHelper:
    DURATION_SHORT = 1500
    DURATION_LONG = 2750
    DURATION_PROLONG = 5000

    @classmethod
    def _show(cls, kind: str, *args, **kwargs):
        return services.call("show_bulletin", kind, args, kwargs, default=None)

    @classmethod
    def show_info(cls, message: str, fragment=None):
        return cls._show("info", message, fragment=fragment)

    @classmethod
    def show_error(cls, message: str, fragment=None):
        return cls._show("error", message, fragment=fragment)

    @classmethod
    def show_success(cls, message: str, fragment=None):
        return cls._show("success", message, fragment=fragment)

    @classmethod
    def show_simple(cls, text: str, icon_res_id: int, fragment=None):
        return cls._show("simple", text, icon_res_id, fragment=fragment)

    @classmethod
    def show_two_line(cls, title: str, subtitle: str, icon_res_id: int, fragment=None):
        return cls._show("two_line", title, subtitle, icon_res_id, fragment=fragment)

    @classmethod
    def show_with_button(cls, text: str, icon_res_id: int, button_text: str, on_click: Callable[[], None] | None, fragment=None, duration: int = DURATION_PROLONG):
        return cls._show("with_button", text, icon_res_id, button_text, isolate_callback(on_click, "ui.bulletin.button"), fragment=fragment, duration=duration)

    @classmethod
    def show_undo(cls, text: str, on_undo: Callable[[], None], on_action: Callable[[], None] | None = None, subtitle: str | None = None, fragment=None):
        return cls._show("undo", text, isolate_callback(on_undo, "ui.bulletin.undo"), isolate_callback(on_action, "ui.bulletin.action"), subtitle=subtitle, fragment=fragment)

    @classmethod
    def show_copied_to_clipboard(cls, message: str | None = None, fragment=None):
        return cls._show("copied_to_clipboard", message, fragment=fragment)

    @classmethod
    def show_link_copied(cls, is_private_link_info: bool = False, fragment=None):
        return cls._show("link_copied", is_private_link_info, fragment=fragment)

    @classmethod
    def show_file_saved_to_gallery(cls, is_video: bool = False, amount: int = 1, fragment=None):
        return cls._show("file_saved_to_gallery", is_video, amount, fragment=fragment)

    @classmethod
    def show_file_saved_to_downloads(cls, file_type_enum_name: str = "UNKNOWN", amount: int = 1, fragment=None):
        return cls._show("file_saved_to_downloads", file_type_enum_name, amount, fragment=fragment)


__all__ = ["BaseFragment", "BulletinHelper", "Theme"]
