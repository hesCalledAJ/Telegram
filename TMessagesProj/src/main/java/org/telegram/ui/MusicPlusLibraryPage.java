package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MusicPlusLibrary;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Library tab of Music +: Playlists / Chats / Friends / Artists sections (ordered like the plugin),
 * list or grid per section, and playlist / artist detail pages.
 *
 * Chats and Friends sections render from the shared store but have no detail pages yet.
 */
public class MusicPlusLibraryPage extends FrameLayout {

    private static final int ROW_DP = 56;
    private static final int COVER_DP = 44;
    private static final int GRID_COLS = 4;

    private static final int T_LABEL = 0;
    private static final int T_HINT = 1;
    private static final int T_ROW = 2;
    private static final int T_CARD = 3;
    private static final int T_DETAIL_HEADER = 4;
    private static final int T_TRACK = 5;

    private static final int STATE_HOME = 0;
    private static final int STATE_PLAYLIST = 1;
    private static final int STATE_ARTIST = 2;

    private static class Item {
        int type;
        String kind;          // section kind: playlists / chats / friends / artists
        String title;
        String sub;
        String id;            // playlist id, artist name
        JSONObject track;
        int trackIndex;
        boolean first, last, round;
        int count;
    }

    private final MusicPlusActivity host;
    private final MusicPlusLibrary lib = MusicPlusLibrary.getInstance();
    private final RecyclerListView list;
    private final GridLayoutManager layoutManager;
    private final Adapter adapter = new Adapter();
    private final ArrayList<Item> items = new ArrayList<>();

