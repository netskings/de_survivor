"""Chainable legacy alert builder with an injectable Android host service."""

from __future__ import annotations

from typing import Any, Callable

from plugin_runtime import services
from plugin_runtime.context import isolate_callback

try:
    from android.content import Context
    from android.graphics.drawable import Drawable
    from android.view import View
    from org.telegram.ui.ActionBar import AlertDialog, Theme
except (ImportError, ModuleNotFoundError):
    Context = Drawable = View = AlertDialog = Theme = Any


class _ButtonClickListenerProxy:
    def __init__(self, py_callable: Callable, builder_instance: "AlertDialogBuilder") -> None:
        self.py_callable = py_callable
        self.builder_instance = builder_instance

    def onClick(self, dialog_java_instance: Any, which: int):
        return self.py_callable(self.builder_instance, which)


class _ItemsClickListenerProxy(_ButtonClickListenerProxy):
    pass


class _DismissListenerProxy:
    def __init__(self, py_callable: Callable, builder_instance: "AlertDialogBuilder") -> None:
        self.py_callable = py_callable
        self.builder_instance = builder_instance

    def onDismiss(self, dialog_java_instance: Any):
        return self.py_callable(self.builder_instance)


class _CancelListenerProxy(_DismissListenerProxy):
    def onCancel(self, dialog_java_instance: Any):
        return self.py_callable(self.builder_instance)


class AlertDialogBuilder:
    ALERT_TYPE_MESSAGE = 0
    ALERT_TYPE_LOADING = 2
    ALERT_TYPE_SPINNER = 3
    BUTTON_POSITIVE = -1
    BUTTON_NEGATIVE = -2
    BUTTON_NEUTRAL = -3

    def __init__(
        self, context: Any, progress_style: int = 0, resources_provider: Any | None = None
    ) -> None:
        self._context = context
        self._progress_style = progress_style
        self._resources_provider = resources_provider
        self._values: dict[str, Any] = {}
        self._dialog = None

    def get_context(self) -> Any:
        return self._context

    def _set(self, key: str, value: Any) -> "AlertDialogBuilder":
        self._values[key] = value
        return self

    def set_title(self, title: str) -> "AlertDialogBuilder":
        return self._set("title", title)

    def set_message(self, message: str) -> "AlertDialogBuilder":
        return self._set("message", message)

    def set_message_text_view_clickable(self, clickable: bool) -> "AlertDialogBuilder":
        return self._set("message_clickable", clickable)

    def set_positive_button(self, text: str, listener: Callable | None = None) -> "AlertDialogBuilder":
        return self._set("positive_button", (text, isolate_callback(listener, "ui.alert.positive")))

    def set_negative_button(self, text: str, listener: Callable | None = None) -> "AlertDialogBuilder":
        return self._set("negative_button", (text, isolate_callback(listener, "ui.alert.negative")))

    def set_neutral_button(self, text: str, listener: Callable | None = None) -> "AlertDialogBuilder":
        return self._set("neutral_button", (text, isolate_callback(listener, "ui.alert.neutral")))

    def make_button_red(self, button_type: int) -> "AlertDialogBuilder":
        return self._set("red_button", button_type)

    def set_on_back_button_listener(self, listener: Callable | None = None) -> "AlertDialogBuilder":
        return self._set("back_listener", isolate_callback(listener, "ui.alert.back"))

    def set_view(self, view: Any, height: int = -2) -> "AlertDialogBuilder":
        return self._set("view", (view, height))

    def set_items(self, items: list[str], listener: Callable | None = None, icons: list[int] | None = None) -> "AlertDialogBuilder":
        return self._set("items", (items, isolate_callback(listener, "ui.alert.items"), icons))

    def set_on_dismiss_listener(self, listener: Callable | None = None) -> "AlertDialogBuilder":
        return self._set("dismiss_listener", isolate_callback(listener, "ui.alert.dismiss"))

    def set_on_cancel_listener(self, listener: Callable | None = None) -> "AlertDialogBuilder":
        return self._set("cancel_listener", isolate_callback(listener, "ui.alert.cancel"))

    def set_top_image(self, res_id: int, background_color: int) -> "AlertDialogBuilder":
        return self._set("top_image", (res_id, background_color))

    def set_top_drawable(self, drawable: Any, background_color: int) -> "AlertDialogBuilder":
        return self._set("top_drawable", (drawable, background_color))

    def set_top_animation(self, res_id: int, size: int, auto_repeat: bool, background_color: int, layer_colors: dict[str, int] | None = None) -> "AlertDialogBuilder":
        return self._set("top_animation", (res_id, size, auto_repeat, background_color, layer_colors))

    def set_top_animation_is_new(self, is_new: bool) -> "AlertDialogBuilder":
        return self._set("top_animation_is_new", is_new)

    def set_dim_enabled(self, enabled: bool) -> "AlertDialogBuilder":
        return self._set("dim_enabled", enabled)

    def set_dialog_button_color_key(self, theme_key: int) -> "AlertDialogBuilder":
        return self._set("button_color_key", theme_key)

    def set_blurred_background(self, blur: bool, blur_behind_if_possible: bool = True) -> "AlertDialogBuilder":
        return self._set("blurred_background", (blur, blur_behind_if_possible))

    def create(self) -> "AlertDialogBuilder":
        self._dialog = services.call(
            "create_alert_dialog", self, self._values, default=self
        )
        return self

    def show(self) -> "AlertDialogBuilder":
        if self._dialog is None:
            self.create()
        services.call("show_alert_dialog", self._dialog, default=None)
        return self

    def dismiss(self) -> None:
        services.call("dismiss_alert_dialog", self._dialog, default=None)

    def get_dialog(self) -> Any | None:
        return self._dialog

    def get_button(self, button_type: int) -> Any | None:
        return services.call("get_alert_button", self._dialog, button_type, default=None)

    def set_progress(self, progress: int):
        self._values["progress"] = progress
        services.call("set_alert_progress", self._dialog, progress, default=None)

    def set_cancelable(self, cancelable: bool):
        self._values["cancelable"] = cancelable
        services.call("set_alert_cancelable", self._dialog, cancelable, default=None)

    def set_canceled_on_touch_outside(self, cancel: bool):
        self._values["canceled_on_touch_outside"] = cancel
        services.call("set_alert_canceled_on_touch_outside", self._dialog, cancel, default=None)


__all__ = [
    "AlertDialog",
    "AlertDialogBuilder",
    "Context",
    "Drawable",
    "Theme",
    "View",
]
