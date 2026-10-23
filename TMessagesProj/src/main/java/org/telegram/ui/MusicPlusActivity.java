package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AudioPlayerAlert;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PlayPauseDrawable;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * Music + : native port of the "Music +" Exteragram plugin, hosted as a tab in {@link MainTabsActivity}.
 *
 * Layout mirrors the plugin: centered header (overline + title), a three segment sliding pill
 * (Library / Queue / Lyrics), a rounded stage holding the pages and a mini player on the bottom.
 *
 * Ported so far: header, segmented pill, Queue page (56dp rows, 44dp covers, now playing highlight,
 * group corners 18dp / 5dp) and the mini player. Library and Lyrics pages are scaffolds.
 */
public class MusicPlusActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, MainTabsActivity.TabFragmentDelegate {

    private static final int ROW_DP = 56;
    private static final int COVER_DP = 44;
    private static final int R_BIG = 18;
    private static final int R_SMALL = 5;

    private static final int PAGE_LIBRARY = 0;
    private static final int PAGE_QUEUE = 1;
    private static final int PAGE_LYRICS = 2;

    private boolean hasMainTabs;
    private int currentPage = PAGE_QUEUE;

    private LinearLayout root;
    private TextView overlineView;
    private TextView titleView;
    private SegmentedPill pill;
    private FrameLayout stage;
    private final View[] pages = new View[3];
    private RecyclerListView queueList;
    private QueueAdapter queueAdapter;
    private LinearLayoutManager queueLayoutManager;
    private MiniPlayer miniPlayer;

