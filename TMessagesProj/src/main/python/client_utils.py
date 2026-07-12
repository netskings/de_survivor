"""Source-compatible Telegram client helpers using Android host services."""

from __future__ import annotations

from pathlib import Path

from plugin_runtime import services
from plugin_runtime.context import isolate_callback

try:
    from java import dynamic_proxy
    from java.lang import Object
    from org.telegram.messenger import DispatchQueue, NotificationCenter
    from org.telegram.tgnet import RequestDelegate, TLObject, TLRPC
except (ImportError, ModuleNotFoundError):
    dynamic_proxy = None
    Object = object
    DispatchQueue = None
    NotificationCenter = None
    RequestDelegate = None
    TLObject = None
    TLRPC = None

STAGE_QUEUE = "stageQueue"
GLOBAL_QUEUE = "globalQueue"
CACHE_CLEAR_QUEUE = "cacheClearQueue"
SEARCH_QUEUE = "searchQueue"
PHONE_BOOK_QUEUE = "phoneBookQueue"
THEME_QUEUE = "themeQueue"
EXTERNAL_NETWORK_QUEUE = "externalNetworkQueue"
PLUGINS_QUEUE = "pluginsQueue"

SEND_MESSAGE_PARAM_FIELDS = (
    "message", "caption", "location", "photo", "videoEditedInfo", "user",
    "document", "game", "poll", "pollSendParams", "pollIndex", "todo",
    "invoice", "mediaWebPage", "cover", "peer", "path", "replyToMsg",
    "replyToTopMsg", "webPage", "searchLinks", "retryMessageObject",
    "entities", "replyMarkup", "params", "notify", "scheduleDate",
    "scheduleRepeatPeriod", "ttl", "parentObject", "sendAnimationData",
    "updateStickersOrder", "hasMediaSpoilers", "replyToStoryItem",
    "sendingStory", "replyQuote", "invert_media", "quick_reply_shortcut",
    "quick_reply_shortcut_id", "effect_id", "stars", "payStars",
    "monoForumPeer", "sendingHighQuality", "suggestionParams", "isLivePhoto",
    "livePhotoTimestamp", "dice_stake",
)
SEND_MESSAGE_PARAM_DEFAULTS = {
    field: None for field in SEND_MESSAGE_PARAM_FIELDS
}
SEND_MESSAGE_PARAM_DEFAULTS.update(
    peer=0,
    pollIndex=0,
    searchLinks=True,
    notify=True,
    scheduleDate=0,
    scheduleRepeatPeriod=0,
    ttl=0,
    updateStickersOrder=False,
    hasMediaSpoilers=False,
    invert_media=False,
    quick_reply_shortcut_id=0,
    effect_id=0,
    stars=0,
    payStars=0,
    monoForumPeer=0,
    sendingHighQuality=False,
    isLivePhoto=False,
    livePhotoTimestamp=0,
    dice_stake=0,
)

# Additive bridge-only keys used by the high-level media helpers. They don't
# alter the public SendMessageParams snapshot above.
_SEND_MESSAGE_BRIDGE_FIELDS = {
    "media_type", "file_path", "high_quality", "mime", "forceDocument",
    "force_document", "thumbPath", "thumb_path", "coverPath", "cover_path",
    "coverPhoto", "cover_photo", "stickers", "mode", "video_edited_info",
    "editingMessageObject", "editing_message_object", "story_item",
    "reply_to_msg", "reply_to_top_msg", "reply_quote", "reply_to_story_item",
    "schedule_date", "schedule_repeat_period", "has_media_spoilers",
    "sending_high_quality", "mono_forum_peer", "suggestion_params",
    "search_links", "quickReplyShortcut", "quickReplyShortcutId",
    "effectId", "invertMedia", "update_stickers_order",
}


def get_queue_by_name(queue_name: str):
    return services.call("get_queue_by_name", queue_name, default=None)


def run_on_queue(fn: callable, queue_name: str = PLUGINS_QUEUE, delay: int = 0):
    if not callable(fn):
        raise TypeError("fn must be callable")
    fn = isolate_callback(fn, "client_utils.run_on_queue")
    if services.has("run_on_queue"):
        return services.call("run_on_queue", fn, queue_name, delay)
    return fn()


if dynamic_proxy is not None:
    class RequestCallback(dynamic_proxy(RequestDelegate)):
        def __init__(self, fn: callable) -> None:
            super().__init__()
            if not callable(fn):
                raise TypeError("fn must be callable")
            self.fn = isolate_callback(fn, "client_utils.RequestCallback")

        def run(self, response, error):
            return self.fn(response, error)
else:
    class RequestCallback:
        def __init__(self, fn: callable) -> None:
            if not callable(fn):
                raise TypeError("fn must be callable")
            self.fn = isolate_callback(fn, "client_utils.RequestCallback")

        def run(self, response, error):
            return self.fn(response, error)


def send_request(request, fn: callable) -> int:
    callback = fn if isinstance(fn, RequestCallback) else RequestCallback(fn)
    if not callable(callback.fn):
        raise TypeError("fn must be callable or RequestCallback")
    return int(services.call("send_request", request, callback))


def _get(name: str):
    return services.call(name, default=None)


