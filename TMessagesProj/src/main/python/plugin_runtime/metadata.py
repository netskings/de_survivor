"""Static, non-executing parser for exteraGram/AyuGram plugin metadata."""

from __future__ import annotations

import ast
import re
import tokenize
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .errors import CompatibilityError, MetadataError

METADATA_NAMES = {
    "__id__",
    "__name__",
    "__description__",
    "__author__",
    "__version__",
    "__icon__",
    "__app_version__",
    "__sdk_version__",
    "__requirements__",
    "__min_version__",
}
PLUGIN_ID_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_-]{1,31}$")
MAX_PLUGIN_SOURCE_BYTES = 4 * 1024 * 1024


@dataclass(frozen=True)
class StaticPluginMetadata:
    id: str
    name: str
    description: str = ""
    author: str = ""
    version: str = "1.0"
    icon: str | None = None
    app_version: str | None = None
    sdk_version: str | None = None
    requirements: tuple[str, ...] = ()
    min_version: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "description": self.description,
            "author": self.author,
            "version": self.version,
            "icon": self.icon,
            "app_version": self.app_version,
            "sdk_version": self.sdk_version,
            "requirements": list(self.requirements),
            "min_version": self.min_version,
        }


def _read_source(path: Path) -> str:
    if path.suffix.lower() not in {".py", ".plugin"}:
        raise MetadataError("plugin must be a single .py or .plugin source file")
    try:
        size = path.stat().st_size
    except OSError as exc:
        raise MetadataError(f"cannot access plugin source: {exc}") from exc
    if size > MAX_PLUGIN_SOURCE_BYTES:
        raise MetadataError(
            f"plugin source exceeds {MAX_PLUGIN_SOURCE_BYTES} byte safety limit"
        )
    try:
        with tokenize.open(path) as source_file:
            return source_file.read()
    except (OSError, SyntaxError, UnicodeError) as exc:
        raise MetadataError(f"cannot decode plugin source: {exc}") from exc


def parse_metadata_source(source: str, filename: str = "<plugin>") -> StaticPluginMetadata:
    try:
        tree = ast.parse(source, filename=filename)
    except SyntaxError as exc:
        raise MetadataError(f"invalid Python syntax: {exc}") from exc

    values: dict[str, Any] = {}
    for node in tree.body:
        name: str | None = None
        value_node: ast.AST | None = None
        if isinstance(node, ast.Assign) and len(node.targets) == 1:
            target = node.targets[0]
            if isinstance(target, ast.Name):
                name = target.id
                value_node = node.value
        if name not in METADATA_NAMES or value_node is None:
            continue
        if name == "__requirements__":
            if not isinstance(value_node, ast.List) or any(
                not isinstance(item, ast.Constant) or not isinstance(item.value, str)
                for item in value_node.elts
            ):
                raise MetadataError(
                    "metadata __requirements__ must be a static list of string constants"
                )
            values[name] = [item.value for item in value_node.elts]
        elif isinstance(value_node, ast.Constant):
            values[name] = value_node.value
        else:
            raise MetadataError(f"metadata {name} must be a static constant")

    plugin_id = values.get("__id__")
    name = values.get("__name__")
    if not isinstance(plugin_id, str):
        raise MetadataError("required metadata __id__ must be a string literal")
    if not PLUGIN_ID_RE.fullmatch(plugin_id):
        raise MetadataError(
            "__id__ must be 2-32 characters, start with a latin letter, and "
            "contain only latin letters, digits, '_' or '-'"
        )
    if not isinstance(name, str) or not name.strip():
        raise MetadataError("required metadata __name__ must be a non-empty string")

    def optional_string(key: str, default: str | None) -> str | None:
        value = values.get(key, default)
        if value is not None and not isinstance(value, str):
            raise MetadataError(f"metadata {key} must be a string literal")
        return value

    requirements_value = values.get("__requirements__", [])
    if not isinstance(requirements_value, list) or any(
        not isinstance(item, str) or not item.strip() for item in requirements_value
    ):
        raise MetadataError("__requirements__ must be a list of non-empty strings")

    min_version = optional_string("__min_version__", None)
    app_version = optional_string("__app_version__", None)
    if app_version is None and min_version:
        # Both historical spellings exist in the wild: "11.12.0" and
        # ">=11.12.0". Never manufacture an invalid ">=>=..." specifier.
        app_version = (
            min_version
            if re.match(r"^(===|==|!=|~=|<=|>=|<|>)", min_version.strip())
            else f">={min_version}"
        )

    return StaticPluginMetadata(
        id=plugin_id,
        name=name,
        description=optional_string("__description__", "") or "",
        author=optional_string("__author__", "") or "",
        version=optional_string("__version__", "1.0") or "1.0",
        icon=optional_string("__icon__", None),
        app_version=app_version,
        sdk_version=optional_string("__sdk_version__", None),
        requirements=tuple(requirements_value),
        min_version=min_version,
    )


def read_metadata(path: str | Path) -> StaticPluginMetadata:
    source_path = Path(path)
    return parse_metadata_source(_read_source(source_path), str(source_path))


def _fallback_version_tuple(value: str) -> tuple[tuple[int, Any], ...]:
    parts = re.findall(r"\d+|[A-Za-z]+", value)
    return tuple((0, int(part)) if part.isdigit() else (1, part.lower()) for part in parts)


def _fallback_matches(current: str, specifier: str) -> bool:
    current_tuple = _fallback_version_tuple(current)
    for raw_clause in specifier.split(","):
        clause = raw_clause.strip()
        if not clause:
            continue
        match = re.fullmatch(r"(===|==|!=|<=|>=|<|>)(.+)", clause)
        if not match:
            raise MetadataError(f"invalid version specifier: {specifier!r}")
        operator, wanted = match.groups()
        wanted = wanted.strip()
        if operator in {"==", "!="} and wanted.endswith(".*"):
            prefix = wanted[:-2]
            equal = current == prefix or current.startswith(prefix + ".")
        else:
            wanted_tuple = _fallback_version_tuple(wanted)
            if operator in {"==", "==="}:
                equal = current_tuple == wanted_tuple
            elif operator == "!=":
                equal = current_tuple != wanted_tuple
            elif operator == ">=":
                equal = current_tuple >= wanted_tuple
            elif operator == "<=":
                equal = current_tuple <= wanted_tuple
            elif operator == ">":
                equal = current_tuple > wanted_tuple
            else:
                equal = current_tuple < wanted_tuple
        if not equal:
            return False
    return True


def version_matches(current: str, specifier: str | None) -> bool:
    if not specifier:
        return True
    try:
        from packaging.specifiers import InvalidSpecifier, SpecifierSet
        from packaging.version import InvalidVersion, Version

        try:
            return Version(current) in SpecifierSet(specifier)
        except (InvalidSpecifier, InvalidVersion) as exc:
            raise MetadataError(f"invalid version constraint: {exc}") from exc
    except ImportError:
        return _fallback_matches(current, specifier)


def assert_compatible(
    metadata: StaticPluginMetadata, app_version: str, sdk_version: str
) -> None:
    if not version_matches(app_version, metadata.app_version):
        raise CompatibilityError(
            f"plugin {metadata.id} requires app {metadata.app_version}, current is {app_version}"
        )
    if not version_matches(sdk_version, metadata.sdk_version):
        raise CompatibilityError(
            f"plugin {metadata.id} requires SDK {metadata.sdk_version}, current is {sdk_version}"
        )
