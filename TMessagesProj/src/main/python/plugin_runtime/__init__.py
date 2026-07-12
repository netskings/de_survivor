"""Shared core for the legacy plugin API and the versioned v2 facade."""

from .errors import (
    CompatibilityError,
    FeatureNotAvailableError,
    MetadataError,
    PluginLoadError,
    PluginRuntimeError,
    RequirementSecurityError,
)
from .metadata import StaticPluginMetadata, read_metadata


def __getattr__(name):
    # BasePlugin imports plugin_runtime.context while manager imports
    # BasePlugin. Keep the public convenience export without an import cycle.
    if name == "PluginManager":
        from .manager import PluginManager
        return PluginManager
    raise AttributeError(name)

__all__ = [
    "CompatibilityError",
    "FeatureNotAvailableError",
    "MetadataError",
    "PluginLoadError",
    "PluginManager",
    "PluginRuntimeError",
    "RequirementSecurityError",
    "StaticPluginMetadata",
    "read_metadata",
]
