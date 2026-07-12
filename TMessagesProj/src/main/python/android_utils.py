"""Legacy Android helpers backed by injectable Chaquopy host callbacks."""

from __future__ import annotations

import logging
import threading
from typing import Any

from plugin_runtime import services
from plugin_runtime.context import isolate_callback

try:
    from java import dynamic_proxy
    from java.lang import Runnable
    from android.view import View

    class OnClickListener(dynamic_proxy(View.OnClickListener)):
        def __init__(self, fn: callable) -> None:
            super().__init__()
            self.fn = isolate_callback(fn, "android_utils.OnClickListener")

        def onClick(self, _view) -> None:
            return self.fn(_view)

    class OnLongClickListener(dynamic_proxy(View.OnLongClickListener)):
        def __init__(self, fn: callable) -> None:
            super().__init__()
            self.fn = isolate_callback(fn, "android_utils.OnLongClickListener")

        def onLongClick(self, _view):
            return bool(self.fn(_view))

    class _R(dynamic_proxy(Runnable)):
        def __init__(self, fn) -> None:
            super().__init__()
            self.fn = isolate_callback(fn, "android_utils.Runnable")

        def run(self) -> None:
            return self.fn()
except (ImportError, ModuleNotFoundError):
    class OnClickListener:
        def __init__(self, fn: callable) -> None:
            self.fn = isolate_callback(fn, "android_utils.OnClickListener")

        def onClick(self, _view) -> None:
            return self.fn(_view)

    class OnLongClickListener:
        def __init__(self, fn: callable) -> None:
            self.fn = isolate_callback(fn, "android_utils.OnLongClickListener")

        def onLongClick(self, _view):
            return bool(self.fn(_view))

    class _R:
        def __init__(self, fn) -> None:
            self.fn = isolate_callback(fn, "android_utils.Runnable")

        def run(self) -> None:
            return self.fn()


def run_on_ui_thread(func: callable, delay: int = 0):
    if not callable(func):
        raise TypeError("func must be callable")
    func = isolate_callback(func, "android_utils.run_on_ui_thread")
    if services.has("run_on_ui_thread"):
        return services.call("run_on_ui_thread", func, delay)
    if delay <= 0:
        return func()
    timer = threading.Timer(delay / 1000.0, func)
    timer.daemon = True
    timer.start()
    return timer


def log(data: Any):
    if services.has("android_log"):
        return services.call("android_log", data)
    logging.getLogger("telegram.plugins").info("%s", data)
    return None


def copy_to_clipboard(text: str):
    return services.call("copy_to_clipboard", text, default=None)


def R(fn):
    return _R(fn)
