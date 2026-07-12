"""Legacy file helpers with paths rooted in runtime-owned app storage."""

from __future__ import annotations

import os
from pathlib import Path

from plugin_runtime import services
from plugin_runtime.context import get_manager


def _directory(service_name: str, fallback_name: str) -> str:
    if services.has(service_name):
        path = Path(services.call(service_name))
    else:
        manager = get_manager(required=False)
        base = manager.state_dir if manager is not None else Path.cwd()
        path = base / fallback_name
    path.mkdir(parents=True, exist_ok=True)
    return str(path)


def get_plugins_dir() -> str:
    manager = get_manager(required=False)
    if manager is not None:
        return str(manager.plugins_dir)
    return _directory("get_plugins_dir", "plugins")


def get_cache_dir() -> str: return _directory("get_cache_dir", "cache")
def get_files_dir() -> str: return _directory("get_files_dir", "files")
def get_images_dir() -> str: return _directory("get_images_dir", "images")
def get_videos_dir() -> str: return _directory("get_videos_dir", "videos")
def get_audios_dir() -> str: return _directory("get_audios_dir", "audios")
def get_documents_dir() -> str: return _directory("get_documents_dir", "documents")


def read_file(file_path: str) -> str | None:
    try:
        with open(file_path, "r", encoding="utf-8") as source:
            return source.read()
    except (OSError, UnicodeError):
        return None


def write_file(file_path: str, content: str):
    if not isinstance(content, str):
        raise TypeError("content must be str")
    path = Path(file_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as output:
        return output.write(content)


def delete_file(file_path: str) -> bool:
    try:
        Path(file_path).unlink()
        return True
    except OSError:
        return False


def ensure_dir_exists(dir_path: str):
    Path(dir_path).mkdir(parents=True, exist_ok=True)


def list_dir(path: str, recursive: bool = False, include_files: bool = True, include_dirs: bool = False, extensions: list[str] | None = None) -> list[str]:
    root = Path(path)
    iterator = root.rglob("*") if recursive else root.glob("*")
    normalized_extensions = None
    if extensions is not None:
        normalized_extensions = {
            extension.lower() if extension.startswith(".") else f".{extension.lower()}"
            for extension in extensions
        }
    result = []
    for item in iterator:
        if item.is_file() and include_files:
            if normalized_extensions is None or item.suffix.lower() in normalized_extensions:
                result.append(str(item))
        elif item.is_dir() and include_dirs:
            result.append(str(item))
    return result
