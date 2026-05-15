package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.annotation.SuppressLint;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Custom.CustomSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class FeedMediaHelper {

    public interface MediaClickListener {
        void onMediaClick(FeedController.FeedItem item, int index);
    }

    public interface ImageLoadCallback {
        void onMainImageLoaded(boolean fromCache);
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
            FeedRoundVideoView roundVideoView,
            ImageLoadCallback loadCallback) {

        List<MessageObject> mediaMessages = collectVisualMedia(item);

        if (roundVideoView != null) {
            roundVideoView.release();
            roundVideoView.setVisibility(View.GONE);
        }

        if (mediaMessages.isEmpty()) {
            clearAll(mainImage, mediaContainer, mediaOverlay, mediaRow, albumLabel);
            removeAlbumViews(mediaContainer);
            if (loadCallback != null) loadCallback.onMainImageLoaded(true);
            return;
        }

        resetForNewImage(mainImage, mediaContainer);

        MessageObject firstMsg = mediaMessages.get(0);
        if (mediaMessages.size() == 1 && firstMsg.isRoundVideo()) {
            clearAll(mainImage, mediaContainer, mediaOverlay, mediaRow, albumLabel);
            removeAlbumViews(mediaContainer);
            if (roundVideoView != null) roundVideoView.setMessage(firstMsg);
            if (loadCallback != null) loadCallback.onMainImageLoaded(true);
            return;
        }

        int h = setupSingleMedia(mediaMessages.get(0), mainImage, mediaOverlay, loadCallback);

        mediaContainer.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) mediaContainer.getLayoutParams();
        lp.height = h;
        mediaContainer.setLayoutParams(lp);
        mainImage.post(mainImage::invalidate);

        if (mediaMessages.size() >= 2) {
            if (loadCallback != null) loadCallback.onMainImageLoaded(true);

            FeedAlbumMode mode = CustomSettings.feedAlbumMode();
            if (mode == FeedAlbumMode.GRID) {
                setupAlbumGrid(mediaMessages, context, item,
                        mediaContainer, mainImage, mediaOverlay,
                        listener);
            } else {
                setupAlbumCarousel(mediaMessages, context, item,
                        mediaContainer, mainImage,
                        listener, h);
            }
            mediaRow.setVisibility(View.GONE);
            albumLabel.setVisibility(View.GONE);
            mediaOverlay.setVisibility(View.GONE);
        } else {
            removeAlbumViews(mediaContainer);
            mainImage.setVisibility(View.VISIBLE);
            mediaRow.setVisibility(View.GONE);
            albumLabel.setVisibility(View.GONE);
        }
    }

    private static void removeAlbumViews(FrameLayout mediaContainer) {
        for (int i = mediaContainer.getChildCount() - 1; i >= 0; i--) {
            android.view.View child = mediaContainer.getChildAt(i);
            if (child instanceof FeedAlbumCarouselView
                    || child instanceof FeedAlbumGridView) {
                mediaContainer.removeViewAt(i);
            }
        }
    }

    public static String overlayLabel(MessageObject msg) {
        if (msg == null || msg.messageOwner == null) return null;
        TLRPC.MessageMedia media = msg.messageOwner.media;
        if (media == null) return null;

        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            return msg.isLivePhoto() ? "LIVE" : null;
        }
        if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
            boolean isGif = false;
            boolean isVideo = false;
            int duration = 0;
            for (TLRPC.DocumentAttribute attr : media.document.attributes) {
                if (attr instanceof TLRPC.TL_documentAttributeAnimated) {
                    isGif = true;
                } else if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                    isVideo = true;
                    duration = (int) attr.duration;
                }
            }
            if (isGif) return "GIF";
            if (isVideo) return formatDurationLabel(duration);
        }
        return null;
    }

    public static void applyOverlayLabel(TextView overlay, MessageObject msg) {
        String label = overlayLabel(msg);
        if (label == null) {
            overlay.setVisibility(View.GONE);
            return;
        }
        overlay.setText(label);
        overlay.setVisibility(View.VISIBLE);
    }

    private static String formatDurationLabel(int seconds) {
        if (seconds < 0) seconds = 0;
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        if (h > 0) {
            return String.format(Locale.US, "▶ %d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "▶ %d:%02d", m, s);
    }

    public static List<MessageObject> collectVisualMedia(FeedController.FeedItem item) {
        List<MessageObject> result = new ArrayList<>();

        for (MessageObject msg : item.messages) {
            TLRPC.MessageMedia media = msg.messageOwner.media;
            if (media == null
                    || media instanceof TLRPC.TL_messageMediaEmpty
                    || media instanceof TLRPC.TL_messageMediaWebPage
                    || media instanceof TLRPC.TL_messageMediaPoll) continue;

            if (media instanceof TLRPC.TL_messageMediaPhoto) {
                result.add(msg);
            } else if (media instanceof TLRPC.TL_messageMediaDocument
                    && media.document != null) {

                if (FeedUtils.isSticker(media.document)) {
                    continue;
                }

                for (TLRPC.DocumentAttribute attr : media.document.attributes) {
                    if (attr instanceof TLRPC.TL_documentAttributeVideo
                            || attr instanceof TLRPC.TL_documentAttributeAnimated) {
                        result.add(msg);
                        break;
                    }
                }
            }
        }

        Collections.sort(result, Comparator.comparingInt(MessageObject::getId));

        return result;
    }

    private static void clearAll(BackupImageView mainImage,
                                 FrameLayout mediaContainer,
                                 TextView mediaOverlay,
                                 LinearLayout mediaRow,
                                 TextView albumLabel) {
        mainImage.setImageDrawable(null);
        mainImage.getImageReceiver().clearImage();
        mainImage.getImageReceiver().setDelegate(null);
        mediaContainer.setVisibility(View.GONE);
        mediaOverlay.setVisibility(View.GONE);
        mediaRow.setVisibility(View.GONE);
        albumLabel.setVisibility(View.GONE);
    }

    private static void resetForNewImage(BackupImageView mainImage,
                                         FrameLayout mediaContainer) {
        mainImage.setImageDrawable(null);
        mainImage.getImageReceiver().clearImage();
        mainImage.getImageReceiver().setDelegate(null);

        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) mediaContainer.getLayoutParams();
        lp.width = LinearLayout.LayoutParams.MATCH_PARENT;
        lp.gravity = Gravity.NO_GRAVITY;
        mediaContainer.setLayoutParams(lp);
        mainImage.setRoundRadius(dp(12));
    }

    public static TLRPC.PhotoSize bestSize(List<TLRPC.PhotoSize> sizes) {
        if (sizes == null) return null;
        TLRPC.PhotoSize best = null;
        int bestArea = 0;
        for (TLRPC.PhotoSize s : sizes) {
            if (s instanceof TLRPC.TL_photoSizeEmpty) continue;
            if (s instanceof TLRPC.TL_photoStrippedSize) continue;
            int area = s.w * s.h;
            if (area > bestArea) {
                bestArea = area;
                best = s;
            }
        }
        if (best == null) {
            for (TLRPC.PhotoSize s : sizes) {
                if (s instanceof TLRPC.TL_photoSizeEmpty) continue;
                if (s instanceof TLRPC.TL_photoStrippedSize) continue;
                return s;
            }
        }
        return best;
    }

    private static TLRPC.PhotoSize findStripped(List<TLRPC.PhotoSize> sizes) {
        if (sizes == null) return null;
        for (TLRPC.PhotoSize s : sizes) {
            if (s instanceof TLRPC.TL_photoStrippedSize) return s;
        }
        return null;
    }

    public static TLRPC.PhotoSize smallestThumb(List<TLRPC.PhotoSize> sizes) {
        if (sizes == null) return null;
        TLRPC.PhotoSize smallest = null;
        int smallestArea = Integer.MAX_VALUE;
        for (TLRPC.PhotoSize s : sizes) {
            if (s instanceof TLRPC.TL_photoSizeEmpty) continue;
            if (s instanceof TLRPC.TL_photoStrippedSize) continue;
            int area = s.w * s.h;
            if (area > 0 && area < smallestArea) {
                smallestArea = area;
                smallest = s;
            }
        }
        return smallest;
    }

    private static String makeFilter(int displayWidth, int height) {
        int side = Math.max(displayWidth, height);
        return side + "_" + side;
    }

    @SuppressLint("SetTextI18n")
    static int setupSingleMedia(MessageObject msg,
                                BackupImageView iv,
                                TextView overlay,
                                ImageLoadCallback loadCallback) {
        TLRPC.Message raw = msg.messageOwner;

        if (raw.media instanceof TLRPC.TL_messageMediaDocument && raw.media.document != null) {
            if (FeedUtils.isSticker(raw.media.document)) {
                iv.setImageDrawable(null);
                iv.getImageReceiver().clearImage();
                overlay.setVisibility(View.GONE);
                if (loadCallback != null) loadCallback.onMainImageLoaded(true);
                return dp(200);
            }
        }

        int displayWidth = AndroidUtilities.displaySize.x - dp(32);
        int height = dp(200);

        boolean isLivePhoto = msg.isLivePhoto();

        if (raw.media instanceof TLRPC.TL_messageMediaPhoto
                && raw.media.photo != null) {

            TLRPC.Photo photo = raw.media.photo;
            TLRPC.PhotoSize best = bestSize(photo.sizes);

            if (best != null) {
                if (best.w > 0 && best.h > 0) {
                    height = Math.max(dp(150), Math.min(dp(400),
                            (int) (displayWidth * ((float) best.h / best.w))));
                }
            }

            String filter = makeFilter(displayWidth, height);

            ImageLocation thumbLoc = null;
            String thumbFilter = null;
            TLRPC.PhotoSize stripped = findStripped(photo.sizes);
            if (stripped != null) {
                thumbLoc = ImageLocation.getForPhoto(stripped, photo);
                thumbFilter = "b";
            } else if (best != null) {
                TLRPC.PhotoSize small = smallestThumb(photo.sizes);
                if (small != null && small != best) {
                    thumbLoc = ImageLocation.getForPhoto(small, photo);
                    thumbFilter = "80_80_b";
                }
            }

            if (isLivePhoto && msg.getDocument() != null) {
                TLRPC.Document videoDoc = msg.getDocument();
                ImageLocation videoLoc = ImageLocation.getForDocument(videoDoc);
                int videoSize = videoDoc.size > 0 && videoDoc.size <= Integer.MAX_VALUE ? (int) videoDoc.size : 0;

                ImageLocation photoThumb = best != null ? ImageLocation.getForPhoto(best, photo) : thumbLoc;
                String photoThumbFilter = best != null ? filter : thumbFilter;

                iv.setImage(
                        videoLoc, filter,
                        photoThumb, photoThumbFilter,
                        videoSize, msg);

                iv.getImageReceiver().setAutoRepeat(1);
                iv.getImageReceiver().setAllowStartAnimation(true);
                iv.getImageReceiver().setDelegate((imageReceiver, set, isThumb, memCache) -> {
                    if (set && !isThumb) {
                        if (loadCallback != null) loadCallback.onMainImageLoaded(memCache);
                        iv.getImageReceiver().setAllowStartAnimation(true);
                        iv.getImageReceiver().startAnimation();
                        iv.invalidate();
                    }
                });

                applyOverlayLabel(overlay, msg);
            } else {
                if (best != null) {
                    iv.setImage(
                            ImageLocation.getForPhoto(best, photo), filter,
                            thumbLoc, thumbFilter,
                            0, msg);
                } else if (thumbLoc != null) {
                    iv.setImage(thumbLoc, filter, null, null, 0, msg);
                }
                iv.getImageReceiver().setDelegate((imageReceiver, set, isThumb, memCache) -> {
                    if (set && !isThumb) {
                        if (loadCallback != null) loadCallback.onMainImageLoaded(memCache);
                    }
                });
                applyOverlayLabel(overlay, msg);
            }

        } else if (raw.media instanceof TLRPC.TL_messageMediaDocument
                && raw.media.document != null) {

            TLRPC.Document doc = raw.media.document;
            boolean isGif = false, isVideo = false;
            double duration = 0;
            int videoW = 0, videoH = 0;

            for (TLRPC.DocumentAttribute attr : doc.attributes) {
                if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                    TLRPC.TL_documentAttributeVideo videoAttr =
                            (TLRPC.TL_documentAttributeVideo) attr;
                    isVideo = true;
                    duration = videoAttr.duration;
                    videoW = videoAttr.w;
                    videoH = videoAttr.h;
                }
                if (attr instanceof TLRPC.TL_documentAttributeAnimated) {
                    isGif = true;
                }
            }

            if (videoW > 0 && videoH > 0) {
                height = Math.max(dp(150), Math.min(dp(400),
                        (int) (displayWidth * ((float) videoH / videoW))));
            }

            ImageLocation docThumbLoc = null;
            String docThumbFilter = null;
            if (doc.thumbs != null && !doc.thumbs.isEmpty()) {
                TLRPC.PhotoSize stripped = findStripped(doc.thumbs);
                if (stripped != null) {
                    docThumbLoc = ImageLocation.getForDocument(stripped, doc);
                    docThumbFilter = "b";
                } else {
                    TLRPC.PhotoSize ts = bestSize(doc.thumbs);
                    if (ts != null) {
                        docThumbLoc = ImageLocation.getForDocument(ts, doc);
                        docThumbFilter = "80_80_b";
                    }
                }
            }

            if (isGif) {
                if (isVideo) {
                    TLRPC.PhotoSize thumbSize = bestSize(doc.thumbs);
                    if (thumbSize != null) {
                        String vFilter = makeFilter(displayWidth, height);
                        iv.setImage(
                                ImageLocation.getForDocument(thumbSize, doc), vFilter,
                                docThumbLoc, docThumbFilter,
                                0, msg);
                    } else if (docThumbLoc != null) {
                        iv.setImage(docThumbLoc,
                                displayWidth + "_" + height + "_b",
                                null, null, 0, msg);
                    }
                } else {
                    String gifFilter = makeFilter(displayWidth, height);

                    int docSize = 0;
                    if (doc.size > 0 && doc.size <= Integer.MAX_VALUE) {
                        docSize = (int) doc.size;
                    }

                    iv.setImage(
                            ImageLocation.getForDocument(doc), gifFilter,
                            docThumbLoc, docThumbFilter,
                            docSize, msg);
                    iv.getImageReceiver().setAutoRepeat(1);
                    iv.getImageReceiver().setAllowStartAnimation(true);
                    iv.getImageReceiver().setDelegate((imageReceiver, set, isThumb, memCache) -> {
                        if (set && !isThumb) {
                            if (loadCallback != null) loadCallback.onMainImageLoaded(memCache);
                            iv.getImageReceiver().setAllowStartAnimation(true);
                            iv.getImageReceiver().startAnimation();
                            iv.invalidate();
                        }
                    });
                }

                applyOverlayLabel(overlay, msg);
            } else {
                TLRPC.PhotoSize thumbSize = bestSize(doc.thumbs);
                if (thumbSize != null) {
                    String vFilter = makeFilter(displayWidth, height);
                    iv.setImage(
                            ImageLocation.getForDocument(thumbSize, doc), vFilter,
                            docThumbLoc, docThumbFilter,
                            0, msg);
                } else if (docThumbLoc != null) {
                    iv.setImage(docThumbLoc,
                            displayWidth + "_" + height + "_b",
                            null, null, 0, msg);
                }

                iv.getImageReceiver().setDelegate((imageReceiver, set, isThumb, memCache) -> {
                    if (set && !isThumb) {
                        if (loadCallback != null) loadCallback.onMainImageLoaded(memCache);
                    }
                });

                applyOverlayLabel(overlay, msg);
            }
        }

        return height;
    }

    static void setupMediaThumb(MessageObject msg, BackupImageView v) {
        TLRPC.Message raw = msg.messageOwner;
        v.getImageReceiver().clearImage();

        if (raw.media instanceof TLRPC.TL_messageMediaPhoto
                && raw.media.photo != null) {

            TLRPC.Photo photo = raw.media.photo;
            TLRPC.PhotoSize best = bestSize(photo.sizes);
            ImageLocation thumbLoc = null;
            TLRPC.PhotoSize stripped = findStripped(photo.sizes);
            if (stripped != null) {
                thumbLoc = ImageLocation.getForPhoto(stripped, photo);
            }

            if (msg.isLivePhoto() && msg.getDocument() != null && best != null) {
                TLRPC.Document videoDoc = msg.getDocument();
                int videoSize = videoDoc.size > 0 && videoDoc.size <= Integer.MAX_VALUE
                        ? (int) videoDoc.size : 0;
                v.setImage(
                        ImageLocation.getForDocument(videoDoc), "80_80",
                        ImageLocation.getForPhoto(best, photo), "80_80",
                        videoSize, msg);
                v.getImageReceiver().setAutoRepeat(1);
                v.getImageReceiver().setAllowStartAnimation(true);
            } else if (best != null) {
                v.setImage(
                        ImageLocation.getForPhoto(best, photo), "80_80",
                        thumbLoc, "b", 0, msg);
            } else if (thumbLoc != null) {
                v.setImage(thumbLoc, "80_80_b",
                        null, null, 0, msg);
            }

        } else if (raw.media instanceof TLRPC.TL_messageMediaDocument
                && raw.media.document != null) {

            TLRPC.Document doc = raw.media.document;
            boolean isGif = false;
            for (TLRPC.DocumentAttribute attr : doc.attributes) {
                if (attr instanceof TLRPC.TL_documentAttributeAnimated) {
                    isGif = true;
                    break;
                }
            }

            ImageLocation docThumbLoc = null;
            if (doc.thumbs != null) {
                TLRPC.PhotoSize stripped = findStripped(doc.thumbs);
                if (stripped != null)
                    docThumbLoc = ImageLocation.getForDocument(stripped, doc);
            }

            if (isGif) {
                ImageLocation thumbLoc = null;
                if (doc.thumbs != null && !doc.thumbs.isEmpty()) {
                    TLRPC.PhotoSize ts = bestSize(doc.thumbs);
                    if (ts != null)
                        thumbLoc = ImageLocation.getForDocument(ts, doc);
                }
                if (thumbLoc == null) thumbLoc = docThumbLoc;

                v.setImage(
                        ImageLocation.getForDocument(doc), "80_80",
                        thumbLoc,
                        thumbLoc == docThumbLoc ? "b" : "80_80",
                        (int) doc.size, msg);
                v.getImageReceiver().setAutoRepeat(1);
                v.getImageReceiver().setAllowStartAnimation(true);
            } else {
                TLRPC.PhotoSize thumb = bestSize(doc.thumbs);
                if (thumb != null) {
                    v.setImage(
                            ImageLocation.getForDocument(thumb, doc), "80_80",
                            docThumbLoc, "b", 0, msg);
                } else if (docThumbLoc != null) {
                    v.setImage(docThumbLoc, "80_80_b",
                            null, null, 0, msg);
                }
            }
        }
    }

    public static MessageObject findStickerMessage(FeedController.FeedItem item) {
        if (item == null) return null;
        for (MessageObject msg : item.messages) {
            TLRPC.MessageMedia media = msg.messageOwner.media;
            if (!(media instanceof TLRPC.TL_messageMediaDocument) || media.document == null)
                continue;
            if (FeedUtils.isSticker(media.document)) {
                return msg;
            }
        }
        return null;
    }

    private static void setupAlbumCarousel(
            List<MessageObject> mediaMessages,
            Context context,
            FeedController.FeedItem item,
            FrameLayout mediaContainer,
            BackupImageView mainImage,
            MediaClickListener listener,
            int heightPx) {

        for (int i = mediaContainer.getChildCount() - 1; i >= 0; i--) {
            android.view.View child = mediaContainer.getChildAt(i);
            if (child instanceof FeedAlbumGridView) {
                mediaContainer.removeViewAt(i);
            }
        }

        mainImage.setVisibility(android.view.View.GONE);
        mainImage.getImageReceiver().clearImage();

        FeedAlbumCarouselView carousel = null;
        for (int i = 0; i < mediaContainer.getChildCount(); i++) {
            android.view.View child = mediaContainer.getChildAt(i);
            if (child instanceof FeedAlbumCarouselView) {
                carousel = (FeedAlbumCarouselView) child;
                break;
            }
        }

        if (carousel == null) {
            carousel = new FeedAlbumCarouselView(context);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            mediaContainer.addView(carousel, 0, lp);
        }

        android.widget.LinearLayout.LayoutParams containerLp =
                (android.widget.LinearLayout.LayoutParams) mediaContainer.getLayoutParams();
        containerLp.height = heightPx;
        mediaContainer.setLayoutParams(containerLp);

        carousel.setMessages(mediaMessages, heightPx);

        carousel.setOnPageClickListener(index -> {
            if (listener != null) listener.onMediaClick(item, index);
        });
    }

    private static void setupAlbumGrid(
            List<MessageObject> mediaMessages,
            Context context,
            FeedController.FeedItem item,
            FrameLayout mediaContainer,
            BackupImageView mainImage,
            TextView mediaOverlay,
            MediaClickListener listener) {

        for (int i = mediaContainer.getChildCount() - 1; i >= 0; i--) {
            View child = mediaContainer.getChildAt(i);
            if (child instanceof FeedAlbumCarouselView) mediaContainer.removeViewAt(i);
        }

        mainImage.setVisibility(View.GONE);
        mediaOverlay.setVisibility(View.GONE);

        FeedAlbumGridView grid = null;
        for (int i = 0; i < mediaContainer.getChildCount(); i++) {
            View child = mediaContainer.getChildAt(i);
            if (child instanceof FeedAlbumGridView) {
                grid = (FeedAlbumGridView) child;
                break;
            }
        }

        if (grid == null) {
            grid = new FeedAlbumGridView(context);
            FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            mediaContainer.addView(grid, 0, glp);
        }

        grid.setMessages(mediaMessages);
        final FeedAlbumGridView finalGrid = grid;
        grid.setOnItemClickListener(index -> {
            if (listener != null) listener.onMediaClick(item, index);
        });

        grid.post(() -> {
            int h = finalGrid.getMeasuredHeight();
            if (h > 0) {
                LinearLayout.LayoutParams lp =
                        (LinearLayout.LayoutParams) mediaContainer.getLayoutParams();
                lp.height = h;
                mediaContainer.setLayoutParams(lp);
            }
        });
    }

    public static boolean hasVisualMediaSpoiler(FeedController.FeedItem item) {
        if (item == null) return false;
        for (MessageObject msg : item.messages) {
            if (msg.messageOwner == null) continue;
            TLRPC.MessageMedia media = msg.messageOwner.media;
            if (media == null) continue;

            boolean isVisual = false;
            if (media instanceof TLRPC.TL_messageMediaPhoto) {
                isVisual = true;
            } else if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
                for (TLRPC.DocumentAttribute attr : media.document.attributes) {
                    if (attr instanceof TLRPC.TL_documentAttributeVideo
                            || attr instanceof TLRPC.TL_documentAttributeAnimated) {
                        isVisual = true;
                        break;
                    }
                }
            }
            if (isVisual) {
                return media.spoiler;
            }
        }
        return false;
    }
}