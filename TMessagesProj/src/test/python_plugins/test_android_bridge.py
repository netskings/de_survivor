import tempfile
import unittest
from pathlib import Path

from plugin_runtime import android_bridge


class AndroidBridgeTests(unittest.TestCase):
    def tearDown(self):
        android_bridge.shutdown()

    def test_java_facing_shapes_and_lifecycle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "bridge.py"
            source.write_text(
                '''
from base_plugin import BasePlugin, HookResult, HookStrategy
from ui.settings import Switch, Text
__id__="bridge_plugin"\n__name__="Bridge"
class P(BasePlugin):
 def on_plugin_load(self): self.add_on_send_message_hook()
 def on_send_message_hook(self, account, params):
  params["account"] = account
  return HookResult(HookStrategy.MODIFY, params=params)
 def create_settings(self):
  return [Switch("on", "On", True), Text("More", create_sub_fragment=lambda: [Switch("nested", "Nested", False)])]
''',
                encoding="utf-8",
            )
            android_bridge.initialize(
                str(root / "plugins"), str(root / "state"), "12.8.1", "1.4.3.10"
            )
            descriptor = android_bridge.install(str(source))
            self.assertEqual("bridge_plugin", descriptor["id"])
            android_bridge.set_enabled("bridge_plugin", True)
            result = android_bridge.dispatch_send(4, {})
            self.assertEqual(["MODIFY", {"account": 4}], result)
            # Java periodically clears menu contributions before rebuilding
            # them. This bridge operation must not unregister unrelated hooks.
            android_bridge.remove_menu_items_by_plugin_id("bridge_plugin")
            result = android_bridge.dispatch_send(5, {})
            self.assertEqual(["MODIFY", {"account": 5}], result)
            serialized = android_bridge.serialize_settings("bridge_plugin")
            self.assertEqual("switch", serialized[0]["kind"])
            self.assertTrue(android_bridge.setting_changed("bridge_plugin", 0, False))
            nested = android_bridge.serialize_sub_settings("bridge_plugin", 1)
            self.assertEqual("1/0", nested[0]["index"])
            self.assertTrue(android_bridge.setting_changed("bridge_plugin", "1/0", True))


if __name__ == "__main__":
    unittest.main()
