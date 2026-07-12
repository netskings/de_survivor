"""Narrow Chaquopy entry point used by the Java PluginManager."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from . import services
from .context import isolate_callback, set_manager
from .manager import PluginManager
from .metadata import assert_compatible, read_metadata

_manager: PluginManager | None = None


def _runtime() -> PluginManager:
    if _manager is None:
        raise RuntimeError("plugin runtime has not been initialized")
    return _manager


def _configure_android_services() -> None:
    try:
        from org.telegram.messenger.plugins import PluginHostServices as Host
    except (ImportError, ModuleNotFoundError):
        return

    def account():
        return Host.getAccountInstance()

    def show_bulletin(kind, positional, keyword):
        def report_failure(message):
            raise RuntimeError(f"bulletin host failure: {message}")

        return Host.showBulletin(
            kind,
            positional,
            keyword,
            isolate_callback(report_failure, "ui.bulletin.show"),
        )

    def send_message(values, parse_mode):
        return Host.runOnUiThread(
            isolate_callback(
                lambda: Host.sendMessage(values, parse_mode),
                "client_utils.send_message",
            ),
            0,
        )

    def edit_message(message, text, file_path, with_spoiler, parse_mode, options):
        return Host.runOnUiThread(
            isolate_callback(
                lambda: Host.editMessage(
                    message, text, file_path, with_spoiler, parse_mode, options
                ),
                "client_utils.edit_message",
            ),
            0,
        )

    services.configure(
        run_on_ui_thread=lambda fn, delay=0: Host.runOnUiThread(fn, int(delay)),
        run_on_queue=lambda fn, queue_name, delay=0: Host.runOnQueue(
            fn, queue_name, int(delay)
        ),
        get_queue_by_name=Host.getQueueByName,
        android_log=Host.log,
        copy_to_clipboard=Host.copyToClipboard,
        plugin_log=lambda plugin_id, message: Host.log(f"[{plugin_id}] {message}"),
        send_request=Host.sendRequest,
        send_message=send_message,
        edit_message=edit_message,
        raw_entity_to_tlrpc=Host.rawEntityToTlRpc,
        get_last_fragment=Host.getLastFragment,
        get_account_instance=account,
        get_messages_controller=lambda: account().getMessagesController(),
        get_contacts_controller=lambda: account().getContactsController(),
        get_media_data_controller=lambda: account().getMediaDataController(),
        get_connections_manager=lambda: account().getConnectionsManager(),
        get_location_controller=lambda: account().getLocationController(),
        get_notifications_controller=lambda: account().getNotificationsController(),
        get_messages_storage=lambda: account().getMessagesStorage(),
        get_send_messages_helper=lambda: account().getSendMessagesHelper(),
        get_file_loader=lambda: account().getFileLoader(),
        get_secret_chat_helper=lambda: account().getSecretChatHelper(),
        get_download_controller=lambda: account().getDownloadController(),
        get_notifications_settings=lambda: account().getNotificationsSettings(),
        get_notification_center=lambda: account().getNotificationCenter(),
        get_media_controller=Host.getMediaController,
        get_user_config=lambda: account().getUserConfig(),
        get_cache_dir=lambda: Host.getCacheDir().getAbsolutePath(),
        get_files_dir=lambda: Host.getFilesDir().getAbsolutePath(),
        get_images_dir=lambda: Host.getMediaDir("image").getAbsolutePath(),
        get_videos_dir=lambda: Host.getMediaDir("video").getAbsolutePath(),
        get_audios_dir=lambda: Host.getMediaDir("audio").getAbsolutePath(),
        get_documents_dir=lambda: Host.getMediaDir("document").getAbsolutePath(),
        reload_plugin_settings=Host.reloadPluginSettings,
        create_alert_dialog=Host.createAlertDialog,
        show_alert_dialog=Host.showAlertDialog,
        dismiss_alert_dialog=Host.dismissAlertDialog,
        get_alert_button=Host.getAlertButton,
        set_alert_progress=Host.setAlertProgress,
        set_alert_cancelable=Host.setAlertCancelable,
        set_alert_canceled_on_touch_outside=Host.setAlertCanceledOnTouchOutside,
        show_bulletin=show_bulletin,
    )


def initialize(
    plugins_dir: str,
    state_dir: str,
    app_version: str,
    sdk_version: str,
) -> list[dict[str, Any]]:
    global _manager
    if _manager is not None:
        _manager.shutdown()
    services.clear()
    _configure_android_services()
    _manager = PluginManager(plugins_dir, state_dir, app_version, sdk_version)
    # Java owns process safe mode and consumes the interrupted-start marker
    # before starting Python. Clear the mirrored Python marker so disabling
    # safe mode cannot leave the runtime silently stuck on the next launch.
    _manager.set_safe_mode(False)
    set_manager(_manager)
    _manager.discover()
    return _manager.list_plugins()


def load_enabled_plugins() -> list[dict[str, Any]]:
    return _runtime().load_enabled_plugins()


def set_safe_mode(enabled: bool) -> None:
    _runtime().set_safe_mode(bool(enabled))


def list_plugins() -> list[dict[str, Any]]:
    manager = _runtime()
    manager.discover()
    return manager.list_plugins()


def install(path: str) -> dict[str, Any]:
    return _runtime().install(path)


def validate_plugin_from_file(path: str) -> dict[str, Any]:
    manager = _runtime()
    metadata = read_metadata(path)
    assert_compatible(metadata, manager.app_version, manager.sdk_version)
    return metadata.to_dict()


def set_enabled(plugin_id: str, enabled: bool) -> dict[str, Any]:
    return _runtime().set_enabled(plugin_id, bool(enabled))


def delete_plugin(plugin_id: str) -> bool:
    return _runtime().delete_plugin(plugin_id)


def invoke_app_event(name: str) -> None:
    _runtime().invoke_app_event(name)


def invoke_external_callback(label: str, callback: Any, *args: Any) -> Any:
    return _runtime().invoke_discovered_callback(label, callback, *args)


def dispatch_send(account: int, params: Any) -> list[Any]:
    result = _runtime().dispatch_send(account, params)
    return [result.strategy.name, result.params]


def dispatch_pre_request(name: str, account: int, request: Any) -> list[Any]:
    result = _runtime().dispatch_pre_request(name, account, request)
    return [result.strategy.name, result.request]


def dispatch_post_request(
    name: str, account: int, response: Any, error: Any
) -> list[Any]:
    result = _runtime().dispatch_post_request(name, account, response, error)
    return [result.strategy.name, result.response, result.error]


def dispatch_update(name: str, account: int, update: Any) -> list[Any]:
    result = _runtime().dispatch_update(name, account, update)
    return [result.strategy.name, result.update]


def dispatch_updates(name: str, account: int, updates: Any) -> list[Any]:
    result = _runtime().dispatch_updates(name, account, updates)
    return [result.strategy.name, result.updates]


def get_settings(plugin_id: str) -> list[Any]:
    return _runtime().create_settings(plugin_id)


def serialize_settings(plugin_id: str) -> list[dict[str, Any]]:
    return _runtime().serialize_settings(plugin_id)


def serialize_sub_settings(plugin_id: str, index: int | str) -> list[dict[str, Any]]:
    return _runtime().serialize_sub_settings(plugin_id, index)


def setting_changed(plugin_id: str, index: int | str, value: Any) -> bool:
    return _runtime().setting_changed(plugin_id, index, value)


def setting_clicked(
    plugin_id: str,
    index: int | str,
    kind: str = "click",
    view: Any = None,
    item: Any = None,
) -> bool:
    return _runtime().setting_clicked(plugin_id, index, kind, view, item)


def create_custom_view(
    plugin_id: str,
    index: int | str,
    context: Any,
    list_view: Any,
    current_account: int,
    class_guid: int,
    resources_provider: Any,
) -> Any:
    return _runtime().create_custom_view(
        plugin_id,
        index,
        context,
        list_view,
        current_account,
        class_guid,
        resources_provider,
    )


def create_custom_item(plugin_id: str, index: int | str) -> Any:
    return _runtime().create_custom_item(plugin_id, index)


def legacy_preferences_all() -> dict[str, Any]:
    manager = _runtime()
    flattened: dict[str, Any] = {}
    for plugin_id in manager.records:
        for key, value in manager.settings.get_all(plugin_id).items():
            flattened[f"plugin_setting_{plugin_id}_{key}"] = value
    return flattened


def add_menu_item(plugin_id: str, item: Any) -> str | None:
    record = _runtime().records.get(plugin_id)
    if record is None or record.instance is None:
        return None
    return _runtime().add_menu_item(record.instance, item)


def remove_menu_item(plugin_id: str, item_id: str) -> bool:
    record = _runtime().records.get(plugin_id)
    if record is None or record.instance is None:
        return False
    return _runtime().remove_menu_item(record.instance, item_id)


def remove_menu_items_by_plugin_id(plugin_id: str) -> None:
    _runtime()._cleanup_plugin_menu_items(plugin_id)


def shutdown() -> None:
    global _manager
    if _manager is not None:
        _manager.shutdown()
    _manager = None
    set_manager(None)
    services.clear()