    private int state = STATE_HOME;
    private String stateRef;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MusicPlusLibraryPage(Context context, MusicPlusActivity host) {
        super(context);
        this.host = host;
        setWillNotDraw(false);

        list = new RecyclerListView(context, host.getResourceProvider());
        layoutManager = new GridLayoutManager(context, GRID_COLS);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position < items.size() && items.get(position).type == T_CARD ? 1 : GRID_COLS;
            }
        });
        list.setLayoutManager(layoutManager);
        list.setAdapter(adapter);
        list.setClipToPadding(false);
        list.setVerticalScrollBarEnabled(false);
        list.setItemAnimator(null);
        list.setBackground(Theme.createRoundRectDrawable(dp(18), host.cBg()));
        list.setClipToOutline(true);
        addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        refresh();
    }

    /* navigation */

    public boolean canGoBack() {
        return state != STATE_HOME;
    }

    public boolean goBack() {
        if (state == STATE_HOME) {
            return false;
        }
        state = STATE_HOME;
        stateRef = null;
        refresh();
        list.scrollToPosition(0);
        host.onLibraryStateChanged();
        return true;
    }

    private void go(int newState, String ref) {
        state = newState;
        stateRef = ref;
        refresh();
        list.scrollToPosition(0);
        host.onLibraryStateChanged();
    }

    public void scrollToTop() {
        list.smoothScrollToPosition(0);
    }

    /* content */

    public void refresh() {
        items.clear();
        if (state == STATE_HOME) {
            buildHome();
        } else if (state == STATE_PLAYLIST) {
            buildPlaylist();
        } else {
            buildArtist();
        }
        adapter.notifyDataSetChanged();
        layoutManager.setSpanCount(GRID_COLS);
    }

    private Item item(int type) {
        final Item it = new Item();
        it.type = type;
        items.add(it);
        return it;
    }

    private String tracksMeta(List<JSONObject> tracks) {
        final int n = tracks.size();
        final int mins = MusicPlusLibrary.minutes(tracks);
        String s = formatString(R.string.MusicPlusTracks, n);
        if (mins >= 60) {
            s += " · " + (mins % 60 == 0 ? formatString(R.string.MusicPlusHours, mins / 60)
                : formatString(R.string.MusicPlusHoursMinutes, mins / 60, mins % 60));
        } else if (mins > 0) {
            s += " · " + formatString(R.string.MusicPlusMinutes, mins);
        }
        return s;
    }

    private void buildHome() {
        for (String section : MusicPlusLibrary.SECTION_ORDER) {
            switch (section) {
                case "playlists": buildPlaylists(); break;
                case "chats": buildChats(); break;
                case "friends": buildFriends(); break;
                case "artists": buildArtists(); break;
            }
        }
    }

    private void label(String kind, int titleRes) {
        final Item it = item(T_LABEL);
        it.kind = kind;
        it.title = getString(titleRes);
    }

    private void hint(int res) {
        item(T_HINT).title = getString(res);
    }

    private void buildPlaylists() {
        label("playlists", R.string.MusicPlusPlaylists);
        final org.json.JSONArray all = lib.playlists();
        final boolean grid = "grid".equals(lib.view("playlists"));
        for (int i = 0; i < all.length(); i++) {
            final JSONObject p = all.optJSONObject(i);
            if (p == null) {
                continue;
            }
            final Item it = item(grid ? T_CARD : T_ROW);
            it.kind = "playlists";
            it.id = p.optString("id");
            it.title = p.optString("name");
            it.sub = tracksMeta(MusicPlusLibrary.trackList(p));
            it.first = i == 0;
            it.last = i == all.length() - 1;
            final ArrayList<JSONObject> tracks = MusicPlusLibrary.trackList(p);
            it.track = tracks.isEmpty() ? null : tracks.get(0);
        }
        if (all.length() == 0) {
            hint(R.string.MusicPlusNoPlaylists);
        }
    }

    private void buildChats() {
        label("chats", R.string.MusicPlusChats);
        final org.json.JSONArray all = lib.chats();
        final boolean grid = "grid".equals(lib.view("chats"));
        for (int i = 0; i < all.length(); i++) {
            final JSONObject c = all.optJSONObject(i);
            if (c == null) {
                continue;
            }
            final Item it = item(grid ? T_CARD : T_ROW);
            it.kind = "chats";
            it.id = c.optLong("d") + ":" + c.optLong("t");
            it.title = c.optString("n");
            it.sub = getString(R.string.MusicPlusTapToLoad);
            it.round = true;
            it.first = i == 0;
            it.last = i == all.length() - 1;
        }
        if (all.length() == 0) {
            hint(R.string.MusicPlusNoChats);
        }
    }

    private void buildFriends() {
        label("friends", R.string.MusicPlusFriends);
        final org.json.JSONArray all = lib.friends();
        final boolean grid = "grid".equals(lib.view("friends"));
        for (int i = 0; i < all.length(); i++) {
            final JSONObject f = all.optJSONObject(i);
            if (f == null) {
                continue;
            }
            final Item it = item(grid ? T_CARD : T_ROW);
            it.kind = "friends";
            it.id = String.valueOf(f.optLong("d", f.optLong("id")));
            it.title = f.optString("n", getString(R.string.MusicPlusProfile));
            it.sub = getString(R.string.MusicPlusTapToLoad);
            it.round = true;
            it.first = i == 0;
            it.last = i == all.length() - 1;
        }
        if (all.length() == 0) {
            hint(R.string.MusicPlusNoFriends);
        }
    }

    private void buildArtists() {
        final List<Map.Entry<String, ArrayList<JSONObject>>> artists = lib.artists();
        if (artists.isEmpty()) {
            return;
        }
        label("artists", R.string.MusicPlusArtists);
        final boolean grid = "grid".equals(lib.view("artists"));
        for (int i = 0; i < artists.size(); i++) {
            final Map.Entry<String, ArrayList<JSONObject>> a = artists.get(i);
            final Item it = item(grid ? T_CARD : T_ROW);
            it.kind = "artists";
            it.id = a.getKey();
            it.title = a.getKey();
            it.sub = tracksMeta(a.getValue());
            it.round = true;
            it.track = a.getValue().get(0);
            it.first = i == 0;
            it.last = i == artists.size() - 1;
        }
    }

    private void detailHeader(String title, String meta) {
        final Item it = item(T_DETAIL_HEADER);
        it.title = title;
        it.sub = meta;
    }

    private void buildPlaylist() {
        final JSONObject p = lib.playlist(stateRef);
        if (p == null) {
            state = STATE_HOME;
            buildHome();
            return;
        }
        final ArrayList<JSONObject> tracks = MusicPlusLibrary.trackList(p);
        detailHeader(p.optString("name"), tracksMeta(tracks));
        for (int i = 0; i < tracks.size(); i++) {
            final Item it = item(T_TRACK);
            it.id = stateRef;
            it.track = tracks.get(i);
            it.trackIndex = i;
            it.first = i == 0;
            it.last = i == tracks.size() - 1;
        }
    }

    private void buildArtist() {
        ArrayList<JSONObject> tracks = new ArrayList<>();
        for (Map.Entry<String, ArrayList<JSONObject>> a : lib.artists()) {
            if (a.getKey().equals(stateRef)) {
                tracks = a.getValue();
            }
        }
        detailHeader(stateRef, tracksMeta(tracks));
        for (int i = 0; i < tracks.size(); i++) {
            final Item it = item(T_TRACK);
            it.id = null;
            it.track = tracks.get(i);
            it.trackIndex = i;
            it.first = i == 0;
            it.last = i == tracks.size() - 1;
        }
    }

    /* playback of saved tracks: resolve the messages first, then hand the whole list to the player */

    private ArrayList<JSONObject> currentTracks() {
        final ArrayList<JSONObject> out = new ArrayList<>();
        for (Item it : items) {
            if (it.type == T_TRACK) {
                out.add(it.track);
            }
        }
        return out;
    }

    private void playTracks(int startIndex) {
        final ArrayList<JSONObject> tracks = currentTracks();
        if (tracks.isEmpty()) {
            return;
        }
        final JSONObject wanted = startIndex >= 0 && startIndex < tracks.size() ? tracks.get(startIndex) : tracks.get(0);
        lib.resolve(UserConfig.selectedAccount, tracks, messages -> {
            if (messages.isEmpty()) {
                BulletinFactory.of(host).createSimpleBulletin(R.raw.error, getString(R.string.MusicPlusTracksUnavailable)).show();
                return;
            }
            MessageObject current = lib.cached(wanted);
            if (current == null) {
                current = messages.get(0);
            }
            MediaController.getInstance().setPlaylist(messages, current, 0, false, null);
            host.onLibraryStateChanged();
            refresh();
        });
    }

    /* menus + dialogs */

    private void showPlaylistMenu(View anchor, String id) {
        final JSONObject p = lib.playlist(id);
        if (p == null || host.getParentActivity() == null) {
            return;
        }
        final boolean system = MusicPlusLibrary.LAST_QUEUE_ID.equals(id);
        final ItemOptions options = ItemOptions.makeOptions(host, anchor);
        options.addIf(!system, R.drawable.msg_edit, getString(R.string.MusicPlusRename), () -> promptName(getString(R.string.MusicPlusRename), p.optString("name"), name -> {
            lib.renamePlaylist(id, name);
            refresh();
        }));
        options.add(R.drawable.msg_delete, getString(R.string.Delete), true, () -> {
            lib.deletePlaylist(id);
            if (state == STATE_PLAYLIST) {
                goBack();
            } else {
                refresh();
            }
        });
        options.show();
    }

    public interface NameCallback {
        void onName(String name);
    }

    public void promptName(String title, String initial, NameCallback callback) {
        if (host.getParentActivity() == null) {
            return;
        }
        final Context context = host.getParentActivity();
        final AlertDialog.Builder builder = new AlertDialog.Builder(context, host.getResourceProvider());
        builder.setTitle(title);
        final EditTextBoldCursor input = new EditTextBoldCursor(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        input.setTextColor(host.cText());
        input.setHintText(getString(R.string.MusicPlusPlaylistName));
        input.setHintColor(host.cSub());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(true);
        input.setText(initial);
        input.setSelection(input.length());
        input.setLineColors(host.cSub(), host.cAccent(), host.cSub());
        final LinearLayout box = new LinearLayout(context);
        box.setPadding(dp(24), 0, dp(24), 0);
        box.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        builder.setView(box);
        builder.setPositiveButton(getString(R.string.OK), (d, w) -> {
            final String name = input.getText().toString().trim();
            if (!name.isEmpty()) {
                callback.onName(name);
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        host.showDialog(builder.create());
    }

    /* adapter */

    private class Adapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            final int t = holder.getItemViewType();
            return t == T_ROW || t == T_CARD || t == T_TRACK;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            final Context c = parent.getContext();
            final View v;
            switch (type) {
                case T_LABEL: v = new LabelView(c); break;
                case T_HINT: v = new HintView(c); break;
                case T_CARD: v = new CardView(c); break;
                case T_DETAIL_HEADER: v = new DetailHeaderView(c); break;
                default: v = new RowView(c); break;
            }
            final int h = type == T_ROW || type == T_TRACK ? dp(ROW_DP) : ViewGroup.LayoutParams.WRAP_CONTENT;
            v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
            return new RecyclerListView.Holder(v);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            final Item it = items.get(position);
            final View v = holder.itemView;
            if (v instanceof LabelView) {
                ((LabelView) v).bind(it);
            } else if (v instanceof HintView) {
                ((HintView) v).setText(it.title);
            } else if (v instanceof CardView) {
                ((CardView) v).bind(it);
            } else if (v instanceof DetailHeaderView) {
                ((DetailHeaderView) v).bind(it);
            } else {
                ((RowView) v).bind(it);
            }
            v.setOnClickListener(null);
            v.setOnLongClickListener(null);
            if (it.type == T_ROW || it.type == T_CARD) {
                v.setOnClickListener(x -> onItemClick(it));
                v.setOnLongClickListener(x -> {
                    if ("playlists".equals(it.kind)) {
                        showPlaylistMenu(x, it.id);
                        return true;
                    }
                    return false;
                });
            } else if (it.type == T_TRACK) {
                v.setOnClickListener(x -> playTracks(it.trackIndex));
                v.setOnLongClickListener(x -> {
                    if (it.id != null && host.getParentActivity() != null) {
                        final ItemOptions o = ItemOptions.makeOptions(host, x);
                        o.add(R.drawable.msg_delete, getString(R.string.MusicPlusRemove), true, () -> {
                            lib.removeTrack(it.id, it.trackIndex);
                            refresh();
                        });
                        o.show();
                        return true;
                    }
                    return false;
                });
            }
        }
    }

    private void onItemClick(Item it) {
        if ("playlists".equals(it.kind)) {
            go(STATE_PLAYLIST, it.id);
        } else if ("artists".equals(it.kind)) {
            go(STATE_ARTIST, it.id);
        } else {
            BulletinFactory.of(host).createSimpleBulletin(R.raw.info, getString(R.string.MusicPlusComingSoon)).show();
        }
    }

    /* views, metrics copied from the plugin */

    private class LabelView extends LinearLayout {
        private final TextView title;
        private final TextView toggle;
        private String kind;

        LabelView(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(48));
            setPadding(dp(16), dp(14), dp(6), dp(2));
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(AndroidUtilities.bold());
            title.setTextColor(host.cAccent());
            title.setSingleLine();
            addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            toggle = new TextView(context);
            toggle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            toggle.setTypeface(AndroidUtilities.bold());
            toggle.setTextColor(host.cAccent());
            toggle.setGravity(Gravity.CENTER);
            toggle.setPadding(dp(10), 0, dp(10), 0);
            toggle.setOnClickListener(v -> {
                lib.toggleView(kind);
                refresh();
            });
            addView(toggle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32));
        }

        void bind(Item it) {
            kind = it.kind;
            title.setText(it.title);
            toggle.setText(getString("grid".equals(lib.view(kind)) ? R.string.MusicPlusList : R.string.MusicPlusGrid));
        }
    }

    private class HintView extends TextView {
        HintView(Context context) {
            super(context);
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            setTextColor(host.cSub());
            setPadding(dp(16), dp(6), dp(16), dp(10));
        }
    }

    private class DetailHeaderView extends LinearLayout {
        private final TextView title;
        private final TextView meta;

        DetailHeaderView(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(10), dp(10), dp(10), dp(8));

            final android.widget.ImageView back = new android.widget.ImageView(context);
            back.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            back.setImageResource(R.drawable.ic_ab_back);
            back.setColorFilter(host.cText());
            final GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(ColorUtils.blendARGB(host.cBg(), host.cSub(), 0.13f));
            back.setBackground(bg);
            back.setOnClickListener(v -> goBack());
            addView(back, LayoutHelper.createLinear(40, 40));

            final LinearLayout col = new LinearLayout(context);
            col.setOrientation(VERTICAL);
            col.setPadding(dp(12), 0, dp(8), 0);
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            title.setTypeface(AndroidUtilities.bold());
            title.setTextColor(host.cText());
            title.setSingleLine();
            title.setEllipsize(TextUtils.TruncateAt.END);
            meta = new TextView(context);
            meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            meta.setTextColor(host.cSub());
            meta.setSingleLine();
            col.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            col.addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            final TextView play = new TextView(context);
            play.setText(getString(R.string.MusicPlusPlay));
            play.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            play.setTypeface(AndroidUtilities.bold());
            play.setTextColor(host.cAccent());
            play.setGravity(Gravity.CENTER);
            play.setPadding(dp(14), 0, dp(14), 0);
            play.setBackground(Theme.createRoundRectDrawable(dp(16), ColorUtils.blendARGB(host.cItem(), host.cAccent(), 0.18f)));
            play.setOnClickListener(v -> playTracks(0));
            addView(play, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32));
        }

        void bind(Item it) {
            title.setText(it.title);
            meta.setText(it.sub);
        }
    }

    /** Tile: cover when the track message is cached, accent glyph on tinted square otherwise. */
    private class Tile extends FrameLayout {
        private final BackupImageView image;
        private final TextView glyph;
        private final int radius;

        Tile(Context context, int radiusDp) {
            super(context);
            radius = dp(radiusDp);
            setBackground(Theme.createRoundRectDrawable(radius, ColorUtils.blendARGB(host.cItem(), host.cAccent(), 0.2f)));
            glyph = new TextView(context);
            glyph.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            glyph.setTypeface(AndroidUtilities.bold());
            glyph.setTextColor(host.cAccent());
            glyph.setGravity(Gravity.CENTER);
            addView(glyph, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            image = new BackupImageView(context);
            image.setRoundRadius(radius);
            addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }

        void bind(JSONObject track, String glyphText, boolean round) {
            glyph.setText(glyphText);
            if (round) {
                image.setRoundRadius(dp(1000));
                setBackground(Theme.createRoundRectDrawable(dp(1000), ColorUtils.blendARGB(host.cItem(), host.cAccent(), 0.2f)));
            } else {
                image.setRoundRadius(radius);
            }
            image.setImageDrawable(null);
            final MessageObject m = track == null ? null : lib.cached(track);
            if (m != null) {
                bindCover(image, m);
            }
        }
    }

    private static void bindCover(BackupImageView view, MessageObject m) {
        final String artwork = m.getArtworkUrl(true);
        final TLRPC.Document doc = m.getDocument();
        final TLRPC.PhotoSize thumb = doc != null ? FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 90) : null;
        if (artwork != null) {
            view.setImage(ImageLocation.getForPath(artwork), "44_44", null, null, m);
        } else if (thumb != null) {
            view.setImage(ImageLocation.getForDocument(thumb, doc), "44_44", null, null, m);
        }
    }

    private GradientDrawable groupBg(int color, boolean first, boolean last) {
        final float big = dp(18), small = dp(5);
        final float t = first ? big : small, b = last ? big : small;
        final GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadii(new float[]{t, t, t, t, b, b, b, b});
        return g;
    }

    private class RowView extends LinearLayout {
        private final Tile tile;
        private final TextView title;
        private final TextView sub;

        RowView(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(16), 0, dp(14), 0);
            tile = new Tile(context, 8);
            addView(tile, LayoutHelper.createLinear(COVER_DP, COVER_DP));
            final LinearLayout info = new LinearLayout(context);
            info.setOrientation(VERTICAL);
            info.setGravity(Gravity.CENTER_VERTICAL);
            info.setPadding(dp(12), 0, dp(4), 0);
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            title.setTextColor(host.cText());
            title.setSingleLine();
            title.setEllipsize(TextUtils.TruncateAt.END);
            sub = new TextView(context);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            sub.setSingleLine();
            sub.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            info.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            addView(info, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        }

        void bind(Item it) {
            setBackground(groupBg(host.cItem(), it.first, it.last));
            if (it.type == T_TRACK) {
                title.setText(it.track.optString("t"));
                sub.setText(it.track.optString("a"));
                sub.setTextColor(host.cSub());
                final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
                final boolean isPlaying = playing != null && playing.getId() == it.track.optInt("i")
                    && playing.getDialogId() == it.track.optLong("d");
                title.setTextColor(isPlaying ? host.cAccent() : host.cText());
                tile.bind(it.track, "♫", false);
            } else {
                title.setText(it.title);
                title.setTextColor(host.cText());
                sub.setText(it.sub);
                sub.setTextColor(host.cAccent());
                tile.bind(it.track, "chats".equals(it.kind) ? "#" : "♫", it.round);
            }
        }
    }

    private class CardView extends LinearLayout {
        private final Tile tile;
        private final TextView title;
        private final TextView sub;

        CardView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(dp(4), dp(10), dp(4), dp(8));
            tile = new Tile(context, 12);
            final int size = Math.max(dp(42), Math.min(dp(120), (AndroidUtilities.displaySize.x - dp(96)) / GRID_COLS));
            addView(tile, LayoutHelper.createLinear(size, size));
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            title.setTypeface(AndroidUtilities.bold());
            title.setTextColor(host.cText());
            title.setGravity(Gravity.CENTER);
            title.setSingleLine();
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(0, dp(8), 0, 0);
            sub = new TextView(context);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
            sub.setTextColor(host.cSub());
            sub.setGravity(Gravity.CENTER);
            sub.setSingleLine();
            sub.setEllipsize(TextUtils.TruncateAt.END);
            addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        void bind(Item it) {
            title.setText(it.title);
            sub.setText(it.sub);
            tile.bind(it.track, "chats".equals(it.kind) ? "#" : "♫", it.round);
        }
    }
}
