/*
 * ManageQueueActivity.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.queue;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import net.cellar.App;
import net.cellar.BaseActivity;
import net.cellar.BuildConfig;
import net.cellar.R;
import net.cellar.model.Wish;
import net.cellar.supp.UiUtil;
import net.cellar.supp.Util;
import net.cellar.supp.Log;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;

public class ManageQueueActivity extends AppCompatActivity implements QueueManager.Listener {

    private static final String TAG = "ManageQ";
    /** https://en.wikipedia.org/wiki/Miscellaneous_Symbols_and_Pictographs#Emoji_modifiers */
    private static final String[] EMOJIS = new String[] {
            "\uD83D\uDC66",             // 👦
            "\uD83D\uDC66\uD83C\uDFFB", // 👦🏻
            "\uD83D\uDC66\uD83C\uDFFD", // 👦🏽
            "\uD83D\uDC66\uD83C\uDFFF", // 👦🏿
            "\uD83D\uDC69\uD83C\uDFFC", // 👩🏼
            "\uD83D\uDC69\uD83C\uDFFD", // 👩🏽
            "\uD83D\uDC69\uD83C\uDFFF", // 👩🏿
            "\uD83D\uDC7D"              // 👽
    };

    private final List<Wish> wishes = new ArrayList<>(4);

    private CoordinatorLayout coordinatorLayout;
    private RecyclerView recyclerViewWishes;
    private WishesAdapter wishesAdapter;
    private View viewEmptyQueue;
    private AlertDialog dialogConfirmDeletion;
    private BitmapDrawable drawableNextIcon;

    /**
     * Displays a dialog that asks for confirmation for deleting an entry.
     * @param wish Wish
     * @throws NullPointerException if the parameter is {@code null}
     */
    private void deleteWish(@NonNull Wish wish) {
        final CharSequence title = !TextUtils.isEmpty(wish.getTitle()) ? wish.getTitle() : wish.getUri().toString();
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.msg_confirmation)
                .setIcon(R.drawable.ic_baseline_warning_amber_24)
                .setMessage(getString(R.string.msg_really_delete_queued, title))
                .setNegativeButton(R.string.label_no, (dialog, which) -> dialog.cancel())
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    dialog.dismiss();
                    if (QueueManager.getInstance().remove(wish)) {
                        Snackbar.make(coordinatorLayout, getString(R.string.msg_queued_deleted, title), Snackbar.LENGTH_SHORT).show();
                    } else {
                        Snackbar.make(coordinatorLayout, getString(R.string.error_cant_delete, title), Snackbar.LENGTH_SHORT).show();
                    }
                });
        this.dialogConfirmDeletion = builder.create();
        Window dialogWindow = this.dialogConfirmDeletion.getWindow();
        if (dialogWindow != null) dialogWindow.setBackgroundDrawableResource(R.drawable.background);
        this.dialogConfirmDeletion.show();
    }

    /**
     * Creates the icon bitmap for the {@link R.id#action_next_from_queue R.id.action_next_from_queue} action.
     * @return Bitmap
     */
    @NonNull
    private Bitmap makeIcon() {
        int s = getResources().getDimensionPixelSize(android.R.dimen.app_icon_size);
        final Bitmap bitmap = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas();
        canvas.setBitmap(bitmap);
        canvas.drawColor(0x22000000);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        final List<String> list = Arrays.asList(EMOJIS);
        Collections.shuffle(list);
        final int n = Math.min(4, list.size());
        final float xstep = s / (float)n;
        for (int i = 1; i <= n; i++) {
            Bitmap b = Util.makeCharBitmap(list.get(i - 1), 0f, s, s, Color.BLACK, Color.TRANSPARENT, null);
            canvas.save();
            float scale = (float)Math.sqrt(i / (float)n);
            float xoff = s * 0.25f - (n - i) * xstep * 0.25f;
            canvas.scale(scale, scale);
            paint.setAlpha((int)(255f * scale));
            //if (BuildConfig.DEBUG) Log.i(TAG, "i=" + i + ", xoff=" + xoff + ", scale=" + scale + ", alpha=" + (int)(255f * scale));
            canvas.drawBitmap(b, xoff, xoff / 2f, paint);
            canvas.restore();
            b.recycle();
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(getResources().getColor(R.color.colorAccent));
        canvas.drawRoundRect(0, 0, s - 1, s - 1, 4f, 4f, paint);
        return bitmap;
    }

    /** {@inheritDoc} */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        BaseActivity.setAnimations(this);
        BaseActivity.setDarkMode(this);
        super.onCreate(savedInstanceState);

        getDelegate().setContentView(R.layout.activity_queued);
        Toolbar toolbar = getDelegate().findViewById(R.id.toolbar);
        getDelegate().setSupportActionBar(toolbar);

        this.coordinatorLayout = getDelegate().findViewById(R.id.coordinator_layout);
        this.viewEmptyQueue = getDelegate().findViewById(R.id.textViewNoQueued);
        this.recyclerViewWishes = getDelegate().findViewById(R.id.recyclerViewQueue);
        assert this.recyclerViewWishes != null;
        this.recyclerViewWishes.setHasFixedSize(true);
        this.wishesAdapter = new WishesAdapter();
        this.recyclerViewWishes.setAdapter(this.wishesAdapter);
        this.recyclerViewWishes.setLayoutManager(new LinearLayoutManager(this));
    }

    /** {@inheritDoc} */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.manage_queue, menu);
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_next_from_queue) {
            if (!QueueManager.getInstance().nextPlease(true)) {
                Snackbar.make(this.coordinatorLayout, R.string.error_queue_pop_failed, Snackbar.LENGTH_LONG).show();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** {@inheritDoc} */
    @Override
    protected void onPause() {
        QueueManager.getInstance().removeListener(this);
        UiUtil.dismissDialog(this.dialogConfirmDeletion);
        super.onPause();
    }

    /** {@inheritDoc} */
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem menuItemNextPlease = menu.findItem(R.id.action_next_from_queue);
        if (this.drawableNextIcon == null) {
            this.drawableNextIcon = new BitmapDrawable(getResources(), makeIcon());
        }
        menuItemNextPlease.setIcon(this.drawableNextIcon);
        menuItemNextPlease.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menuItemNextPlease.setVisible(QueueManager.getInstance().hasNonHeldStuff() && !((App)getApplicationContext()).hasActiveLoaders());
        return true;
    }

    /** {@inheritDoc} */
    @Override
    protected void onResume() {
        BaseActivity.setDarkMode(this);
        super.onResume();
        QueueManager.getInstance().addListener(this);
        refresh();
    }

    /** {@inheritDoc} */
    @Override
    public void queueChanged() {
        if (BuildConfig.DEBUG) Log.i(TAG, "queueChanged()");
        refresh();
    }

    private void refresh() {
        if (BuildConfig.DEBUG) Log.i(TAG, "refresh()");
        synchronized (this.wishes) {
            this.wishes.clear();
            this.wishes.addAll(QueueManager.getInstance().getWishes());
            this.viewEmptyQueue.setVisibility(this.wishes.isEmpty() ? View.VISIBLE : View.GONE);
        }
        this.wishesAdapter.notifyDataSetChanged();
        invalidateOptionsMenu();
    }

    /**
     *
     */
    private static class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnLongClickListener, PopupMenu.OnMenuItemClickListener {

        private final TextView textViewWishTitle;
        private final TextView textViewWishInfo;
        //private final ImageButton buttonDelete;
        private final ImageButton buttonMoveUp;

        /**
         * Constructor.
         * @param itemView View
         */
        public ViewHolder(@NonNull final View itemView) {
            super(itemView);
            this.textViewWishTitle = itemView.findViewById(R.id.textViewWishTitle);
            this.textViewWishInfo = itemView.findViewById(R.id.textViewWishInfo);
            //this.buttonDelete = itemView.findViewById(R.id.buttonDelete);
            this.buttonMoveUp = itemView.findViewById(R.id.buttonUp);

            //this.buttonDelete.setOnClickListener(this);
            this.buttonMoveUp.setOnClickListener(this);

            this.itemView.setOnLongClickListener(this);
        }

        /** {@inheritDoc} */
        @Override
        public void onClick(View v) {
            int position = getAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return;
            if (v == buttonMoveUp) {
                if (position < 1) return;
                QueueManager.getInstance().moveUp(position);
            }
        }

        /** {@inheritDoc} */
        @Override
        public boolean onLongClick(View v) {
            Context ctx = v.getContext();
            if (!(ctx instanceof ManageQueueActivity)) return false;
            final ManageQueueActivity activity = (ManageQueueActivity)ctx;
            int position = getAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return false;
            Wish wish = activity.wishes.get(position);
            PopupMenu popup = new PopupMenu(activity, v);
            popup.setOnMenuItemClickListener(this);
            MenuInflater inflater = popup.getMenuInflater();
            Menu menu = popup.getMenu();
            inflater.inflate(R.menu.queue_view, menu);
            //MenuItem menuItemDelete = menu.findItem(R.id.action_delete);
            MenuItem menuItemDefer = menu.findItem(R.id.action_defer);
            menuItemDefer.setTitle(wish.isHeld() ? R.string.action_defer_stop : R.string.action_defer);
            popup.show();
            return true;
        }

        /** {@inheritDoc} */
        @Override
        public boolean onMenuItemClick(MenuItem item) {
            int position = getAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return false;

            Context ctx = this.itemView.getContext();
            if (!(ctx instanceof ManageQueueActivity)) return false;
            final ManageQueueActivity activity = (ManageQueueActivity)ctx;

            Wish wish = activity.wishes.get(position);

            final int id = item.getItemId();
            if (id == R.id.action_delete) {
                activity.deleteWish(wish);
            } else if (id == R.id.action_defer) {
                QueueManager.getInstance().toggleHeld(position);
            }
            return false;
        }
    }

    /**
     * The adapter for the RecyclerView.
     */
    class WishesAdapter extends RecyclerView.Adapter<ManageQueueActivity.ViewHolder> {

        private final DateFormat dateFormatDay = DateFormat.getDateInstance(DateFormat.SHORT);
        private final DateFormat dateFormatTime = DateFormat.getTimeInstance(DateFormat.SHORT);

        /**
         * Constructor.
         */
        private WishesAdapter() {
            super();
            setHasStableIds(true);
        }

        /** {@inheritDoc} */
        @Override
        public int getItemCount() {
            int n;
            synchronized (ManageQueueActivity.this.wishes) {
                n = ManageQueueActivity.this.wishes.size();
            }
            return n;
        }

        /** {@inheritDoc} */
        @Override
        public long getItemId(int position) {
            long id;
            synchronized (ManageQueueActivity.this.wishes) {
                id = ManageQueueActivity.this.wishes.get(position).hashCode();
            }
            return id;
        }

        /** {@inheritDoc} */
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final Wish wish;
            synchronized (ManageQueueActivity.this.wishes) {
                wish = ManageQueueActivity.this.wishes.get(position);
            }
            boolean held = wish.isHeld();

            holder.buttonMoveUp.setVisibility(position > 0 ? View.VISIBLE : View.INVISIBLE);

            // display title
            CharSequence title = wish.getTitle();
            if (TextUtils.isEmpty(title)) title = wish.getUri().toString();
            if (TextUtils.isEmpty(title)) title = "???";
            holder.textViewWishTitle.setText(title);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                holder.itemView.setTooltipText(held ? wish.getUri().toString() + " (⌛)" : wish.getUri().toString());
            }

            // display creation timestamp
            long timestamp = wish.getTimestamp();
            if (timestamp > 0L) {
                Calendar now = new GregorianCalendar();
                Calendar calCreated = new GregorianCalendar();
                calCreated.setTimeInMillis(timestamp);
                if (now.get(Calendar.YEAR) == calCreated.get(Calendar.YEAR)
                        && now.get(Calendar.MONTH) == calCreated.get(Calendar.MONTH)
                        && now.get(Calendar.DAY_OF_MONTH) == calCreated.get(Calendar.DAY_OF_MONTH)) {
                    holder.textViewWishInfo.setText(held ? this.dateFormatTime.format(timestamp) + " (" + getString(R.string.label_deferred) + ")" : this.dateFormatTime.format(timestamp));
                } else {
                    holder.textViewWishInfo.setText(held ? this.dateFormatDay.format(timestamp) + " (" + getString(R.string.label_deferred) + ")" : this.dateFormatDay.format(timestamp));
                }
            } else {
                holder.textViewWishInfo.setText(held ? getString(R.string.label_deferred) : null);
            }

            // colorize according to the 'held' flag
            Resources res = getResources();
            @ColorInt int textColor = held ? res.getColor(R.color.colorDisabledText) : res.getColor(R.color.colorTextPrimary);
            holder.textViewWishTitle.setTextColor(textColor);
            holder.textViewWishInfo.setTextColor(textColor);
        }

        /** {@inheritDoc} */
        @NonNull
        @Override
        public ManageQueueActivity.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.queue_view, parent, false);
            return new ManageQueueActivity.ViewHolder(v);
        }

    }

}
