import dataclasses
import importlib
import importlib.abc
import inspect
import re
import tempfile
import unittest
from pathlib import Path

import android_utils
import client_utils as client_module
import file_utils
import hook_utils
import markdown_utils
import plugin_settings
from _sdk_version import SafeModeImporter
from base_plugin import BasePlugin, HookResult, HookStrategy, PluginMetadata
from dev_server import DevServer
from client_utils import (
    SEND_MESSAGE_PARAM_DEFAULTS,
    SEND_MESSAGE_PARAM_FIELDS,
    edit_message,
    send_message,
    send_photo,
)
from plugin_runtime import services
from ui import alert, bulletin, dialog
import ui.settings as settings_module
from ui.settings import Custom, Divider, EditText, Header, Input, Selector, SimpleSettingFactory, Switch, Text


class ContractTests(unittest.TestCase):
    def assert_signature_contract(
        self,
        target,
        names,
        *,
        keyword_only=(),
        var_keyword=(),
        defaults=None,
    ):
        signature = inspect.signature(target)
        self.assertEqual(list(names), list(signature.parameters))
        for name in keyword_only:
            self.assertIs(
                inspect.Parameter.KEYWORD_ONLY,
                signature.parameters[name].kind,
                name,
            )
        for name in var_keyword:
            self.assertIs(
                inspect.Parameter.VAR_KEYWORD,
                signature.parameters[name].kind,
                name,
            )
        for name, expected in (defaults or {}).items():
            self.assertEqual(expected, signature.parameters[name].default, name)

    def tearDown(self):
        services.clear()

    def test_hook_result_field_order_and_defaults(self):
        self.assertEqual(
            ["strategy", "request", "response", "update", "updates", "error", "params"],
            [field.name for field in dataclasses.fields(HookResult)],
        )
        result = HookResult()
        self.assertIs(HookStrategy.DEFAULT, result.strategy)
        self.assertTrue(all(getattr(result, name) is None for name in ("request", "response", "update", "updates", "error", "params")))
        self.assertIs(HookStrategy.CANCEL, HookStrategy.BLOCK)
        self.assertIs(HookStrategy.DEFAULT, HookStrategy.CONTINUE)

    def test_plugin_metadata_accepts_historical_seven_args(self):
        metadata = PluginMetadata("id", "name", "desc", "author", "1", "icon", "12")
        self.assertEqual([], metadata.requirements)

    def test_plugin_metadata_union_supports_seven_and_eight_arguments(self):
        parameter = inspect.signature(PluginMetadata).parameters["requirements"]
        self.assertIsNot(inspect.Parameter.empty, parameter.default)
        self.assertEqual(
            [], PluginMetadata("i", "n", "d", "a", "v", "x", "m").requirements
        )
        self.assertEqual(
            ["packaging"],
            PluginMetadata(
                "i", "n", "d", "a", "v", "x", "m", ["packaging"]
            ).requirements,
        )

    def test_markdown_utils_owns_the_frozen_legacy_entity_surface(self):
        self.assertEqual(
            ["type", "offset", "length", "language", "url", "document_id"],
            list(inspect.signature(markdown_utils.RawEntity).parameters),
        )
        self.assertEqual(
            [
                "CODE", "PRE", "STRIKETHROUGH", "TEXT_LINK", "BOLD",
                "ITALIC", "UNDERLINE", "SPOILER", "CUSTOM_EMOJI",
            ],
            list(markdown_utils.TLEntityType.__members__),
        )
        self.assertEqual("markdown_utils", markdown_utils.RawEntity.__module__)
        self.assertEqual("markdown_utils", markdown_utils.TLEntityType.__module__)
        parsed = markdown_utils.parse_markdown("*bold*")
        self.assertIsInstance(parsed, markdown_utils.ParsedMessage)
        self.assertEqual("bold", parsed.text)
        self.assertIsInstance(parsed.entities[0], markdown_utils.RawEntity)
        self.assertIs(
            markdown_utils.TLEntityType.BOLD, parsed.entities[0].type
        )

    def test_sdk_importer_and_dev_server_symbols_match_the_stub_surface(self):
        self.assertTrue(issubclass(SafeModeImporter, importlib.abc.MetaPathFinder))
        self.assertTrue(issubclass(SafeModeImporter, importlib.abc.Loader))
        self.assertTrue(hasattr(DevServer, "PYCHARM_DEBUGGER_DIR"))

    def test_base_plugin_phase_one_signature_snapshot(self):
        expected = {
            "on_plugin_load": ["self"],
            "on_plugin_unload": ["self"],
            "create_settings": ["self"],
            "on_app_event": ["self", "event_type"],
            "pre_request_hook": ["self", "request_name", "account", "request"],
            "post_request_hook": ["self", "request_name", "account", "response", "error"],
            "on_update_hook": ["self", "update_name", "account", "update"],
            "on_updates_hook": ["self", "container_name", "account", "updates"],
            "on_send_message_hook": ["self", "account", "params"],
            "add_hook": ["self", "name", "match_substring", "priority"],
            "add_on_send_message_hook": ["self", "priority"],
            "remove_hook": ["self", "name"],
            "get_setting": ["self", "key", "default"],
            "set_setting": ["self", "key", "value", "reload_settings"],
            "export_settings": ["self"],
            "import_settings": ["self", "settings", "reload_settings"],
            "log": ["self", "message"],
            "add_menu_item": ["self", "menu_item_data"],
            "remove_menu_item": ["self", "item_id"],
        }
        for name, parameters in expected.items():
            with self.subTest(name=name):
                self.assert_signature_contract(getattr(BasePlugin, name), parameters)
        self.assertFalse(inspect.signature(BasePlugin.add_hook).parameters["match_substring"].default)
        self.assertEqual(0, inspect.signature(BasePlugin.add_hook).parameters["priority"].default)
        self.assertEqual(0, inspect.signature(BasePlugin.add_on_send_message_hook).parameters["priority"].default)
        self.assertIsNone(inspect.signature(BasePlugin.get_setting).parameters["default"].default)
        self.assertFalse(inspect.signature(BasePlugin.set_setting).parameters["reload_settings"].default)
        self.assertTrue(inspect.signature(BasePlugin.import_settings).parameters["reload_settings"].default)

    def test_phase_two_names_keep_official_signatures_while_gated(self):
        expected = {
            "hook_method": ["self", "method_or_constructor", "xposed_hook", "priority", "before", "after", "before_filters", "after_filters"],
            "hook_all_methods": ["self", "hook_class", "method_name", "xposed_hook", "priority", "before", "after", "before_filters", "after_filters"],
            "hook_all_constructors": ["self", "hook_class", "xposed_hook", "priority", "before", "after", "before_filters", "after_filters"],
            "unhook_method": ["self", "unhook"],
        }
        for name, parameters in expected.items():
            with self.subTest(name=name):
                self.assert_signature_contract(
                    getattr(BasePlugin, name),
                    parameters,
                    keyword_only=(parameters[-4:] if name != "unhook_method" else ()),
                )

    def test_settings_stable_signatures_and_defaults(self):
        contracts = {
            Switch: ["key", "text", "default", "subtext", "icon", "on_change", "on_long_click", "link_alias"],
            Selector: ["key", "text", "default", "items", "icon", "on_change", "on_long_click", "link_alias"],
            Input: ["key", "text", "default", "subtext", "icon", "on_change", "on_long_click", "link_alias"],
            Text: ["text", "subtext", "icon", "accent", "red", "on_click", "create_sub_fragment", "on_long_click", "link_alias"],
            Header: ["text"],
            Divider: ["text"],
            EditText: ["key", "hint", "default", "multiline", "max_length", "mask", "on_change"],
            Custom: ["item", "view", "factory", "factory_args", "on_click", "on_long_click", "create_sub_fragment", "link_alias"],
        }
        for target, parameters in contracts.items():
            with self.subTest(target=target.__name__):
                self.assert_signature_contract(target, parameters)
        self.assert_signature_contract(
            SimpleSettingFactory,
            [
                "create_view", "bind_view", "is_clickable", "is_shadow",
                "create_item", "on_click", "on_long_click", "attached_view",
                "equals", "content_equals",
            ],
            keyword_only=(
                "is_clickable", "is_shadow", "create_item", "on_click",
                "on_long_click", "attached_view", "equals", "content_equals",
            ),
        )
        self.assertEqual("", inspect.signature(Input).parameters["default"].default)
        self.assertFalse(inspect.signature(EditText).parameters["multiline"].default)
        self.assertEqual(0, inspect.signature(EditText).parameters["max_length"].default)
        self.assertEqual("", inspect.signature(Text).parameters["subtext"].default)
        self.assertEqual("", Divider().text)
        factory = SimpleSettingFactory(lambda *args: None, lambda *args: None)
        self.assertFalse(factory.is_clickable)
        for legacy_name in (
            "pyobject_type",
            "object_array_type",
            "typehelper",
            "pyobject_call_method",
        ):
            self.assertTrue(hasattr(settings_module, legacy_name), legacy_name)

    def test_text_supports_unambiguous_exteragram_utils_positional_overload(self):
        on_click = lambda view: None
        subpage = lambda: []
        legacy = Text("x", "msg_info", True, False, on_click, subpage)
        self.assertEqual("", legacy.subtext)
        self.assertEqual("msg_info", legacy.icon)
        self.assertTrue(legacy.accent)
        self.assertFalse(legacy.red)
        self.assertIs(on_click, legacy.on_click)
        self.assertIs(subpage, legacy.create_sub_fragment)

        keyword_legacy = Text(
            "x", "msg_info", True, red=True, on_click=on_click
        )
        self.assertEqual("msg_info", keyword_legacy.icon)
        self.assertTrue(keyword_legacy.accent)
        self.assertTrue(keyword_legacy.red)
        self.assertIs(on_click, keyword_legacy.on_click)

        modern = Text("x", subtext="s", icon="msg_info", accent=True)
        self.assertEqual(
            ("s", "msg_info", True),
            (modern.subtext, modern.icon, modern.accent),
        )

    def test_android_file_hook_and_settings_module_signatures(self):
        contracts = {
            android_utils.OnClickListener: ["fn"],
            android_utils.OnLongClickListener: ["fn"],
            android_utils.run_on_ui_thread: ["func", "delay"],
            android_utils.log: ["data"],
            android_utils.copy_to_clipboard: ["text"],
            android_utils.R: ["fn"],
            file_utils.get_plugins_dir: [],
            file_utils.get_cache_dir: [],
            file_utils.get_files_dir: [],
            file_utils.get_images_dir: [],
            file_utils.get_videos_dir: [],
            file_utils.get_audios_dir: [],
            file_utils.get_documents_dir: [],
            file_utils.read_file: ["file_path"],
            file_utils.write_file: ["file_path", "content"],
            file_utils.delete_file: ["file_path"],
            file_utils.ensure_dir_exists: ["dir_path"],
            file_utils.list_dir: ["path", "recursive", "include_files", "include_dirs", "extensions"],
            hook_utils.find_class: ["class_name"],
            hook_utils.get_private_field: ["obj", "field_name"],
            hook_utils.set_private_field: ["obj", "field_name", "new_value"],
            hook_utils.get_static_private_field: ["clazz", "field_name"],
            hook_utils.set_static_private_field: ["clazz", "field_name", "new_value"],
            plugin_settings.init: ["plugins_dir_path", "all_shared_prefs"],
            plugin_settings.get_setting: ["plugin_id", "key", "default"],
            plugin_settings.set_setting: ["plugin_id", "key", "value"],
            plugin_settings.clear_settings: ["plugin_id"],
            plugin_settings.get_all_settings: ["plugin_id"],
            plugin_settings.set_all_settings: ["plugin_id", "settings"],
        }
        for target, parameters in contracts.items():
            with self.subTest(target=getattr(target, "__qualname__", repr(target))):
                self.assert_signature_contract(target, parameters)
        self.assertEqual(0, inspect.signature(android_utils.run_on_ui_thread).parameters["delay"].default)
        list_dir = inspect.signature(file_utils.list_dir).parameters
        self.assertEqual((False, True, False, None), tuple(
            list_dir[name].default
            for name in ("recursive", "include_files", "include_dirs", "extensions")
        ))
        self.assertTrue(hasattr(hook_utils, "JavaClass"))
        self.assertTrue(hasattr(hook_utils, "JavaObject"))

    def test_client_utils_public_signature_snapshot(self):
        no_arg = [
            "get_last_fragment", "get_account_instance", "get_messages_controller",
            "get_contacts_controller", "get_media_data_controller",
            "get_connections_manager", "get_location_controller",
            "get_notifications_controller", "get_messages_storage",
            "get_send_messages_helper", "get_file_loader", "get_secret_chat_helper",
            "get_download_controller", "get_notifications_settings",
            "get_notification_center", "get_media_controller", "get_user_config",
        ]
        for name in no_arg:
            with self.subTest(name=name):
                self.assert_signature_contract(getattr(client_module, name), [])
        contracts = {
            client_module.get_queue_by_name: (["queue_name"], (), ()),
            client_module.run_on_queue: (["fn", "queue_name", "delay"], (), ()),
            client_module.RequestCallback: (["fn"], (), ()),
            client_module.send_request: (["request", "fn"], (), ()),
            client_module.send_message: (["params", "parse_mode"], (), ()),
            client_module.send_text: (["peer", "text", "parse_mode", "kwargs"], ("parse_mode",), ("kwargs",)),
            client_module.send_photo: (["peer", "file_path", "caption", "high_quality", "parse_mode", "kwargs"], ("parse_mode",), ("kwargs",)),
            client_module.send_document: (["peer", "file_path", "caption", "parse_mode", "kwargs"], ("parse_mode",), ("kwargs",)),
            client_module.send_video: (["peer", "file_path", "caption", "parse_mode", "kwargs"], ("parse_mode",), ("kwargs",)),
            client_module.send_audio: (["peer", "file_path", "caption", "parse_mode", "kwargs"], ("parse_mode",), ("kwargs",)),
            client_module.edit_message: (["message_obj", "text", "file_path", "with_spoiler", "parse_mode", "kwargs"], ("parse_mode",), ("kwargs",)),
        }
        for target, (parameters, keyword_only, var_keyword) in contracts.items():
            with self.subTest(target=getattr(target, "__qualname__", repr(target))):
                self.assert_signature_contract(
                    target,
                    parameters,
                    keyword_only=keyword_only,
                    var_keyword=var_keyword,
                )
        run_queue = inspect.signature(client_module.run_on_queue).parameters
        self.assertEqual(client_module.PLUGINS_QUEUE, run_queue["queue_name"].default)
        self.assertEqual(0, run_queue["delay"].default)

    def test_dialog_is_additive_alert_alias(self):
        self.assertIs(alert.AlertDialogBuilder, dialog.AlertDialogBuilder)

    def test_alert_constants_match_telegram_values(self):
        builder = alert.AlertDialogBuilder
        self.assertEqual(0, builder.ALERT_TYPE_MESSAGE)
        self.assertEqual(2, builder.ALERT_TYPE_LOADING)
        self.assertEqual(3, builder.ALERT_TYPE_SPINNER)
        self.assertEqual((-1, -2, -3), (
            builder.BUTTON_POSITIVE,
            builder.BUTTON_NEGATIVE,
            builder.BUTTON_NEUTRAL,
        ))

    def test_alert_builder_signature_snapshot(self):
        self.assert_signature_contract(
            alert.AlertDialogBuilder,
            ["context", "progress_style", "resources_provider"],
            defaults={"progress_style": 0, "resources_provider": None},
        )
        contracts = {
            "get_context": ["self"],
            "set_title": ["self", "title"],
            "set_message": ["self", "message"],
            "set_message_text_view_clickable": ["self", "clickable"],
            "set_positive_button": ["self", "text", "listener"],
            "set_negative_button": ["self", "text", "listener"],
            "set_neutral_button": ["self", "text", "listener"],
            "make_button_red": ["self", "button_type"],
            "set_on_back_button_listener": ["self", "listener"],
            "set_view": ["self", "view", "height"],
            "set_items": ["self", "items", "listener", "icons"],
            "set_on_dismiss_listener": ["self", "listener"],
            "set_on_cancel_listener": ["self", "listener"],
            "set_top_image": ["self", "res_id", "background_color"],
            "set_top_drawable": ["self", "drawable", "background_color"],
            "set_top_animation": ["self", "res_id", "size", "auto_repeat", "background_color", "layer_colors"],
            "set_top_animation_is_new": ["self", "is_new"],
            "set_dim_enabled": ["self", "enabled"],
            "set_dialog_button_color_key": ["self", "theme_key"],
            "set_blurred_background": ["self", "blur", "blur_behind_if_possible"],
            "create": ["self"],
            "show": ["self"],
            "dismiss": ["self"],
            "get_dialog": ["self"],
            "get_button": ["self", "button_type"],
            "set_progress": ["self", "progress"],
            "set_cancelable": ["self", "cancelable"],
            "set_canceled_on_touch_outside": ["self", "cancel"],
        }
        for name, parameters in contracts.items():
            with self.subTest(name=name):
                self.assert_signature_contract(
                    getattr(alert.AlertDialogBuilder, name), parameters
                )
        self.assertEqual(-2, inspect.signature(alert.AlertDialogBuilder.set_view).parameters["height"].default)
        self.assertTrue(inspect.signature(alert.AlertDialogBuilder.set_blurred_background).parameters["blur_behind_if_possible"].default)

    def test_bulletin_signature_snapshot(self):
        contracts = {
            "show_info": ["message", "fragment"],
            "show_error": ["message", "fragment"],
            "show_success": ["message", "fragment"],
            "show_simple": ["text", "icon_res_id", "fragment"],
            "show_two_line": ["title", "subtitle", "icon_res_id", "fragment"],
            "show_with_button": ["text", "icon_res_id", "button_text", "on_click", "fragment", "duration"],
            "show_undo": ["text", "on_undo", "on_action", "subtitle", "fragment"],
            "show_copied_to_clipboard": ["message", "fragment"],
            "show_link_copied": ["is_private_link_info", "fragment"],
            "show_file_saved_to_gallery": ["is_video", "amount", "fragment"],
            "show_file_saved_to_downloads": ["file_type_enum_name", "amount", "fragment"],
        }
        for name, parameters in contracts.items():
            with self.subTest(name=name):
                self.assert_signature_contract(
                    getattr(bulletin.BulletinHelper, name), parameters
                )
        self.assertEqual(
            bulletin.BulletinHelper.DURATION_PROLONG,
            inspect.signature(bulletin.BulletinHelper.show_with_button)
            .parameters["duration"].default,
        )

    def test_simple_setting_factory_host_facade_and_arguments(self):
        calls = []

        def create_view(*args):
            calls.append(("create", args))
            return "view"

        def bind_view(*args):
            calls.append(("bind", args))

        factory = SimpleSettingFactory(create_view, bind_view, is_clickable=True)
        self.assertIs(factory, factory.java)
        self.assertIs(factory, factory.instance.java)
        self.assertEqual("view", factory.instance.java.create_view("context", "list"))
        factory.instance.java.bind_view("view", "item")

        subpage = lambda: []
        setting = factory("first", 2, create_sub_fragment=subpage, link_alias="alias")
        self.assertIsInstance(setting, Custom)
        self.assertIs(factory, setting.factory)
        self.assertEqual(("first", 2), setting.factory_args)
        self.assertIs(subpage, setting.create_sub_fragment)
        self.assertEqual("alias", setting.link_alias)
        self.assertEqual([
            ("create", ("context", "list")),
            ("bind", ("view", "item")),
        ], calls)

    def test_missing_media_file_is_rejected_before_host_dispatch(self):
        calls = []
        services.configure(send_message=lambda *args: calls.append(args))
        missing = Path(tempfile.gettempdir()) / "telegram-plugin-missing-photo.jpg"
        with self.assertRaisesRegex(
            ValueError, rf"^File not found: {re.escape(str(missing))}$"
        ):
            send_photo(123, str(missing), "caption", high_quality=True)
        self.assertEqual([], calls)

        with tempfile.NamedTemporaryFile(suffix=".jpg") as media:
            services.configure(
                send_message=lambda params, parse_mode: (params, parse_mode)
            )
            params, parse_mode = send_photo(
                123, media.name, "caption", high_quality=True
            )
        self.assertEqual(123, params["peer"])
        self.assertEqual(media.name, params["file_path"])
        self.assertTrue(params["high_quality"])
        self.assertEqual("photo", params["media_type"])
        self.assertIsNone(parse_mode)

    def test_legacy_validation_errors_are_exact(self):
        with self.assertRaisesRegex(
            ValueError,
            r"^Invalid parse mode: MARKDOWNV2\. Must be HTML or MARKDOWN\.$",
        ):
            send_message({"peer": 1, "message": "x"}, "MARKDOWNV2")
        with self.assertRaisesRegex(
            ValueError, r"^Unknown SendMessageParams parameter 'unknown'$"
        ):
            send_message({"peer": 1, "message": "x", "unknown": True})
        with self.assertRaisesRegex(
            ValueError, r"^Unknown SendMessageParams parameter 'file_path'$"
        ):
            send_message({"peer": 1, "file_path": "media.jpg"})

        missing = Path(tempfile.gettempdir()) / "telegram-plugin-missing-edit.jpg"
        with self.assertRaisesRegex(
            ValueError, rf"^Media file not found at path: {re.escape(str(missing))}$"
        ):
            edit_message(object(), file_path=str(missing))

    def test_send_message_parameter_snapshot_is_populated(self):
        self.assertIn("replyToMsg", SEND_MESSAGE_PARAM_FIELDS)
        self.assertIn("entities", SEND_MESSAGE_PARAM_FIELDS)
        self.assertTrue(SEND_MESSAGE_PARAM_DEFAULTS["notify"])

    def test_legacy_import_union(self):
        modules = [
            "_sdk_version", "android_utils", "client_utils", "file_utils",
            "hook_utils", "markdown_utils", "plugin_settings", "plugins_manager",
            "ui.settings", "ui.alert", "ui.dialog", "ui.bulletin",
            "extera_utils.classes", "extera_utils.metadata_parser",
            "extera_utils.text_formatting", "utils.metadata_parser", "plugin_api_v2",
        ]
        for module in modules:
            with self.subTest(module=module):
                importlib.import_module(module)


if __name__ == "__main__":
    unittest.main()
