package org.telegram.ui.Feed;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

public class FeedViewHolder extends RecyclerView.ViewHolder {

    FeedViewHolder(@NonNull View v) {
        super(v);
    }

    void bind(Object item) {
        if (item instanceof FeedController.FeedSeparator
                && itemView instanceof FeedSeparatorCell) {
            ((FeedSeparatorCell) itemView).setText(
                    LocaleController.getString(R.string.FeedRecommendationsSeparator));

        } else if (item instanceof FeedController.FeedItem
                && itemView instanceof FeedPostCell) {
            ((FeedPostCell) itemView).setPost((FeedController.FeedItem) item);
        }
    }

    static FeedViewHolder create(Context context, int currentAccount,
                                 Theme.ResourcesProvider resourceProvider,
                                 FeedPostCell.Callback callback,
                                 int viewType) {
        if (viewType == FeedAdapter.VIEW_TYPE_SEPARATOR) {
            FeedSeparatorCell cell = new FeedSeparatorCell(context, resourceProvider);
            cell.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new FeedViewHolder(cell);
        }

        FeedPostCell cell = new FeedPostCell(context, currentAccount, resourceProvider);
        cell.setCallback(callback);
        cell.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return new FeedViewHolder(cell);
    }
}
