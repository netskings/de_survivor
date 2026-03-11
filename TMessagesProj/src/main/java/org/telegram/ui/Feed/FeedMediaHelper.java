package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.annotation.SuppressLint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FeedMediaHelper {

    public interface MediaClickListener {
        void onMediaClick(FeedController.FeedItem item, int index);
    }

    @SuppressLint("SetTextI18n")
    public static void setupMedia(
            FeedController.FeedItem item,
            Context context,
            FrameLayout mediaContainer,
            BackupImageView mainImage,
            TextView mediaOverlay,
            LinearLayout mediaRow,
            TextView albumLabel,
            MediaClickListener listener,
            Theme.ResourcesProvider rp) {

        List<MessageObject> mediaMessages = new ArrayList<>();
        for (MessageObject msg : item.messages) {
            TLRPC.MessageMedia media = msg.messageOwner.media;
            if (media == null) continue;
            if (media instanceof TLRPC.TL_messageMediaEmpty) continue;
            if (media instanceof TLRPC.TL_messageMediaWebPage) continue;
            if (media instanceof TLRPC.TL_messageMediaPoll) continue;

            if (media instanceof TLRPC.TL_messageMediaPhoto) {
                mediaMessages.add(msg);
            } else if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
                boolean isVisualMedia = false;
                for (TLRPC.DocumentAttribute attr : media.document.attributes) {
                    if (attr instanceof TLRPC.TL_documentAttributeVideo) isVisualMedia = true;
                    if (attr instanceof TLRPC.TL_documentAttributeAnimated) isVisualMedia = true;
                }
                if (isVisualMedia) mediaMessages.add(msg);
            }
        }

        if (mediaMessages.isEmpty()) {
            mediaContainer.setVisibility(View.GONE);
            mediaOverlay.setVisibility(View.GONE);
            mediaRow.setVisibility(View.GONE);
            albumLabel.setVisibility(View.GONE);
            return;
        }

        int h = setupSingleMedia(mediaMessages.get(0), mainImage, mediaOverlay);
        mediaContainer.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mediaContainer.getLayoutParams();
        lp.height = h;
        mediaContainer.setLayoutParams(lp);

        if (mediaMessages.size() >= 2) {
            mediaRow.removeAllViews();
            int show = Math.min(mediaMessages.size() - 1, 3);
            for (int i = 0; i < show; i++) {
                final int idx = i + 1;
                BackupImageView thumb = new BackupImageView(context);
                thumb.setRoundRadius(dp(8));
                thumb.setOnClickListener(v -> {
                    if (listener != null) listener.onMediaClick(item, idx);
                });
                setupMediaThumb(mediaMessages.get(idx), thumb);
                mediaRow.addView(thumb, LayoutHelper.createLinear(80, 80, 0, 0, 4, 0));
            }
            if (mediaMessages.size() > 4) {
                TextView more = new TextView(context);
                more.setText("+" + (mediaMessages.size() - 4));
                more.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, rp));
                more.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                more.setGravity(Gravity.CENTER);
                mediaRow.addView(more, LayoutHelper.createLinear(48, 80, Gravity.CENTER_VERTICAL));
            }
            mediaRow.setVisibility(View.VISIBLE);

            int photos = 0, videos = 0;
            for (MessageObject m : mediaMessages) {
                if (m.isVideo() || m.isRoundVideo()) videos++;
                else photos++;
            }
            StringBuilder sb = new StringBuilder("Album • ");
            if (photos > 0) sb.append(photos).append(photos == 1 ? " photo" : " photos");
            if (photos > 0 && videos > 0) sb.append(", ");
            if (videos > 0) sb.append(videos).append(videos == 1 ? " video" : " videos");
            albumLabel.setText(sb.toString());
            albumLabel.setVisibility(View.VISIBLE);
        } else {
            mediaRow.setVisibility(View.GONE);
            albumLabel.setVisibility(View.GONE);
        }
    }

    public static TLRPC.PhotoSize bestSize(List<TLRPC.PhotoSize> sizes) {
        if (sizes == null) return null;
        TLRPC.PhotoSize best = null;
        int bestA = 0;
        for (TLRPC.PhotoSize s : sizes) {
            if (s instanceof TLRPC.TL_photoSizeEmpty) continue;
            int a = s.w * s.h;
            if (a > bestA) { bestA = a; best = s; }
        }
        return best;
    }

    public static TLRPC.PhotoSize smallestThumb(List<TLRPC.PhotoSize> sizes) {
        if (sizes == null) return null;
        for (TLRPC.PhotoSize s : sizes) {
            if (s instanceof TLRPC.TL_photoSizeEmpty) continue;
            if (s.type != null && (s.type.equals("s") || s.type.equals("m"))) return s;
        }
        TLRPC.PhotoSize smallest = null;
        int smallestArea = Integer.MAX_VALUE;
        for (TLRPC.PhotoSize s : sizes) {
            if (s instanceof TLRPC.TL_photoSizeEmpty) continue;
            int area = s.w * s.h;
            if (area > 0 && area < smallestArea) {
                smallestArea = area;
                smallest = s;
            }
        }
        return smallest;
    }

    @SuppressLint("SetTextI18n")
    static int setupSingleMedia(MessageObject msg,
                                BackupImageView iv,
                                TextView overlay) {
        TLRPC.Message raw = msg.messageOwner;
        int height = dp(200);

        if (raw.media instanceof TLRPC.TL_messageMediaPhoto && raw.media.photo != null) {
            TLRPC.PhotoSize best = bestSize(raw.media.photo.sizes);
            if (best != null) {
                if (best.w > 0 && best.h > 0) {
                    int w = AndroidUtilities.displaySize.x - dp(32);
                    height = Math.max(dp(150), Math.min(dp(400), (int) (w * ((float) best.h / best.w))));
                }
                iv.setImage(ImageLocation.getForPhoto(best, raw.media.photo),
                        height + "_" + height, (ImageLocation) null, null, 0, raw.media.photo);
            }
            overlay.setVisibility(View.GONE);
        } else if (raw.media instanceof TLRPC.TL_messageMediaDocument && raw.media.document != null) {
            TLRPC.Document doc = raw.media.document;
            boolean isGif = false, isVideo = false;
            double duration = 0;
            int videoW = 0, videoH = 0;
            for (TLRPC.DocumentAttribute attr : doc.attributes) {
                if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                    isVideo = true;
                    duration = ((TLRPC.TL_documentAttributeVideo) attr).duration;
                    videoW = attr.w;
                    videoH = attr.h;
                    if (attr.w > 0 && attr.h > 0) {
                        int w = AndroidUtilities.displaySize.x - dp(32);
                        height = Math.max(dp(150), Math.min(dp(400), (int) (w * ((float) attr.h / attr.w))));
                    }
                }
                if (attr instanceof TLRPC.TL_documentAttributeAnimated) isGif = true;
            }

            if (isGif) {
                if (videoW > 0 && videoH > 0) {
                    int w = AndroidUtilities.displaySize.x - dp(32);
                    height = Math.max(dp(150), Math.min(dp(400), (int) (w * ((float) videoH / videoW))));
                }

                String thumbFilter = height + "_" + height;
                ImageLocation thumbLocation = null;
                if (doc.thumbs != null && !doc.thumbs.isEmpty()) {
                    TLRPC.PhotoSize thumb = bestSize(doc.thumbs);
                    if (thumb != null) {
                        thumbLocation = ImageLocation.getForDocument(thumb, doc);
                    }
                }

                iv.setImage(
                        ImageLocation.getForDocument(doc),
                        height + "_" + height,
                        thumbLocation,
                        thumbFilter,
                        (int) doc.size,
                        doc
                );
                iv.getImageReceiver().setAutoRepeat(1);
                iv.getImageReceiver().setAllowStartAnimation(true);

                overlay.setText("GIF");
                overlay.setVisibility(View.VISIBLE);
            } else {
                if (doc.thumbs != null && !doc.thumbs.isEmpty()) {
                    TLRPC.PhotoSize thumb = bestSize(doc.thumbs);
                    if (thumb != null)
                        iv.setImage(ImageLocation.getForDocument(thumb, doc),
                                height + "_" + height, null, null, 0, doc);
                }
                if (isVideo) {
                    int d = (int) duration;
                    overlay.setText(String.format(Locale.US, "▶ %d:%02d", d / 60, d % 60));
                    overlay.setVisibility(View.VISIBLE);
                } else {
                    overlay.setVisibility(View.GONE);
                }
            }
        }
        return height;
    }

    static void setupMediaThumb(MessageObject msg, BackupImageView v) {
        TLRPC.Message raw = msg.messageOwner;
        if (raw.media instanceof TLRPC.TL_messageMediaPhoto && raw.media.photo != null) {
            TLRPC.PhotoSize best = bestSize(raw.media.photo.sizes);
            if (best != null)
                v.setImage(ImageLocation.getForPhoto(best, raw.media.photo),
                        "80_80", (ImageLocation) null, null, 0, raw.media.photo);
        } else if (raw.media instanceof TLRPC.TL_messageMediaDocument && raw.media.document != null) {
            TLRPC.Document doc = raw.media.document;

            boolean isGif = false;
            for (TLRPC.DocumentAttribute attr : doc.attributes) {
                if (attr instanceof TLRPC.TL_documentAttributeAnimated) {
                    isGif = true;
                    break;
                }
            }

            if (isGif) {
                ImageLocation thumbLocation = null;
                if (doc.thumbs != null && !doc.thumbs.isEmpty()) {
                    TLRPC.PhotoSize thumb = bestSize(doc.thumbs);
                    if (thumb != null) {
                        thumbLocation = ImageLocation.getForDocument(thumb, doc);
                    }
                }
                v.setImage(
                        ImageLocation.getForDocument(doc),
                        "80_80",
                        thumbLocation,
                        "80_80",
                        (int) doc.size,
                        doc
                );
                v.getImageReceiver().setAutoRepeat(1);
                v.getImageReceiver().setAllowStartAnimation(true);
            } else {
                if (doc.thumbs != null && !doc.thumbs.isEmpty()) {
                    TLRPC.PhotoSize thumb = bestSize(doc.thumbs);
                    if (thumb != null)
                        v.setImage(ImageLocation.getForDocument(thumb, doc),
                                "80_80", null, null, 0, doc);
                }
            }
        }
    }
}