package org.telegram.ui.Feed;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.List;

public class FeedAdapter extends RecyclerView.Adapter<FeedAdapter.ViewHolder> {

    private static final int VIEW_TYPE_POST = 0;
    private static final int VIEW_TYPE_SEPARATOR = 1;

    private final Context context;
    private final int currentAccount;
    private final Theme.ResourcesProvider resourceProvider;
    private List<FeedController.FeedItem> feedItems = new ArrayList<>();
    private List<Object> displayItems;

    private FeedPostCell.Callback cellCallback;

    public FeedAdapter(Context context, int account, Theme.ResourcesProvider resourceProvider) {
        this.context = context;
        this.currentAccount = account;
        this.resourceProvider = resourceProvider;
        this.displayItems = FeedController.getInstance(account).getDisplayItems();
    }

    public void setCellCallback(FeedPostCell.Callback cb) {
        cellCallback = cb;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setItems(List<FeedController.FeedItem> items) {
        this.feedItems = new ArrayList<>(items);
        rebuildDisplay();
        notifyDataSetChanged();
    }

    private void rebuildDisplay() {
        displayItems = FeedController.getInstance(currentAccount).getDisplayItems();
    }

    public void updateItem(int pos) {
        if (pos >= 0 && pos < displayItems.size()) notifyItemChanged(pos);
    }

    public List<FeedController.FeedItem> getItems() {
        return feedItems;
    }

    public Object getDisplayItem(int position) {
        if (position >= 0 && position < displayItems.size()) {
            return displayItems.get(position);
        }
        return null;
    }

    public int findItemPosition(FeedController.FeedItem item) {
        for (int i = 0; i < displayItems.size(); i++) {
            if (displayItems.get(i) == item) return i;
        }
        return -1;
    }

    @Override
    public int getItemCount() {
        return displayItems.size();
    }

    @Override
    public int getItemViewType(int position) {
        Object item = displayItems.get(position);
        if (item instanceof FeedController.FeedSeparator) {
            return VIEW_TYPE_SEPARATOR;
        }
        return VIEW_TYPE_POST;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_SEPARATOR) {
            FeedSeparatorCell sepCell = new FeedSeparatorCell(context, resourceProvider);
            sepCell.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new ViewHolder(sepCell);
        }

        FeedPostCell postCell = new FeedPostCell(context, currentAccount, resourceProvider);
        postCell.setCallback(cellCallback);
        postCell.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return new ViewHolder(postCell);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        if (position < 0 || position >= displayItems.size()) return;

        Object item = displayItems.get(position);

        if (item instanceof FeedController.FeedSeparator
                && holder.itemView instanceof FeedSeparatorCell) {
            ((FeedSeparatorCell) holder.itemView).setText(
                    "You're all caught up! Here are some recommendations");
        } else if (item instanceof FeedController.FeedItem
                && holder.itemView instanceof FeedPostCell) {
            ((FeedPostCell) holder.itemView).setPost((FeedController.FeedItem) item);
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ViewHolder(@NonNull View v) {
            super(v);
        }
    }
}