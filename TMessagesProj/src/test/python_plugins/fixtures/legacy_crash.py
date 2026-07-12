from base_plugin import BasePlugin

__id__ = "legacy_crash"
__name__ = "Legacy Crash Isolation"


class CrashingPlugin(BasePlugin):
    def on_plugin_load(self):
        self.add_on_send_message_hook(priority=100)

    def on_send_message_hook(self, account, params):
        raise SystemExit("reference crash")
