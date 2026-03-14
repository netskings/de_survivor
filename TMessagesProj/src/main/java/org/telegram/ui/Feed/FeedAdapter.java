package org.telegram.ui.Feed;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.List;

public class FeedAdapter extends RecyclerView.Adapter<FeedAdapter.ViewHolder> {

    private static final int TYPE_POST = 0;
    private static final int TYPE_LOADING = 1;

    private final Context context;
    private final int currentAccount;
    private final Theme.ResourcesProvider resourceProvider;
    private final List<FeedController.FeedItem> items = new ArrayList<>();
    private boolean showLoading = false;
    private FeedPostCell.Callback cellCallback;

    public FeedAdapter(Context context, int account, Theme.ResourcesProvider resourceProvider) {
        this.context = context;
        this.currentAccount = account;
        this.resourceProvider = resourceProvider;
    }

    public void setCellCallback(FeedPostCell.Callback cb) { cellCallback = cb; }

    @SuppressLint("NotifyDataSetChanged")
    public void setItems(List<FeedController.FeedItem> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    public void addItems(List<FeedController.FeedItem> newItems) {
        int s = items.size();
        items.addAll(newItems);
        notifyItemRangeInserted(s, newItems.size());
    }

    public void setShowLoading(boolean show) {
        if (showLoading == show) return;
        showLoading = show;
        if (show) notifyItemInserted(items.size());
        else notifyItemRemoved(items.size());
    }

    public void updateItem(int pos) {
        if (pos >= 0 && pos < items.size()) notifyItemChanged(pos);
    }

    public List<FeedController.FeedItem> getItems() { return items; }

    public int findItemPosition(FeedController.FeedItem item) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getUniqueId().equals(item.getUniqueId())) return i;
        }
        return -1;
    }

    @Override public int getItemCount() { return items.size() + (showLoading ? 1 : 0); }
    @Override public int getItemViewType(int pos) { return pos >= items.size() ? TYPE_LOADING : TYPE_POST; }

    @NonNull @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_LOADING) {
            View v = new View(context);
            v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(60)));
            return new ViewHolder(v);
        }
        FeedPostCell cell = new FeedPostCell(context, currentAccount, resourceProvider);
        cell.setCallback(cellCallback);
        cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new ViewHolder(cell);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int pos) {
        if (pos >= items.size()) return;
        if (holder.itemView instanceof FeedPostCell) {
            ((FeedPostCell) holder.itemView).setPost(items.get(pos));
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ViewHolder(@NonNull View v) { super(v); }
    }

    public void setItemsSilent(List<FeedController.FeedItem> newItems) {
        items.clear();
        items.addAll(newItems);
    }
}