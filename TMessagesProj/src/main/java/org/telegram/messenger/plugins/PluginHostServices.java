package org.telegram.messenger.plugins;

import android.net.Uri;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.TextView;

import com.chaquo.python.PyObject;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessageSuggestionParams;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.LocaleController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.tgnet.tl.TL_stories;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Trusted Java services used by the bundled compatibility modules. */
public final class PluginHostServices {
    private static final DispatchQueue pluginsQueue = new DispatchQueue("pluginsQueue");

    private PluginHostServices() {
    }

    public static void runOnUiThread(PyObject callable, int delay) {
        AndroidUtilities.runOnUIThread(() -> callSafely(callable), Math.max(0, delay));
    }

    public static DispatchQueue getQueueByName(String name) {
        if ("stageQueue".equals(name)) return Utilities.stageQueue;
        if ("globalQueue".equals(name)) return Utilities.globalQueue;
        if ("cacheClearQueue".equals(name)) return Utilities.cacheClearQueue;
        if ("searchQueue".equals(name)) return Utilities.searchQueue;
        if ("phoneBookQueue".equals(name)) return Utilities.phoneBookQueue;
        if ("themeQueue".equals(name)) return Utilities.themeQueue;
        if ("externalNetworkQueue".equals(name)) return Utilities.externalNetworkQueue;
        if ("pluginsQueue".equals(name)) return pluginsQueue;
        return null;
    }

    public static void runOnQueue(PyObject callable, String queueName, int delay) {
        DispatchQueue queue = getQueueByName(queueName);
        if (queue == null) {
            throw new IllegalArgumentException("Unknown queue " + queueName);
        }
        queue.postRunnable(() -> callSafely(callable), Math.max(0, delay));
    }

    public static void log(Object value) {
        FileLog.d(String.valueOf(value));
    }

    public static void copyToClipboard(String text) {
        AndroidUtilities.addToClipboard(text);
    }

    public static AccountInstance getAccountInstance() {
        return AccountInstance.getInstance(UserConfig.selectedAccount);
    }

    public static Object getLastFragment() {
        return LaunchActivity.getLastFragment();
    }

