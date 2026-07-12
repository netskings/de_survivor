"""Injectable host services used by Android-facing legacy helper modules."""

from __future__ import annotations

import threading
from typing import Any, Callable, Mapping

_lock = threading.RLock()
_services: dict[str, Callable[..., Any]] = {}


def configure(services: Mapping[str, Callable[..., Any]] | None = None, **kwargs) -> None:
    with _lock:
        if services:
            _services.update(services)
        _services.update(kwargs)


def clear() -> None:
    with _lock:
        _services.clear()


def has(name: str) -> bool:
    with _lock:
        return name in _services


def call(name: str, *args, default: Any = ..., **kwargs) -> Any:
    with _lock:
        service = _services.get(name)
    if service is None:
        if default is not ...:
            return default
        raise RuntimeError(f"Android host service {name!r} is not configured")
    return service(*args, **kwargs)
