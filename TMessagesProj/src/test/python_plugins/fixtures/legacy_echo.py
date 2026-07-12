from base_plugin import BasePlugin, HookResult, HookStrategy
from ui.settings import Header, Switch

__id__ = "legacy_echo"
__name__ = "Legacy Echo"
__description__ = "Reference .py plugin"
__author__ = "tests"
__version__ = "1.0"
__sdk_version__ = ">=1.4.3.10"


class LegacyEchoPlugin(BasePlugin):
    def on_plugin_load(self):
        self.events = ["load"]
        self.add_on_send_message_hook(priority=10)

    def on_plugin_unload(self):
        self.events.append("unload")

    def on_send_message_hook(self, account, params):
        params["echo"] = account
        return HookResult(HookStrategy.MODIFY, params=params)

    def create_settings(self):
        return [Header("General"), Switch("enabled", "Enabled", True)]
