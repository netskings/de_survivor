package org.telegram.ui.Feed;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;

import java.util.Locale;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;

public class FeedUtils {

    public static String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.US, "%.1f KB", size / 1024f);
        if (size < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", size / (1024f * 1024f));
        return String.format(Locale.US, "%.1f GB", size / (1024f * 1024f * 1024f));
    }

    public static String formatVoiceDuration(int seconds) {
        if (seconds < 3600) {
            return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
        }
        return String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    public static String getPeerName(TLRPC.Peer peer, MessagesController controller) {
        if (peer == null) return null;
        if (peer.channel_id != 0) {
            TLRPC.Chat chat = controller.getChat(peer.channel_id);
            return chat != null ? chat.title : null;
        } else if (peer.chat_id != 0) {
            TLRPC.Chat chat = controller.getChat(peer.chat_id);
            return chat != null ? chat.title : null;
        } else if (peer.user_id != 0) {
            TLRPC.User user = controller.getUser(peer.user_id);
            if (user == null) return null;
            String name = user.first_name;
            if (user.last_name != null && !user.last_name.isEmpty())
                name += " " + user.last_name;
            return name;
        }
        return null;
    }

    public static boolean isReallyEdited(TLRPC.Message msg) {
        if (msg.edit_date == 0) return false;
        if (msg.edit_hide) return false;
        if (msg.fwd_from != null) return false;
        if (msg.media instanceof TLRPC.TL_messageMediaGeoLive) return false;
        return !(msg.media instanceof TLRPC.TL_messageMediaPoll);
    }

    public static String getMediaTypeLabel(TLRPC.MessageMedia media) {
        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            return LocaleController.getString(R.string.FeedMediaPhoto);
        }
        if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
            for (TLRPC.DocumentAttribute attr : media.document.attributes) {
                if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                    return LocaleController.getString(R.string.FeedMediaVideo);
                }
                if (attr instanceof TLRPC.TL_documentAttributeAnimated) {
                    return LocaleController.getString(R.string.FeedMediaGif);
                }
                if (attr instanceof TLRPC.TL_documentAttributeAudio) {
                    if (attr.voice) {
                        return LocaleController.getString(R.string.FeedMediaVoiceMessage);
                    }
                    return LocaleController.getString(R.string.FeedMediaAudio);
                }
                if (attr instanceof TLRPC.TL_documentAttributeSticker) {
                    return LocaleController.getString(R.string.FeedMediaSticker);
                }
            }
            return LocaleController.getString(R.string.FeedMediaDocument);
        }
        if (media instanceof TLRPC.TL_messageMediaPoll) {
            return LocaleController.getString(R.string.FeedMediaPoll);
        }
        if (media instanceof TLRPC.TL_messageMediaGeoLive) {
            return LocaleController.getString(R.string.FeedMediaLiveLocation);
        }
        if (media instanceof TLRPC.TL_messageMediaGeo) {
            return LocaleController.getString(R.string.FeedMediaLocation);
        }
        if (media instanceof TLRPC.TL_messageMediaContact) {
            return LocaleController.getString(R.string.FeedMediaContact);
        }
        return LocaleController.getString(R.string.FeedMediaAttachment);
    }

    public static Activity getActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    public static void openFileFallback(Context context, java.io.File file, String mimeType) {
        try {
            android.content.Intent intent = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW);
            android.net.Uri uri;
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        org.telegram.messenger.ApplicationLoader.getApplicationId() + ".provider",
                        file);
            } else {
                uri = android.net.Uri.fromFile(file);
            }
            intent.setDataAndType(uri, mimeType);
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(android.content.Intent.createChooser(intent, ""));
        } catch (Exception e) {
            android.widget.Toast.makeText(context,
                    LocaleController.getString(R.string.FeedNoAppToOpenFile),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    public static boolean isRoundVideo(TLRPC.Document doc) {
        if (doc == null) return false;
        for (TLRPC.DocumentAttribute attr : doc.attributes) {
            if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                return attr.round_message;
            }
        }
        return false;
    }

    public static boolean isVideo(TLRPC.Document doc) {
        if (doc == null) return false;
        for (TLRPC.DocumentAttribute attr : doc.attributes) {
            if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                return true;
            }
        }
        return false;
    }

    public static boolean isVoiceOrAudio(TLRPC.Document doc) {
        if (doc == null) return false;
        for (TLRPC.DocumentAttribute attr : doc.attributes) {
            if (attr instanceof TLRPC.TL_documentAttributeAudio) {
                return true;
            }
        }
        return false;
    }

    public static boolean isSticker(TLRPC.Document doc) {
        if (doc == null) return false;
        for (TLRPC.DocumentAttribute attr : doc.attributes) {
            if (attr instanceof TLRPC.TL_documentAttributeSticker) return true;
            if (attr instanceof TLRPC.TL_documentAttributeCustomEmoji) return true;
        }
        return false;
    }

    public static boolean isGif(TLRPC.Document doc) {
        if (doc == null) return false;
        for (TLRPC.DocumentAttribute attr : doc.attributes) {
            if (attr instanceof TLRPC.TL_documentAttributeAnimated) return true;
        }
        return false;
    }

}
