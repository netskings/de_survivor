import importlib.abc


__version__ = "1.4.3.10"
version_str = __version__
version = tuple(int(item) for item in __version__.split("."))
__beta__ = False
beta = __beta__
MIN_APP_BUILD_VERSION = 0
MIN_APP_VERSION = "12.8.1"


class SafeModeImporter(importlib.abc.MetaPathFinder, importlib.abc.Loader):
    IS_OLD_VERSION = False
    I = False
    E = False

    def find_spec(self, fullname, path, target=None):
        return None

    @classmethod
    def c(cls) -> None:
        return None


Z = SafeModeImporter


class X:
    BUILD_VERSION = 0


def check_safemode():
    from plugin_runtime.context import get_manager
    manager = get_manager(required=False)
    return bool(manager and manager.safe_mode)


def setup_hooks() -> None:
    return None


def __start__():
    return None


def __stop__() -> None:
    return None
