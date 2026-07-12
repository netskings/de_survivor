"""Small process-wide context shared by legacy compatibility modules."""

from contextvars import ContextVar
from typing import Any

_manager: Any = None
_active_plugin_id: ContextVar[str | None] = ContextVar(
    "active_telegram_plugin_id", default=None
)
_active_manager: ContextVar[Any] = ContextVar(
    "active_telegram_plugin_manager", default=None
)


def set_manager(manager: Any) -> None:
    global _manager
    _manager = manager


def get_manager(required: bool = True) -> Any:
    manager = _active_manager.get() or _manager
    if manager is None and required:
        raise RuntimeError("plugin runtime has not been initialized")
    return manager


def enter_plugin(plugin_id: str, manager: Any = None):
    return _active_plugin_id.set(plugin_id), _active_manager.set(manager)


def leave_plugin(token) -> None:
    plugin_token, manager_token = token
    _active_plugin_id.reset(plugin_token)
    _active_manager.reset(manager_token)


def get_active_plugin_id() -> str | None:
    return _active_plugin_id.get()


def isolate_callback(callback, label: str):
    if callback is None:
        return None
    plugin_id = get_active_plugin_id()
    owner_manager = _active_manager.get() or get_manager(required=False)

    def wrapped(*args, **kwargs):
        manager = owner_manager or get_manager(required=False)
        if manager is not None and plugin_id is not None:
            return manager.invoke_external_callback(
                plugin_id, label, callback, *args, **kwargs
            )
        try:
            return callback(*args, **kwargs)
        except BaseException:
            return None

    return wrapped