    public static int sendRequest(TLObject request, RequestDelegate callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must implement RequestDelegate");
        }
        return getAccountInstance().getConnectionsManager().sendRequest(request, callback);
    }

    public static void sendMessage(Map<?, ?> values, String parseMode) {
        if (parseMode != null
                && !"HTML".equalsIgnoreCase(parseMode)
                && !"MARKDOWN".equalsIgnoreCase(parseMode)) {
            throw new IllegalArgumentException(
                    "Invalid parse mode: " + parseMode + ". Must be HTML or MARKDOWN."
            );
        }
        long peer = number(values.get("peer"), 0).longValue();
        String text = values.get("message") == null ? null : String.valueOf(values.get("message"));
        String mediaType = values.get("media_type") == null ? null : String.valueOf(values.get("media_type"));
        String filePath = values.get("file_path") == null ? null : String.valueOf(values.get("file_path"));
        String caption = values.get("caption") == null ? "" : String.valueOf(values.get("caption"));
        boolean notify = !(values.get("notify") instanceof Boolean) || (Boolean) values.get("notify");
        int scheduleDate = number(values.get("scheduleDate"), number(values.get("schedule_date"), 0)).intValue();
        int scheduleRepeatPeriod = number(
                first(values, "scheduleRepeatPeriod", "schedule_repeat_period"), 0
        ).intValue();
        AccountInstance account = getAccountInstance();
        ArrayList<TLRPC.MessageEntity> entities = asEntityList(values.get("entities"));
        if (mediaType != null && filePath != null) {
            MessageObject replyToMsg = typed(values, MessageObject.class, "replyToMsg", "reply_to_msg");
            MessageObject replyToTopMsg = typed(values, MessageObject.class, "replyToTopMsg", "reply_to_top_msg");
            MessageObject editingMessage = typed(values, MessageObject.class, "editingMessageObject", "editing_message_object");
            if (editingMessage != null && values.get("caption") != null) {
                editingMessage.editingMessage = caption;
                editingMessage.editingMessageEntities = entities;
                editingMessage.editingMessageSearchWebPage = booleanValue(
                        first(values, "searchLinks", "search_links"), true
                );
            }
            TL_stories.StoryItem storyItem = typed(values, TL_stories.StoryItem.class, "replyToStoryItem", "story_item");
            ChatActivity.ReplyQuote quote = typed(values, ChatActivity.ReplyQuote.class, "replyQuote", "reply_quote");
            MessageSuggestionParams suggestionParams = typed(values, MessageSuggestionParams.class, "suggestionParams", "suggestion_params");
            int ttl = number(values.get("ttl"), 0).intValue();
            String quickReply = string(first(values, "quick_reply_shortcut", "quickReplyShortcut"));
            int quickReplyId = number(first(values, "quick_reply_shortcut_id", "quickReplyShortcutId"), 0).intValue();
            long effectId = number(first(values, "effect_id", "effectId"), 0).longValue();
            long payStars = number(first(values, "payStars", "stars"), 0).longValue();
            long monoForumPeer = number(first(values, "monoForumPeer", "mono_forum_peer"), 0).longValue();
            boolean invertMedia = booleanValue(first(values, "invert_media", "invertMedia"), false);
            boolean forceDocument = booleanValue(first(values, "forceDocument", "force_document"), false);
            boolean hasMediaSpoilers = booleanValue(first(values, "hasMediaSpoilers", "has_media_spoilers"), false);
            boolean highQuality = booleanValue(first(values, "high_quality", "sendingHighQuality"), false);
            if ("photo".equals(mediaType)) {
                SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
                info.path = filePath;
                info.thumbPath = string(first(values, "thumbPath", "thumb_path"));
                info.caption = caption;
                info.entities = entities;
                info.ttl = ttl;
                info.highQuality = highQuality;
                info.hasMediaSpoilers = hasMediaSpoilers;
                info.videoEditedInfo = typed(values, VideoEditedInfo.class, "videoEditedInfo", "video_edited_info");
                info.masks = asInputDocumentList(values.get("stickers"));
                ArrayList<SendMessagesHelper.SendingMediaInfo> media = new ArrayList<>();
                media.add(info);
                SendMessagesHelper.prepareSendingMedia(
                        account, media, peer, replyToMsg, replyToTopMsg, storyItem,
                        quote, forceDocument, false, editingMessage, notify,
                        scheduleDate, scheduleRepeatPeriod, number(values.get("mode"), 0).intValue(),
                        booleanValue(first(values, "updateStickersOrder", "update_stickers_order"), false), null,
                        quickReply, quickReplyId, effectId, invertMedia, payStars,
                        monoForumPeer, suggestionParams
                );
                return;
            }
            if ("video".equals(mediaType)) {
                SendMessagesHelper.prepareSendingVideo(
                        account, filePath,
                        typed(values, VideoEditedInfo.class, "videoEditedInfo", "video_edited_info"),
                        string(first(values, "coverPath", "cover_path")),
                        typed(values, TLRPC.Photo.class, "coverPhoto", "cover_photo"),
                        peer, replyToMsg, replyToTopMsg, storyItem, quote, entities,
                        ttl, editingMessage, notify, scheduleDate, scheduleRepeatPeriod,
                        forceDocument, hasMediaSpoilers, caption, quickReply,
                        quickReplyId, effectId, payStars, monoForumPeer,
                        suggestionParams, invertMedia
                );
                return;
            }
            ArrayList<String> paths = new ArrayList<>();
            paths.add(filePath);
            ArrayList<String> originalPaths = new ArrayList<>();
            originalPaths.add(filePath);
            SendMessagesHelper.prepareSendingDocuments(
                    account, paths, originalPaths, null, caption, entities,
                    string(values.get("mime")), peer, replyToMsg, replyToTopMsg,
                    storyItem, quote, editingMessage, notify, scheduleDate,
                    scheduleRepeatPeriod, null, quickReply, quickReplyId, effectId,
                    invertMedia, payStars, monoForumPeer, suggestionParams,
                    null, null, null, false, hasMediaSpoilers
            );
            return;
        }
        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text, peer);
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if ("media_type".equals(key) || "file_path".equals(key)) continue;
            setPublicField(params, canonicalSendField(key), entry.getValue());
        }
        account.getSendMessagesHelper().sendMessage(params);
    }

    public static Object editMessage(
            Object value,
            String text,
            String filePath,
            boolean withSpoiler,
            String parseMode,
            Map<?, ?> options
    ) {
        if (!(value instanceof MessageObject)) {
            throw new IllegalArgumentException("message_obj must be MessageObject");
        }
        MessageObject messageObject = (MessageObject) value;
        AccountInstance account = getAccountInstance();
        ArrayList<TLRPC.MessageEntity> entities = asEntityList(
                options == null ? null : options.get("entities")
        );
        if (text != null) {
            messageObject.editingMessage = text;
            messageObject.editingMessageEntities = entities;
            messageObject.editingMessageSearchWebPage = options == null
                    || booleanValue(first(options, "searchLinks", "search_links"), true);
        }
        if (filePath != null) {
            ArrayList<String> paths = new ArrayList<>();
            paths.add(filePath);
            ArrayList<String> originalPaths = new ArrayList<>();
            originalPaths.add(filePath);
            SendMessagesHelper.prepareSendingDocuments(
                    account, paths, originalPaths, null, text, entities, null,
                    messageObject.getDialogId(), null, null, null, null,
                    messageObject, true, 0, 0, null, null, 0, 0,
                    false, 0, 0, null, null, null, null, false, withSpoiler
            );
            return null;
        }
        if (text != null) {
            account.getSendMessagesHelper().editMessage(
                    messageObject, null, null, null, null, null, null,
                    false, withSpoiler, null
            );
        }
        return null;
    }

    public static TLRPC.MessageEntity rawEntityToTlRpc(PyObject raw) {
        PyObject typeValue = raw.get("type");
        if (typeValue != null && typeValue.get("value") != null) {
            typeValue = typeValue.get("value");
        }
        String type = typeValue == null ? "" : typeValue.toString();
        TLRPC.MessageEntity entity;
        switch (type) {
            case "code": entity = new TLRPC.TL_messageEntityCode(); break;
            case "pre":
                TLRPC.TL_messageEntityPre pre = new TLRPC.TL_messageEntityPre();
                pre.language = pyString(raw.get("language"));
                entity = pre;
                break;
            case "strikethrough": entity = new TLRPC.TL_messageEntityStrike(); break;
            case "text_link":
                TLRPC.TL_messageEntityTextUrl link = new TLRPC.TL_messageEntityTextUrl();
                link.url = pyString(raw.get("url"));
                entity = link;
                break;
            case "bold": entity = new TLRPC.TL_messageEntityBold(); break;
            case "italic": entity = new TLRPC.TL_messageEntityItalic(); break;
            case "underline": entity = new TLRPC.TL_messageEntityUnderline(); break;
            case "spoiler": entity = new TLRPC.TL_messageEntitySpoiler(); break;
            case "custom_emoji":
                TLRPC.TL_messageEntityCustomEmoji emoji = new TLRPC.TL_messageEntityCustomEmoji();
                emoji.document_id = pyLong(raw.get("document_id"), 0);
                entity = emoji;
                break;
            case "blockquote":
                TLRPC.TL_messageEntityBlockquote quote = new TLRPC.TL_messageEntityBlockquote();
                quote.collapsed = pyBoolean(raw.get("collapsed"), false);
                entity = quote;
                break;
            default: throw new IllegalArgumentException("Unknown entity type " + type);
        }
        entity.offset = pyInt(raw.get("offset"), 0);
        entity.length = pyInt(raw.get("length"), 0);
        return entity;
    }

    public static File getCacheDir() {
        return ApplicationLoader.applicationContext.getCacheDir();
    }

    public static File getFilesDir() {
        return ApplicationLoader.applicationContext.getFilesDir();
    }

    public static File getMediaDir(String kind) {
        int type;
        if ("image".equals(kind)) type = FileLoader.MEDIA_DIR_IMAGE;
        else if ("video".equals(kind)) type = FileLoader.MEDIA_DIR_VIDEO;
        else if ("audio".equals(kind)) type = FileLoader.MEDIA_DIR_AUDIO;
        else if ("document".equals(kind)) type = FileLoader.MEDIA_DIR_DOCUMENT;
        else throw new IllegalArgumentException("Unknown media directory " + kind);
        return FileLoader.getDirectory(type);
    }

    public static MediaController getMediaController() {
        return MediaController.getInstance();
    }

    public static void reloadPluginSettings(String pluginId) {
        PluginManager.getInstance().notifySettingsReload(pluginId);
    }

    public static Object createAlertDialog(PyObject pythonBuilder, PyObject values) {
        Context context = pythonBuilder.callAttr("get_context").toJava(Context.class);
        int progressStyle = pyInt(pythonBuilder.get("_progress_style"), 0);
        Theme.ResourcesProvider resourcesProvider = null;
        PyObject resourcesValue = pythonBuilder.get("_resources_provider");
        if (notNone(resourcesValue)) {
            resourcesProvider = resourcesValue.toJava(Theme.ResourcesProvider.class);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, progressStyle, resourcesProvider);
        String title = pyString(values.get("title"));
        String message = pyString(values.get("message"));
        if (title != null) builder.setTitle(title);
        if (message != null) builder.setMessage(message);
        PyObject messageClickable = values.get("message_clickable");
        if (notNone(messageClickable)) builder.setMessageTextViewClickable(messageClickable.toBoolean());
        applyButton(builder, pythonBuilder, values.get("positive_button"), AlertDialog.BUTTON_POSITIVE);
        applyButton(builder, pythonBuilder, values.get("negative_button"), AlertDialog.BUTTON_NEGATIVE);
        applyButton(builder, pythonBuilder, values.get("neutral_button"), AlertDialog.BUTTON_NEUTRAL);
        PyObject back = values.get("back_listener");
        if (notNone(back)) {
            builder.setOnBackButtonListener((dialog, which) -> callSafely(back, pythonBuilder, which));
        }
        PyObject viewValue = values.get("view");
        if (notNone(viewValue)) {
            List<PyObject> tuple = viewValue.asList();
            if (!tuple.isEmpty()) {
                View view = tuple.get(0).toJava(View.class);
                int height = tuple.size() > 1 ? tuple.get(1).toInt() : -2;
                builder.setView(view, height);
            }
        }
        PyObject itemsValue = values.get("items");
        if (notNone(itemsValue)) {
            List<PyObject> tuple = itemsValue.asList();
            List<PyObject> pythonItems = tuple.get(0).asList();
            CharSequence[] items = new CharSequence[pythonItems.size()];
            for (int i = 0; i < items.length; i++) items[i] = pythonItems.get(i).toString();
            PyObject listener = tuple.size() > 1 ? tuple.get(1) : null;
            PyObject iconsValue = tuple.size() > 2 ? tuple.get(2) : null;
            if (notNone(iconsValue)) {
                List<PyObject> pythonIcons = iconsValue.asList();
                int[] icons = new int[pythonIcons.size()];
                for (int i = 0; i < icons.length; i++) icons[i] = pythonIcons.get(i).toInt();
                builder.setItems(items, icons, (dialog, which) -> callSafely(listener, pythonBuilder, which));
            } else {
                builder.setItems(items, (dialog, which) -> callSafely(listener, pythonBuilder, which));
            }
        }
        PyObject dismiss = values.get("dismiss_listener");
        if (notNone(dismiss)) {
            builder.setOnDismissListener(dialog -> callSafely(dismiss, pythonBuilder));
        }
        PyObject cancel = values.get("cancel_listener");
        if (notNone(cancel)) {
            builder.setOnCancelListener(dialog -> callSafely(cancel, pythonBuilder));
        }
        PyObject topImage = values.get("top_image");
        if (notNone(topImage)) {
            List<PyObject> tuple = topImage.asList();
            builder.setTopImage(tuple.get(0).toInt(), tuple.get(1).toInt());
        }
        PyObject topDrawable = values.get("top_drawable");
        if (notNone(topDrawable)) {
            List<PyObject> tuple = topDrawable.asList();
            builder.setTopImage(tuple.get(0).toJava(Drawable.class), tuple.get(1).toInt());
        }
        PyObject topAnimation = values.get("top_animation");
        if (notNone(topAnimation)) {
            List<PyObject> tuple = topAnimation.asList();
            Map<String, Integer> layerColors = null;
            if (tuple.size() > 4 && notNone(tuple.get(4))) {
                //noinspection unchecked
                layerColors = tuple.get(4).toJava(Map.class);
            }
            builder.setTopAnimation(
                    tuple.get(0).toInt(), tuple.get(1).toInt(), tuple.get(2).toBoolean(),
                    tuple.get(3).toInt(), layerColors
            );
        }
        PyObject topAnimationIsNew = values.get("top_animation_is_new");
        if (notNone(topAnimationIsNew)) builder.setTopAnimationIsNew(topAnimationIsNew.toBoolean());
        PyObject dimEnabled = values.get("dim_enabled");
        if (notNone(dimEnabled)) builder.setDimEnabled(dimEnabled.toBoolean());
        PyObject buttonColorKey = values.get("button_color_key");
        if (notNone(buttonColorKey)) builder.setDialogButtonColorKey(buttonColorKey.toInt());
        PyObject blurred = values.get("blurred_background");
        if (notNone(blurred)) {
            List<PyObject> tuple = blurred.asList();
            builder.setBlurredBackground(tuple.get(0).toBoolean());
        }
        AlertDialog dialog = builder.create();
        if (notNone(blurred)) {
            List<PyObject> tuple = blurred.asList();
            dialog.setBlurParams(0.8f, tuple.size() < 2 || tuple.get(1).toBoolean(), tuple.get(0).toBoolean());
        }
        PyObject cancelable = values.get("cancelable");
        if (notNone(cancelable)) dialog.setCancelable(cancelable.toBoolean());
        PyObject outside = values.get("canceled_on_touch_outside");
        if (notNone(outside)) dialog.setCanceledOnTouchOutside(outside.toBoolean());
        PyObject progress = values.get("progress");
        if (notNone(progress)) dialog.setProgress(progress.toInt());
        PyObject redButton = values.get("red_button");
        if (notNone(redButton)) {
            int which = redButton.toInt();
            dialog.setOnShowListener(ignored -> {
                View button = dialog.getButton(which);
                if (button instanceof TextView) {
                    ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
                }
            });
        }
        return dialog;
    }

    public static void showAlertDialog(Object dialog) {
        if (dialog instanceof AlertDialog) ((AlertDialog) dialog).show();
    }

    public static void dismissAlertDialog(Object dialog) {
        if (dialog instanceof AlertDialog) ((AlertDialog) dialog).dismiss();
    }

    public static View getAlertButton(Object dialog, int buttonType) {
        return dialog instanceof AlertDialog ? ((AlertDialog) dialog).getButton(buttonType) : null;
    }

    public static void setAlertProgress(Object dialog, int progress) {
        if (dialog instanceof AlertDialog) ((AlertDialog) dialog).setProgress(progress);
    }

    public static void setAlertCancelable(Object dialog, boolean cancelable) {
        if (dialog instanceof AlertDialog) ((AlertDialog) dialog).setCancelable(cancelable);
    }

    public static void setAlertCanceledOnTouchOutside(Object dialog, boolean cancel) {
        if (dialog instanceof AlertDialog) ((AlertDialog) dialog).setCanceledOnTouchOutside(cancel);
    }

    public static void showBulletin(
            String kind,
            PyObject positional,
            PyObject keyword,
            PyObject failureCallback
    ) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
            List<PyObject> args = positional.asList();
            BaseFragment fragment = null;
            PyObject fragmentValue = keyword.get("fragment");
            if (notNone(fragmentValue)) {
                try {
                    fragment = fragmentValue.toJava(BaseFragment.class);
                } catch (Throwable ignored) {
                }
            }
            BulletinFactory factory = BulletinFactory.of(fragment);
            String first = args.isEmpty() ? "" : args.get(0).toString();
            Bulletin bulletin;
            if ("error".equals(kind)) {
                bulletin = factory.createErrorBulletin(first);
            } else if ("success".equals(kind)) {
                bulletin = factory.createSuccessBulletin(first);
            } else if ("simple".equals(kind) && args.size() >= 2) {
                bulletin = factory.createSimpleBulletin(args.get(1).toInt(), first);
            } else if ("two_line".equals(kind) && args.size() >= 3) {
                bulletin = factory.createSimpleBulletin(args.get(2).toInt(), first, args.get(1).toString());
            } else if ("with_button".equals(kind) && args.size() >= 4) {
                PyObject callback = args.get(3);
                int duration = keyword.get("duration") == null ? Bulletin.DURATION_PROLONG : keyword.get("duration").toInt();
                bulletin = factory.createSimpleBulletin(
                        args.get(1).toInt(), first, args.get(2).toString(), duration,
                        () -> callSafely(callback)
                );
            } else if ("undo".equals(kind) && args.size() >= 2) {
                PyObject onUndo = args.get(1);
                PyObject onAction = args.size() > 2 ? args.get(2) : null;
                String subtitle = pyString(keyword.get("subtitle"));
                Runnable undo = () -> callSafely(onUndo);
                Runnable action = notNone(onAction) ? () -> callSafely(onAction) : null;
                bulletin = subtitle == null
                        ? factory.createUndoBulletin(first, undo, action)
                        : factory.createUndoBulletin(first, subtitle, undo, action);
            } else if ("copied_to_clipboard".equals(kind)) {
                String message = args.isEmpty() || !notNone(args.get(0))
                        ? LocaleController.getString(R.string.TextCopied)
                        : args.get(0).toString();
                bulletin = factory.createCopyBulletin(message);
            } else if ("link_copied".equals(kind)) {
                boolean privateLink = !args.isEmpty() && args.get(0).toBoolean();
                bulletin = factory.createCopyLinkBulletin(privateLink);
            } else if ("file_saved_to_gallery".equals(kind)) {
                boolean video = !args.isEmpty() && args.get(0).toBoolean();
                int amount = args.size() > 1 ? args.get(1).toInt() : 1;
                BulletinFactory.FileType type;
                if (video) {
                    type = amount > 1 ? BulletinFactory.FileType.VIDEOS : BulletinFactory.FileType.VIDEO;
                } else {
                    type = amount > 1 ? BulletinFactory.FileType.PHOTOS : BulletinFactory.FileType.PHOTO;
                }
                bulletin = factory.createDownloadBulletin(type, amount, null);
            } else if ("file_saved_to_downloads".equals(kind)) {
                String typeName = args.isEmpty() ? "UNKNOWN" : args.get(0).toString();
                int amount = args.size() > 1 ? args.get(1).toInt() : 1;
                if (amount > 1 && !typeName.endsWith("S")) typeName += "S";
                BulletinFactory.FileType type;
                try {
                    type = BulletinFactory.FileType.valueOf(typeName);
                } catch (IllegalArgumentException ignored) {
                    type = amount > 1 ? BulletinFactory.FileType.UNKNOWNS : BulletinFactory.FileType.UNKNOWN;
                }
                bulletin = factory.createDownloadBulletin(type, amount, null);
            } else {
                bulletin = factory.createSimpleBulletin(R.raw.info, first);
            }
            bulletin.show();
            } catch (Throwable error) {
                FileLog.e(error);
                callSafely(failureCallback, error.toString());
            }
        });
    }

    private static void setPublicField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getField(name);
            Class<?> type = field.getType();
            Object converted = convertPluginValue(type, value);
            if (type == boolean.class && converted instanceof Boolean) {
                field.setBoolean(target, (Boolean) converted);
            } else if (type == int.class && converted instanceof Number) {
                field.setInt(target, ((Number) converted).intValue());
            } else if (type == long.class && converted instanceof Number) {
                field.setLong(target, ((Number) converted).longValue());
            } else if (!type.isPrimitive()
                    && (converted == null || type.isInstance(converted))) {
                field.set(target, converted);
            } else {
                FileLog.e("Could not set parameter '" + name + "': expected "
                        + type.getName() + ", got "
                        + (converted == null ? "null" : converted.getClass().getName())
                        + ". Ignored.");
            }
        } catch (NoSuchFieldException ignored) {
            FileLog.e("Unknown SendMessageParams parameter '" + name + "'");
        } catch (Throwable exception) {
            FileLog.e("Could not set parameter '" + name + "': " + exception + ". Ignored.");
        }
    }

    private static Object convertPluginValue(Class<?> expectedType, Object value) throws Throwable {
        if (value == null) return null;
        Object converted = value;
        if (value instanceof PyObject) {
            PyObject pythonValue = (PyObject) value;
            try {
                converted = pythonValue.toJava(expectedType);
            } catch (Throwable directConversionError) {
                if (List.class.isAssignableFrom(expectedType)) {
                    ArrayList<Object> list = new ArrayList<>();
                    for (PyObject item : pythonValue.asList()) {
                        list.add(item.toJava(Object.class));
                    }
                    converted = list;
                } else if (Map.class.isAssignableFrom(expectedType)) {
                    converted = pythonValue.toJava(Map.class);
                } else {
                    throw directConversionError;
                }
            }
        }
        if (ArrayList.class.isAssignableFrom(expectedType)
                && converted instanceof List
                && !(converted instanceof ArrayList)) {
            converted = new ArrayList<>((List<?>) converted);
        } else if (HashMap.class.isAssignableFrom(expectedType)
                && converted instanceof Map
                && !(converted instanceof HashMap)) {
            converted = new HashMap<>((Map<?, ?>) converted);
        }
        return converted;
    }

    private static Number number(Object value, Number fallback) {
        return value instanceof Number ? (Number) value : fallback;
    }

    private static Object first(Map<?, ?> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key)) {
                return values.get(key);
            }
        }
        return null;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static <T> T typed(Map<?, ?> values, Class<T> type, String... keys) {
        Object value = first(values, keys);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    private static ArrayList<TLRPC.InputDocument> asInputDocumentList(Object value) {
        ArrayList<TLRPC.InputDocument> result = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item instanceof TLRPC.InputDocument) {
                    result.add((TLRPC.InputDocument) item);
                }
            }
        } else if (value instanceof PyObject) {
            for (PyObject item : ((PyObject) value).asList()) {
                TLRPC.InputDocument document = item.toJava(TLRPC.InputDocument.class);
                if (document != null) result.add(document);
            }
        }
        return result.isEmpty() ? null : result;
    }

    private static String canonicalSendField(String key) {
        if ("schedule_date".equals(key)) return "scheduleDate";
        if ("schedule_repeat_period".equals(key)) return "scheduleRepeatPeriod";
        if ("reply_to_msg".equals(key)) return "replyToMsg";
        if ("reply_to_top_msg".equals(key)) return "replyToTopMsg";
        if ("reply_quote".equals(key)) return "replyQuote";
        if ("reply_to_story_item".equals(key)) return "replyToStoryItem";
        if ("has_media_spoilers".equals(key)) return "hasMediaSpoilers";
        if ("sending_high_quality".equals(key) || "high_quality".equals(key)) {
            return "sendingHighQuality";
        }
        if ("mono_forum_peer".equals(key)) return "monoForumPeer";
        if ("suggestion_params".equals(key)) return "suggestionParams";
        return key;
    }

    private static ArrayList<TLRPC.MessageEntity> asEntityList(Object value) {
        ArrayList<TLRPC.MessageEntity> result = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item instanceof TLRPC.MessageEntity) {
                    result.add((TLRPC.MessageEntity) item);
                }
            }
        } else if (value instanceof PyObject) {
            for (PyObject item : ((PyObject) value).asList()) {
                TLRPC.MessageEntity entity = item.toJava(TLRPC.MessageEntity.class);
                if (entity != null) result.add(entity);
            }
        }
        return result;
    }

    private static int pyInt(PyObject value, int fallback) {
        return notNone(value) ? value.toInt() : fallback;
    }

    private static long pyLong(PyObject value, long fallback) {
        return notNone(value) ? value.toLong() : fallback;
    }

    private static boolean pyBoolean(PyObject value, boolean fallback) {
        return notNone(value) ? value.toBoolean() : fallback;
    }

    private static void callSafely(PyObject callable) {
        callSafely(callable, new Object[0]);
    }

    private static void callSafely(PyObject callable, Object... args) {
        if (!notNone(callable)) return;
        try {
            callable.call(args);
        } catch (Throwable exception) {
            FileLog.e(exception);
        }
    }

    private static void applyButton(
            AlertDialog.Builder builder,
            PyObject pythonBuilder,
            PyObject value,
            int which
    ) {
        if (!notNone(value)) return;
        List<PyObject> tuple = value.asList();
        String text = tuple.isEmpty() ? "" : tuple.get(0).toString();
        PyObject callback = tuple.size() > 1 ? tuple.get(1) : null;
        if (which == AlertDialog.BUTTON_POSITIVE) {
            builder.setPositiveButton(text, (dialog, button) -> callSafely(callback, pythonBuilder, button));
        } else if (which == AlertDialog.BUTTON_NEGATIVE) {
            builder.setNegativeButton(text, (dialog, button) -> callSafely(callback, pythonBuilder, button));
        } else {
            builder.setNeutralButton(text, (dialog, button) -> callSafely(callback, pythonBuilder, button));
        }
    }

    private static boolean notNone(PyObject value) {
        return value != null && value.toJava(Object.class) != null;
    }

    private static String pyString(PyObject value) {
        return notNone(value) ? value.toString() : null;
    }
}
