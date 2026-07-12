"""Runtime-specific exceptions exposed to the Android bridge and tests."""


class PluginRuntimeError(Exception):
    """Base exception for deterministic plugin runtime failures."""


class MetadataError(PluginRuntimeError, ValueError):
    """Plugin metadata is missing, dynamic, or has an invalid type/value."""


class CompatibilityError(PluginRuntimeError):
    """A plugin's app or SDK version constraint is not satisfied."""


class PluginLoadError(PluginRuntimeError):
    """A statically valid plugin could not be imported or instantiated."""


class RequirementSecurityError(PluginRuntimeError):
    """A wheel is not a universal pure-Python wheel or is unsafe to extract."""


class FeatureNotAvailableError(NotImplementedError, PluginRuntimeError):
    """A deliberately phase-gated API was used before its implementation stage."""


def reflection_phase_error(feature: str) -> FeatureNotAvailableError:
    return FeatureNotAvailableError(
        f"{feature} belongs to the reflection/Xposed/class-proxy compatibility "
        "stage and is not implemented by the high-level plugin runtime"
    )
