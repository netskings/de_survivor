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
            Theme.ResourcesProvider rp,
            FeedRoundVideoView roundVideoView,
            ImageLoadCallback loadCallback) {

        List<MessageObject> mediaMessages = collectVisualMedia(item);

        if (roundVideoView != null) {
            roundVideoView.release();
            roundVideoView.setVisibility(View.GONE);
        }

        if (mediaMessages.isEmpty()) {
            clearAll(mainImage, mediaContainer, mediaOverlay, mediaRow, albumLabel);
            if (loadCallback != null) loadCallback.onMainImageLoaded(true);
            return;
        }

        resetForNewImage(mainImage, mediaContainer);

        MessageObject firstMsg = mediaMessages.get(0);
        if (mediaMessages.size() == 1 && firstMsg.isRoundVideo()) {
            clearAll(mainImage, mediaContainer, mediaOverlay, mediaRow, albumLabel);
            if (roundVideoView != null) roundVideoView.setMessage(firstMsg);
            if (loadCallback != null) loadCallback.onMainImageLoaded(true);
            return;
        }

        if (loadCallback != null) {
            mainImage.getImageReceiver().setDelegate(
                    (imageReceiver, set, thumb, memCache) -> {
                        if (set && !thumb) loadCallback.onMainImageLoaded(memCache);
                    });
        }

        int h = setupSingleMedia(mediaMessages.get(0), mainImage, mediaOverlay);
        mediaContainer.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) mediaContainer.getLayoutParams();
        lp.height = h;
        mediaContainer.setLayoutParams(lp);
        mainImage.post(mainImage::invalidate);

        if (mediaMessages.size() >= 2) {
            FeedAlbumMode mode = CustomSettings.feedAlbumMode();
            if (mode == FeedAlbumMode.GRID) {
                setupAlbumGrid(mediaMessages, context, item,
                        mediaContainer, mainImage, mediaOverlay,
                        listener);
            } else {
                setupAlbumCarousel(mediaMessages, context, item,
                        mediaContainer, mainImage,
                        listener, rp, h);
            }
            mediaRow.setVisibility(View.GONE);
            albumLabel.setVisibility(View.GONE);
        } else {
            mediaRow.setVisibility(View.GONE);
            albumLabel.setVisibility(View.GONE);
        }
    }

    private static List<MessageObject> collectVisualMedia(FeedController.FeedItem item) {
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
                                TextView overlay) {
        TLRPC.Message raw = msg.messageOwner;
        int displayWidth = AndroidUtilities.displaySize.x - dp(32);
        int height = dp(200);

        if (raw.media instanceof TLRPC.TL_messageMediaPhoto
                && raw.media.photo != null) {

            TLRPC.Photo photo = raw.media.photo;
            TLRPC.PhotoSize best = bestSize(photo.sizes);

            if (best != null) {
                if (best.w > 0 && best.h > 0) {
                    height = Math.max(dp(150), Math.min(dp(400),
                            (int) (displayWidth * ((float) best.h / best.w))));
                }

                String filter = makeFilter(displayWidth, height);

                ImageLocation thumbLoc = null;
                String thumbFilter = null;
                TLRPC.PhotoSize stripped = findStripped(photo.sizes);
                if (stripped != null) {
                    thumbLoc = ImageLocation.getForPhoto(stripped, photo);
                    thumbFilter = "b";
                } else {
                    TLRPC.PhotoSize small = smallestThumb(photo.sizes);
                    if (small != null && small != best) {
                        thumbLoc = ImageLocation.getForPhoto(small, photo);
                        thumbFilter = "80_80_b";
                    }
                }

                iv.setImage(
                        ImageLocation.getForPhoto(best, photo), filter,
                        thumbLoc, thumbFilter,
                        0, msg);
            } else {
                TLRPC.PhotoSize stripped = findStripped(photo.sizes);
                if (stripped != null) {
                    iv.setImage(
                            ImageLocation.getForPhoto(stripped, photo),
                            displayWidth + "_" + height + "_b",
                            null, null, 0, msg);
                }
            }
            overlay.setVisibility(View.GONE);

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
                iv.getImageReceiver().setDelegate((imageReceiver, set, thumb, memCache) -> {
                    if (set && !thumb) {
                        iv.getImageReceiver().setAllowStartAnimation(true);
                        iv.getImageReceiver().startAnimation();
                        iv.invalidate();
                    }
                });

                overlay.setText("GIF");
                overlay.setVisibility(View.VISIBLE);
            } else {
                TLRPC.PhotoSize thumb = bestSize(doc.thumbs);
                if (thumb != null) {
                    String vFilter = makeFilter(displayWidth, height);
                    iv.setImage(
                            ImageLocation.getForDocument(thumb, doc), vFilter,
                            docThumbLoc, docThumbFilter,
                            0, doc);
                } else if (docThumbLoc != null) {
                    iv.setImage(docThumbLoc,
                            displayWidth + "_" + height + "_b",
                            null, null, 0, doc);
                }

                if (isVideo) {
                    int d = (int) duration;
                    overlay.setText(String.format(Locale.US,
                            "▶ %d:%02d", d / 60, d % 60));
                    overlay.setVisibility(View.VISIBLE);
                } else {
                    overlay.setVisibility(View.GONE);
                }
            }
        }

        return height;
    }

    @SuppressLint("SetTextI18n")
    private static void setupAlbumRow(List<MessageObject> mediaMessages,
                                      Context context,
                                      FeedController.FeedItem item,
                                      LinearLayout mediaRow,
                                      TextView albumLabel,
                                      MediaClickListener listener,
                                      Theme.ResourcesProvider rp) {
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
            mediaRow.addView(thumb,
                    LayoutHelper.createLinear(80, 80, 0, 0, 4, 0));
        }
        if (mediaMessages.size() > 4) {
            TextView more = new TextView(context);
            more.setText("+" + (mediaMessages.size() - 4));
            more.setTextColor(Theme.getColor(
                    Theme.key_windowBackgroundWhiteGrayText3, rp));
            more.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            more.setGravity(Gravity.CENTER);
            mediaRow.addView(more,
                    LayoutHelper.createLinear(48, 80, Gravity.CENTER_VERTICAL));
        }
        mediaRow.setVisibility(View.VISIBLE);

        int photos = 0, videos = 0;
        for (MessageObject m : mediaMessages) {
            if (m.isVideo() || m.isRoundVideo()) videos++;
            else photos++;
        }
        StringBuilder sb = new StringBuilder("Album • ");
        if (photos > 0)
            sb.append(photos).append(photos == 1 ? " photo" : " photos");
        if (photos > 0 && videos > 0) sb.append(", ");
        if (videos > 0)
            sb.append(videos).append(videos == 1 ? " video" : " videos");
        albumLabel.setText(sb.toString());
        albumLabel.setVisibility(View.VISIBLE);
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

            if (best != null) {
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
                            docThumbLoc, "b", 0, doc);
                } else if (docThumbLoc != null) {
                    v.setImage(docThumbLoc, "80_80_b",
                            null, null, 0, doc);
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
            Theme.ResourcesProvider rp,
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
            carousel = new FeedAlbumCarouselView(context, rp);
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
}