    public MusicPlusActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        hasMainTabs = arguments != null && arguments.getBoolean("hasMainTabs", false);
        final NotificationCenter nc = NotificationCenter.getGlobalInstance();
        nc.addObserver(this, NotificationCenter.messagePlayingDidStart);
        nc.addObserver(this, NotificationCenter.messagePlayingDidReset);
        nc.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        nc.addObserver(this, NotificationCenter.messagePlayingGoingToStop);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        final NotificationCenter nc = NotificationCenter.getGlobalInstance();
        nc.removeObserver(this, NotificationCenter.messagePlayingDidStart);
        nc.removeObserver(this, NotificationCenter.messagePlayingDidReset);
        nc.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        nc.removeObserver(this, NotificationCenter.messagePlayingGoingToStop);
        super.onFragmentDestroy();
    }

    /* colors, resolved like the plugin does (theme keys, never hard-coded) */

    private int cBg() { return getThemedColor(Theme.key_windowBackgroundGray); }
    private int cItem() { return getThemedColor(Theme.key_windowBackgroundWhite); }
    private int cText() { return getThemedColor(Theme.key_windowBackgroundWhiteBlackText); }
    private int cSub() { return getThemedColor(Theme.key_windowBackgroundWhiteGrayText2); }
    private int cAccent() { return getThemedColor(Theme.key_player_buttonActive); }
    private int cSel() { return ColorUtils.blendARGB(cItem(), cAccent(), 0.18f); }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        hasOwnBackground = true;

        final FrameLayout host = new FrameLayout(context);
        fragmentView = host;
        host.setBackgroundColor(cBg());

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        host.addView(root, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        root.addView(createHeader(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));

        pill = new SegmentedPill(context);
        root.addView(pill, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 38, 10, 6, 10, 8));

        stage = new FrameLayout(context);
        stage.setClipChildren(false);
        root.addView(stage, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f, 10, 0, 10, 0));

        pages[PAGE_LIBRARY] = createScaffoldPage(context, getString(R.string.MusicPlusEmptyLibrary));
        pages[PAGE_QUEUE] = createQueuePage(context);
        pages[PAGE_LYRICS] = createScaffoldPage(context, getString(R.string.MusicPlusEmptyLyrics));
        for (View page : pages) {
            stage.addView(page, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }

        miniPlayer = new MiniPlayer(context);
        root.addView(miniPlayer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64, 10, 8, 10, 0));

        applyInsets();
        showPage(currentPage, false);
        updateQueue();
        return host;
    }

    private void applyInsets() {
        if (root == null) {
            return;
        }
        final int bottom = (hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : 0) + AndroidUtilities.navigationBarHeight;
        root.setPadding(0, AndroidUtilities.statusBarHeight, 0, bottom);
    }

    @Override
    public void onResume() {
        super.onResume();
        applyInsets();
        updateQueue();
    }

    /* header: [spacer 88] [overline + title centered] [search, more] */

    private View createHeader(Context context) {
        final FrameLayout header = new FrameLayout(context);

        final LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);

        overlineView = new TextView(context);
        overlineView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        overlineView.setTypeface(AndroidUtilities.bold());
        overlineView.setTextColor(cSub());
        overlineView.setSingleLine();
        overlineView.setGravity(Gravity.CENTER);
        col.addView(overlineView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(cText());
        titleView.setSingleLine();
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(Gravity.CENTER);
        col.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        header.addView(col, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER, 88, 0, 88, 0));

        final LinearLayout right = new LinearLayout(context);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.addView(createHeaderButton(context, R.drawable.msg_search, v -> { /* TODO: library search */ }), LayoutHelper.createLinear(48, 56));
        right.addView(createHeaderButton(context, R.drawable.ic_ab_other, v -> { /* TODO: queue menu */ }), LayoutHelper.createLinear(48, 56));
        header.addView(right, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.END, 0, 0, 8, 0));
        return header;
    }

    private View createHeaderButton(Context context, int icon, View.OnClickListener listener) {
        final android.widget.ImageView iv = new android.widget.ImageView(context);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        iv.setImageResource(icon);
        iv.setColorFilter(new android.graphics.PorterDuffColorFilter(cText(), android.graphics.PorterDuff.Mode.SRC_IN));
        iv.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        iv.setOnClickListener(listener);
        return iv;
    }

    /* pages */

    private View createScaffoldPage(Context context, String text) {
        final FrameLayout page = new FrameLayout(context);
        page.setBackground(Theme.createRoundRectDrawable(dp(R_BIG), cItem()));
        final TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tv.setTextColor(cSub());
        tv.setGravity(Gravity.CENTER);
        page.addView(tv, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, 0, 24, 0));
        return page;
    }

    private View createQueuePage(Context context) {
        queueList = new RecyclerListView(context, resourceProvider);
        queueLayoutManager = new LinearLayoutManager(context);
        queueList.setLayoutManager(queueLayoutManager);
        queueList.setAdapter(queueAdapter = new QueueAdapter());
        queueList.setClipToPadding(false);
        queueList.setVerticalScrollBarEnabled(false);
        queueList.setOnItemClickListener((view, position) -> {
            final ArrayList<MessageObject> list = MediaController.getInstance().getPlaylist();
            if (position >= 0 && position < list.size()) {
                MediaController.getInstance().findMessageInPlaylistAndPlay(list.get(position));
            }
        });
        return queueList;
    }

    private void showPage(int page, boolean animated) {
        final int old = currentPage;
        currentPage = page;
        pill.setSelected(page, animated);
        titleView.setText(page == PAGE_LIBRARY ? getString(R.string.MusicPlusLibrary)
            : page == PAGE_LYRICS ? getString(R.string.MusicPlusLyrics) : getString(R.string.MusicPlusQueue));
        final int w = stage.getWidth();
        for (int i = 0; i < pages.length; i++) {
            final View v = pages[i];
            v.animate().cancel();
            if (!animated || w <= 0 || (i != page && i != old)) {
                v.setTranslationX(0);
                v.setVisibility(i == page ? View.VISIBLE : View.GONE);
                continue;
            }
            final float dir = page > old ? 1f : -1f;
            v.setVisibility(View.VISIBLE);
            if (i == page) {
                v.setTranslationX(dir * w);
                v.animate().translationX(0).setDuration(280).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            } else {
                v.animate().translationX(-dir * w).setDuration(280).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                    .withEndAction(() -> { if (currentPage != old) { v.setVisibility(View.GONE); v.setTranslationX(0); } }).start();
            }
        }
    }

    private void updateQueue() {
        if (queueAdapter == null) {
            return;
        }
        final int n = MediaController.getInstance().getPlaylist().size();
        overlineView.setText(formatString(R.string.MusicPlusTracks, n).toUpperCase());
        queueAdapter.notifyDataSetChanged();
        if (miniPlayer != null) {
            miniPlayer.update();
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagePlayingDidStart || id == NotificationCenter.messagePlayingDidReset
            || id == NotificationCenter.messagePlayingPlayStateChanged || id == NotificationCenter.messagePlayingGoingToStop) {
            updateQueue();
        }
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        return false;
    }

    @Override
    public void onParentScrollToTop() {
        if (queueList != null && currentPage == PAGE_QUEUE) {
            queueList.smoothScrollToPosition(0);
        }
    }

    /* queue rows: 56dp, 44dp cover, title 16 / sub 14, grouped corners */

    private class QueueAdapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return MediaController.getInstance().getPlaylist().size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final TrackRow row = new TrackRow(parent.getContext());
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)));
            return new RecyclerListView.Holder(row);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            final ArrayList<MessageObject> list = MediaController.getInstance().getPlaylist();
            final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            final MessageObject m = list.get(position);
            ((TrackRow) holder.itemView).bind(m, position == 0, position == list.size() - 1,
                playing != null && playing.getId() == m.getId() && playing.getDialogId() == m.getDialogId());
        }
    }

    private class TrackRow extends FrameLayout {
        private final BackupImageView cover;
        private final TextView title;
        private final TextView sub;

        TrackRow(Context context) {
            super(context);
            cover = new BackupImageView(context);
            cover.setRoundRadius(dp(R_SMALL + 3));
            addView(cover, LayoutHelper.createFrame(COVER_DP, COVER_DP, Gravity.START | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));

            final LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_VERTICAL);
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            title.setSingleLine();
            title.setEllipsize(TextUtils.TruncateAt.END);
            sub = new TextView(context);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            sub.setSingleLine();
            sub.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            col.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            addView(col, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL, 16 + COVER_DP + 12, 0, 16, 0));
        }

        void bind(MessageObject m, boolean first, boolean last, boolean isPlaying) {
            title.setText(m.getMusicTitle());
            sub.setText(m.getMusicAuthor());
            title.setTextColor(isPlaying ? cAccent() : cText());
            sub.setTextColor(cSub());
            final float big = dp(R_BIG), small = dp(R_SMALL);
            final GradientDrawable bg = new GradientDrawable();
            bg.setColor(isPlaying ? cSel() : cItem());
            bg.setCornerRadii(new float[]{first ? big : small, first ? big : small, first ? big : small, first ? big : small,
                last ? big : small, last ? big : small, last ? big : small, last ? big : small});
            setBackground(bg);
            bindCover(cover, m);
        }
    }

    private static void bindCover(BackupImageView view, MessageObject m) {
        final String artwork = m.getArtworkUrl(true);
        final TLRPC.Document doc = m.getDocument();
        TLRPC.PhotoSize thumb = doc != null ? FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 90) : null;
        if (artwork != null) {
            view.setImage(ImageLocation.getForPath(artwork), "44_44", null, null, m);
        } else if (thumb != null) {
            view.setImage(ImageLocation.getForDocument(thumb, doc), "44_44", null, null, m);
        } else {
            view.setImageDrawable(null);
        }
    }

    /* three segment sliding pill, same metrics as the plugin (38dp, 3dp padding, 13sp bold labels) */

    private class SegmentedPill extends FrameLayout {
        private final TextView[] labels = new TextView[3];
        private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float position = PAGE_QUEUE;
        private ValueAnimator animator;

        SegmentedPill(Context context) {
            super(context);
            setWillNotDraw(false);
            setPadding(dp(3), dp(3), dp(3), dp(3));
            setBackground(Theme.createRoundRectDrawable(dp(19), cItem()));
            final LinearLayout inner = new LinearLayout(context);
            inner.setOrientation(LinearLayout.HORIZONTAL);
            final int[] names = {R.string.MusicPlusLibrary, R.string.MusicPlusQueue, R.string.MusicPlusLyrics};
            for (int i = 0; i < 3; i++) {
                final int page = i;
                final TextView tv = new TextView(context);
                tv.setText(getString(names[i]));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                tv.setTypeface(AndroidUtilities.bold());
                tv.setGravity(Gravity.CENTER);
                tv.setSingleLine();
                tv.setOnClickListener(v -> { if (page != currentPage) showPage(page, true); });
                labels[i] = tv;
                inner.addView(tv, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, i == 0 ? 0 : 2, 0, 0, 0));
            }
            addView(inner, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }

        void setSelected(int page, boolean animated) {
            if (animator != null) {
                animator.cancel();
            }
            if (!animated) {
                position = page;
                refresh();
                return;
            }
            animator = ValueAnimator.ofFloat(position, page);
            animator.setDuration(280);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            animator.addUpdateListener(a -> { position = (float) a.getAnimatedValue(); refresh(); });
            animator.start();
        }

        private void refresh() {
            for (int i = 0; i < 3; i++) {
                final float t = Math.max(0f, 1f - Math.abs(position - i));
                labels[i].setTextColor(ColorUtils.blendARGB(cSub(), cAccent(), t));
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float segW = (getWidth() - getPaddingLeft() - getPaddingRight()) / 3f;
            final float left = getPaddingLeft() + position * segW;
            rect.set(left, getPaddingTop(), left + segW, getHeight() - getPaddingBottom());
            pillPaint.setColor(cSel());
            canvas.drawRoundRect(rect, dp(16), dp(16), pillPaint);
            super.onDraw(canvas);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            refresh();
        }
    }

    /* mini player: cover, title/author, play-pause. Tap opens the stock full player. */

    private class MiniPlayer extends FrameLayout {
        private final BackupImageView cover;
        private final TextView title;
        private final TextView sub;
        private final android.widget.ImageView playButton;
        private final PlayPauseDrawable playDrawable = new PlayPauseDrawable(20);

        MiniPlayer(Context context) {
            super(context);
            setBackground(Theme.createRoundRectDrawable(dp(R_BIG), cItem()));
            cover = new BackupImageView(context);
            cover.setRoundRadius(dp(R_SMALL + 3));
            addView(cover, LayoutHelper.createFrame(48, 48, Gravity.START | Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

            final LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_VERTICAL);
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(AndroidUtilities.bold());
            title.setTextColor(cText());
            title.setSingleLine();
            title.setEllipsize(TextUtils.TruncateAt.END);
            sub = new TextView(context);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            sub.setTextColor(cSub());
            sub.setSingleLine();
            sub.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            col.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            addView(col, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL, 8 + 48 + 12, 0, 64, 0));

            playDrawable.setCallback(this);
            playButton = new android.widget.ImageView(context);
            playButton.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            playButton.setImageDrawable(playDrawable);
            playButton.setColorFilter(new android.graphics.PorterDuffColorFilter(cAccent(), android.graphics.PorterDuff.Mode.SRC_IN));
            playButton.setOnClickListener(v -> {
                final MediaController mc = MediaController.getInstance();
                final MessageObject m = mc.getPlayingMessageObject();
                if (m == null) {
                    return;
                }
                if (mc.isMessagePaused()) {
                    mc.playMessage(m);
                } else {
                    mc.pauseMessage(m);
                }
            });
            addView(playButton, LayoutHelper.createFrame(48, 48, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

            setOnClickListener(v -> {
                if (MediaController.getInstance().getPlayingMessageObject() != null && getParentActivity() != null) {
                    showDialog(new AudioPlayerAlert(getParentActivity(), resourceProvider));
                }
            });
        }

        void update() {
            final MediaController mc = MediaController.getInstance();
            final MessageObject m = mc.getPlayingMessageObject();
            if (m == null) {
                title.setText(getString(R.string.MusicPlusNothingPlaying));
                sub.setText("");
                cover.setImageDrawable(null);
                playDrawable.setPause(false, true);
                return;
            }
            title.setText(m.getMusicTitle());
            sub.setText(m.getMusicAuthor());
            bindCover(cover, m);
            playDrawable.setPause(!mc.isMessagePaused(), true);
        }
    }
}
