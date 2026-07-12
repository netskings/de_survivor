import builtins
import shutil
import tempfile
import unittest
from pathlib import Path

from base_plugin import HookStrategy
from plugin_runtime import services
from plugin_runtime.manager import PluginManager
from plugin_runtime.storage import MemoryErrorJournal, MemorySettingsBackend, MemoryStateBackend


FIXTURES = Path(__file__).with_name("fixtures")


class RuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.plugins = root / "plugins"
        self.state = root / "state"
        self.plugins.mkdir()
        self.state_backend = MemoryStateBackend()
        self.settings = MemorySettingsBackend()
        self.journal = MemoryErrorJournal()
        self.manager = PluginManager(
            self.plugins,
            self.state,
            "12.8.1",
            state_backend=self.state_backend,
            settings_backend=self.settings,
            error_journal=self.journal,
            failure_threshold=3,
        )

    def tearDown(self):
        self.manager.shutdown()
        services.clear()
        self.temp.cleanup()
        for name in (
            "PLUGIN_TEST_EVENTS",
            "PLUGIN_REQUEST_CALLBACK",
            "PLUGIN_NOTIFICATION_DELEGATE",
            "PLUGIN_CONSTRUCTOR_CALLBACK",
            "PLUGIN_RAW_CALLBACK",
        ):
            if hasattr(builtins, name):
                delattr(builtins, name)

    def copy_fixture(self, name):
        destination = self.plugins / name
        shutil.copyfile(FIXTURES / name, destination)
        return destination

    def enable(self, plugin_id):
        self.manager.discover()
        self.manager.set_enabled(plugin_id, True)
        return self.manager.records[plugin_id]

    def test_py_load_hook_settings_and_disable_lifecycle(self):
        self.copy_fixture("legacy_echo.py")
        record = self.enable("legacy_echo")
        self.assertTrue(record.initialized)
        payload = {}
        result = self.manager.dispatch_send(2, payload)
        self.assertIs(HookStrategy.MODIFY, result.strategy)
        self.assertEqual(2, payload["echo"])
        self.assertEqual(2, len(self.manager.serialize_settings("legacy_echo")))
        instance = record.instance
        self.manager.set_enabled("legacy_echo", False)
        self.assertEqual(["load", "unload"], instance.events)

    def test_plugin_extension_and_forward_cancel_regression(self):
        self.copy_fixture("legacy_cancel.plugin")
        self.enable("legacy_cancel")
        result = self.manager.dispatch_pre_request("TL_messages_forwardMessages", 0, object())
        self.assertIs(HookStrategy.CANCEL, result.strategy)
        mismatch = self.manager.dispatch_pre_request("TL_messages_sendMessage", 0, object())
        self.assertIs(HookStrategy.DEFAULT, mismatch.strategy)

    def test_installing_update_unloads_old_instance_and_starts_disabled(self):
        self.copy_fixture("legacy_echo.py")
        old_record = self.enable("legacy_echo")
        old_instance = old_record.instance
        replacement = self.state / "replacement.py"
        replacement.write_text(
            '''
from base_plugin import BasePlugin
__id__="legacy_echo"
__name__="Legacy Echo Updated"
__version__="2.0"
class Updated(BasePlugin): pass
''',
            encoding="utf-8",
        )
        descriptor = self.manager.install(replacement)
        self.assertEqual(["load", "unload"], old_instance.events)
        self.assertFalse(descriptor["enabled"])
        self.assertFalse(descriptor["initialized"])
        self.assertEqual("2.0", descriptor["version"])

    def test_priority_modify_final_and_stable_order(self):
        builtins.PLUGIN_TEST_EVENTS = []
        template = '''
import builtins
from base_plugin import BasePlugin, HookResult, HookStrategy
__id__={plugin_id!r}\n__name__={plugin_id!r}
class P(BasePlugin):
 def on_plugin_load(self): self.add_on_send_message_hook(priority={priority})
 def on_send_message_hook(self, account, params):
  builtins.PLUGIN_TEST_EVENTS.append({plugin_id!r})
  params.append({plugin_id!r})
  return HookResult(HookStrategy.{strategy}, params=params)
'''
        for plugin_id, priority, strategy in (("first", 10, "MODIFY"), ("second", 10, "MODIFY_FINAL"), ("late", 0, "MODIFY")):
            (self.plugins / f"{plugin_id}.py").write_text(template.format(plugin_id=plugin_id, priority=priority, strategy=strategy), encoding="utf-8")
        self.manager.discover()
        for plugin_id in ("first", "second", "late"):
            self.manager.set_enabled(plugin_id, True)
        payload = []
        result = self.manager.dispatch_send(0, payload)
        self.assertIs(HookStrategy.MODIFY_FINAL, result.strategy)
        self.assertEqual(["first", "second"], builtins.PLUGIN_TEST_EVENTS)
        self.assertEqual(["first", "second"], payload)

    def test_system_exit_isolated_and_auto_disables(self):
        self.copy_fixture("legacy_crash.py")
        self.copy_fixture("legacy_echo.py")
        self.enable("legacy_crash")
        self.enable("legacy_echo")
        for _ in range(3):
            payload = {}
            self.manager.dispatch_send(1, payload)
            self.assertEqual(1, payload["echo"])
        crash = self.manager.describe("legacy_crash")
        self.assertFalse(crash["enabled"])
        self.assertEqual(3, crash["crash_count"])
        self.assertEqual(3, len(self.journal.entries))
        self.assertTrue(self.journal.entries[-1]["auto_disabled"])

    def test_previous_interrupted_start_enters_safe_mode(self):
        self.state_backend.set_global("startup_loading", True)
        manager = PluginManager(
            self.plugins,
            self.state / "second",
            "12.8.1",
            state_backend=self.state_backend,
            settings_backend=MemorySettingsBackend(),
            error_journal=MemoryErrorJournal(),
        )
        self.assertTrue(manager.safe_mode)

    def test_async_callback_failures_use_the_same_crash_barrier(self):
        callbacks = []
        services.configure(run_on_ui_thread=lambda callback, delay=0: callbacks.append(callback))
        (self.plugins / "async_crash.py").write_text(
            '''
from android_utils import run_on_ui_thread
from base_plugin import BasePlugin
__id__="async_crash"
__name__="Async crash"
class P(BasePlugin):
 def on_plugin_load(self):
  for _ in range(3): run_on_ui_thread(self.fail)
 def fail(self): raise RuntimeError("async failure")
''',
            encoding="utf-8",
        )
        self.enable("async_crash")
        self.assertEqual(3, len(callbacks))
        for callback in callbacks:
            callback()
        descriptor = self.manager.describe("async_crash")
        self.assertFalse(descriptor["enabled"])
        self.assertEqual(3, descriptor["crash_count"])
        self.assertEqual(
            "android_utils.run_on_ui_thread", self.journal.entries[-1]["callback"]
        )

    def test_raw_java_round_trip_callback_is_attributed_and_auto_disabled(self):
        (self.plugins / "raw_callback.py").write_text(
            '''
import builtins
from base_plugin import BasePlugin
__id__="raw_callback"
__name__="Raw callback"
class P(BasePlugin):
 def on_plugin_load(self): builtins.PLUGIN_RAW_CALLBACK = self.fail
 def fail(self, value): raise SystemExit(f"raw callback failure: {value}")
''',
            encoding="utf-8",
        )
        self.enable("raw_callback")
        callback = builtins.PLUGIN_RAW_CALLBACK
        for _ in range(3):
            self.assertIsNone(
                self.manager.invoke_discovered_callback(
                    "PluginsController.callback", callback, "value"
                )
            )
        descriptor = self.manager.describe("raw_callback")
        self.assertFalse(descriptor["enabled"])
        self.assertEqual(3, descriptor["crash_count"])
        self.assertEqual(
            "PluginsController.callback", self.journal.entries[-1]["callback"]
        )

    def test_menu_cleanup_does_not_unregister_outgoing_message_hook(self):
        (self.plugins / "menu_hook.py").write_text(
            '''
from base_plugin import BasePlugin, HookResult, HookStrategy, MenuItemData, MenuItemType
__id__="menu_hook"
__name__="Menu hook"
class P(BasePlugin):
 def on_plugin_load(self):
  self.add_on_send_message_hook()
  self.add_menu_item(MenuItemData(MenuItemType.CHAT_ACTION_MENU, "Action", lambda context: None, item_id="menu-hook-action"))
 def on_send_message_hook(self, account, params):
  params["hook_calls"] = params.get("hook_calls", 0) + 1
  return HookResult(HookStrategy.MODIFY, params=params)
''',
            encoding="utf-8",
        )
        self.enable("menu_hook")
        self.assertIn("menu-hook-action", self.manager._menus)

        self.manager._cleanup_plugin_menu_items("menu_hook")

        self.assertNotIn("menu-hook-action", self.manager._menus)
        payload = {}
        result = self.manager.dispatch_send(0, payload)
        self.assertIs(HookStrategy.MODIFY, result.strategy)
        self.assertEqual(1, payload["hook_calls"])

    def test_request_and_notification_delegates_share_crash_barrier(self):
        (self.plugins / "delegate_crash.py").write_text(
            '''
import builtins
from base_plugin import BasePlugin
from client_utils import NotificationCenterDelegate, RequestCallback
__id__="delegate_crash"
__name__="Delegate crash"
class Delegate(NotificationCenterDelegate):
 def didReceivedNotification(self, id, account, args):
  raise RuntimeError("notification failure")
class P(BasePlugin):
 def on_plugin_load(self):
  builtins.PLUGIN_REQUEST_CALLBACK = RequestCallback(self.request_callback)
  builtins.PLUGIN_NOTIFICATION_DELEGATE = Delegate()
 def request_callback(self, response, error):
  raise RuntimeError("request failure")
''',
            encoding="utf-8",
        )
        self.enable("delegate_crash")

        self.assertIsNone(builtins.PLUGIN_REQUEST_CALLBACK.run(None, None))
        self.assertIsNone(
            builtins.PLUGIN_NOTIFICATION_DELEGATE.didReceivedNotification(
                1, 0, ()
            )
        )

        descriptor = self.manager.describe("delegate_crash")
        self.assertTrue(descriptor["enabled"])
        self.assertEqual(2, descriptor["crash_count"])
        self.assertEqual(
            [
                "client_utils.RequestCallback",
                "client_utils.NotificationCenterDelegate",
            ],
            [entry["callback"] for entry in self.journal.entries],
        )

    def test_constructor_runs_with_metadata_and_plugin_owner_context(self):
        (self.plugins / "constructor_context.py").write_text(
            '''
import builtins
from base_plugin import BasePlugin
from client_utils import RequestCallback
__id__="constructor_context"
__name__="Constructor context"
class P(BasePlugin):
 def __init__(self):
  super().__init__()
  self.set_setting("owner", self.id)
  builtins.PLUGIN_CONSTRUCTOR_CALLBACK = RequestCallback(self.fail)
 def fail(self, response, error):
  raise RuntimeError("constructor callback failure")
''',
            encoding="utf-8",
        )

        record = self.enable("constructor_context")

        self.assertEqual(
            "constructor_context",
            record.instance.get_setting("owner"),
        )
        self.assertIsNone(builtins.PLUGIN_CONSTRUCTOR_CALLBACK.run(None, None))
        self.assertEqual(1, self.manager.describe("constructor_context")["crash_count"])
        self.assertEqual("client_utils.RequestCallback", self.journal.entries[-1]["callback"])

    def test_setting_serialization_errors_are_attributed_and_auto_disabled(self):
        (self.plugins / "bad_setting.py").write_text(
            '''
from base_plugin import BasePlugin
from ui.settings import Selector
__id__="bad_setting"
__name__="Bad setting"
class BadLabel:
 def __str__(self): raise RuntimeError("bad label")
class P(BasePlugin):
 def create_settings(self):
  return [Selector("choice", "Choice", 0, [BadLabel()])]
''',
            encoding="utf-8",
        )
        self.enable("bad_setting")

        for _ in range(3):
            self.assertEqual([], self.manager.serialize_settings("bad_setting"))

        descriptor = self.manager.describe("bad_setting")
        self.assertFalse(descriptor["enabled"])
        self.assertEqual(3, descriptor["crash_count"])
        self.assertEqual("setting.serialize", self.journal.entries[-1]["callback"])


if __name__ == "__main__":
    unittest.main()