def get_last_fragment(): return _get("get_last_fragment")
def get_account_instance(): return _get("get_account_instance")
def get_messages_controller(): return _get("get_messages_controller")
def get_contacts_controller(): return _get("get_contacts_controller")
def get_media_data_controller(): return _get("get_media_data_controller")
def get_connections_manager(): return _get("get_connections_manager")
def get_location_controller(): return _get("get_location_controller")
def get_notifications_controller(): return _get("get_notifications_controller")
def get_messages_storage(): return _get("get_messages_storage")
def get_send_messages_helper(): return _get("get_send_messages_helper")
def get_file_loader(): return _get("get_file_loader")
def get_secret_chat_helper(): return _get("get_secret_chat_helper")
def get_download_controller(): return _get("get_download_controller")
def get_notifications_settings(): return _get("get_notifications_settings")
def get_notification_center(): return _get("get_notification_center")
def get_media_controller(): return _get("get_media_controller")
def get_user_config(): return _get("get_user_config")


def _send_message(params: dict, parse_mode: str = None, *, bridge_media: bool = False):
    if not isinstance(params, dict):
        raise TypeError("params must be a dict")
    if parse_mode is not None and parse_mode not in {"HTML", "MARKDOWN"}:
        raise ValueError(
            f"Invalid parse mode: {parse_mode}. Must be HTML or MARKDOWN."
        )
    for key in params:
        if key not in SEND_MESSAGE_PARAM_FIELDS and not (
            bridge_media and key in _SEND_MESSAGE_BRIDGE_FIELDS
        ):
            raise ValueError(f"Unknown SendMessageParams parameter '{key}'")
    prepared = SEND_MESSAGE_PARAM_DEFAULTS.copy()
    prepared.update(params)
    file_path = prepared.get("file_path")
    if file_path is not None and not Path(str(file_path)).is_file():
        raise ValueError(f"File not found: {file_path}")
    if parse_mode is not None:
        from extera_utils.text_formatting import parse_text

        text_key = "message" if prepared.get("message") is not None else "caption"
        value = prepared.get(text_key)
        if value is not None:
            parsed = parse_text(str(value), parse_mode, text_key == "caption")
            prepared[text_key] = parsed["text"]
            try:
                from java.util import ArrayList

                entities = ArrayList()
                for entity in parsed["entities"]:
                    entities.add(entity.to_tlrpc_object())
            except (ImportError, ModuleNotFoundError):
                entities = list(parsed["entities"])
            prepared["entities"] = entities
    return services.call("send_message", prepared, parse_mode)


def send_message(params: dict, parse_mode: str = None):
    return _send_message(params, parse_mode)


def send_text(peer: int, text: str, *, parse_mode: str | None = None, **kwargs):
    params = dict(kwargs, peer=peer, message=text)
    return send_message(params, parse_mode=parse_mode)


def send_photo(peer: int, file_path: str, caption: str = "", high_quality: bool = False, *, parse_mode: str | None = None, **kwargs):
    params = dict(kwargs, peer=peer, file_path=file_path, caption=caption, high_quality=high_quality, media_type="photo")
    return _send_message(params, parse_mode=parse_mode, bridge_media=True)


def send_document(peer: int, file_path: str, caption: str = "", *, parse_mode: str | None = None, **kwargs):
    params = dict(kwargs, peer=peer, file_path=file_path, caption=caption, media_type="document")
    return _send_message(params, parse_mode=parse_mode, bridge_media=True)


def send_video(peer: int, file_path: str, caption: str = "", *, parse_mode: str | None = None, **kwargs):
    params = dict(kwargs, peer=peer, file_path=file_path, caption=caption, media_type="video")
    return _send_message(params, parse_mode=parse_mode, bridge_media=True)


def send_audio(peer: int, file_path: str, caption: str = "", *, parse_mode: str | None = None, **kwargs):
    params = dict(kwargs, peer=peer, file_path=file_path, caption=caption, media_type="audio")
    return _send_message(params, parse_mode=parse_mode, bridge_media=True)


def edit_message(message_obj, text: str | None = None, file_path: str | None = None, with_spoiler: bool = False, *, parse_mode: str | None = None, **kwargs):
    if file_path is not None and not Path(str(file_path)).is_file():
        raise ValueError(f"Media file not found at path: {file_path}")
    if parse_mode is not None and parse_mode not in {"HTML", "MARKDOWN"}:
        raise ValueError(
            f"Invalid parse mode: {parse_mode}. Must be HTML or MARKDOWN."
        )
    if parse_mode is not None and text is not None:
        from extera_utils.text_formatting import parse_text

        parsed = parse_text(text, parse_mode, file_path is not None)
        text = parsed["text"]
        try:
            from java.util import ArrayList

            entities = ArrayList()
            for entity in parsed["entities"]:
                entities.add(entity.to_tlrpc_object())
        except (ImportError, ModuleNotFoundError):
            entities = list(parsed["entities"])
        kwargs = dict(kwargs, entities=entities)
    return services.call("edit_message", message_obj, text, file_path, with_spoiler, parse_mode, kwargs)


if dynamic_proxy is not None:
    class NotificationCenterDelegate(
        dynamic_proxy(NotificationCenter.NotificationCenterDelegate)
    ):
        def __init_subclass__(cls, **kwargs):
            super().__init_subclass__(**kwargs)
            callback = cls.__dict__.get("didReceivedNotification")
            if callback is not None:
                cls.didReceivedNotification = isolate_callback(
                    callback, "client_utils.NotificationCenterDelegate"
                )

        def __init__(self) -> None:
            super().__init__()

        def didReceivedNotification(self, id: int, account: int, args: None):
            return None
else:
    class NotificationCenterDelegate:
        def __init_subclass__(cls, **kwargs):
            super().__init_subclass__(**kwargs)
            callback = cls.__dict__.get("didReceivedNotification")
            if callback is not None:
                cls.didReceivedNotification = isolate_callback(
                    callback, "client_utils.NotificationCenterDelegate"
                )

        def __init__(self) -> None:
            super().__init__()

        def didReceivedNotification(self, id: int, account: int, args: None):
            return None
