package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Library store for the Music + tab.
 *
 * Uses the same JSON schema as the Exteragram plugin's "library_v1" setting so a plugin export can be
 * imported as is:
 * {
 *   "playlists": [{"id": "...", "name": "...", "sm": ["title", false],
 *                  "tracks": [{"i": msgId, "d": dialogId, "t": title, "a": author, "s": seconds}]}],
 *   "chats":     [{"d": dialogId, "t": topicId, "n": name, "a": account}],
 *   "friends":   [{"d": userId, "n": name, "a": account}],
 *   "views":     {"playlists": "list|grid", ...},
 *   "last":      null
 * }
 */
public class MusicPlusLibrary {

    public static final String LAST_QUEUE_ID = "last_queue";
    public static final String[] SECTION_ORDER = {"playlists", "chats", "friends", "artists"};
    private static final int MAX_TRACKS = 5000;

    private static volatile MusicPlusLibrary instance;

    public static MusicPlusLibrary getInstance() {
        MusicPlusLibrary local = instance;
        if (local == null) {
            synchronized (MusicPlusLibrary.class) {
                local = instance;
                if (local == null) {
                    instance = local = new MusicPlusLibrary();
                }
            }
        }
        return local;
    }

    private final SharedPreferences prefs;
    private JSONObject data;
    private final HashMap<String, MessageObject> messageCache = new HashMap<>();
    private final Set<String> missing = new HashSet<>();

    private MusicPlusLibrary() {
        prefs = ApplicationLoader.applicationContext.getSharedPreferences("musicplus", Context.MODE_PRIVATE);
    }

    /* data */

    private synchronized JSONObject data() {
        if (data == null) {
            try {
                data = new JSONObject(prefs.getString("library_v1", "{}"));
            } catch (Exception e) {
                data = new JSONObject();
            }
            try {
                for (String key : new String[]{"playlists", "chats", "friends"}) {
                    if (!(data.opt(key) instanceof JSONArray)) {
                        data.put(key, new JSONArray());
                    }
                }
                if (!(data.opt("views") instanceof JSONObject)) {
                    data.put("views", new JSONObject());
                }
            } catch (JSONException ignore) {
            }
        }
        return data;
    }

    public synchronized void save() {
        prefs.edit().putString("library_v1", data().toString()).apply();
    }

    public synchronized void importJson(String json) throws JSONException {
        data = new JSONObject(json);
        data();
        save();
    }

    public synchronized String exportJson() {
        return data().toString();
    }

    public JSONArray playlists() {
        return data().optJSONArray("playlists");
    }

    public JSONArray chats() {
        return data().optJSONArray("chats");
    }

    public JSONArray friends() {
        return data().optJSONArray("friends");
    }

    public synchronized JSONObject playlist(String id) {
        final JSONArray all = playlists();
        for (int i = 0; i < all.length(); i++) {
            final JSONObject p = all.optJSONObject(i);
            if (p != null && id.equals(p.optString("id"))) {
                return p;
            }
        }
        return null;
    }

    public synchronized String view(String kind) {
        final String fallback = "artists".equals(kind) ? "grid" : "list";
        return data().optJSONObject("views").optString(kind, fallback);
    }

    public synchronized void toggleView(String kind) {
        try {
            data().optJSONObject("views").put(kind, "grid".equals(view(kind)) ? "list" : "grid");
            save();
        } catch (JSONException ignore) {
        }
    }

    /* playlists */

    public static JSONObject trackOf(MessageObject m) {
        final JSONObject t = new JSONObject();
        try {
            t.put("i", m.getId());
            t.put("d", m.getDialogId());
            t.put("t", m.getMusicTitle());
            t.put("a", m.getMusicAuthor());
            t.put("s", (int) m.getDuration());
        } catch (JSONException ignore) {
        }
        return t;
    }

    private static JSONArray tracksOf(List<MessageObject> messages) {
        final JSONArray arr = new JSONArray();
        for (MessageObject m : messages) {
            if (m == null || arr.length() >= MAX_TRACKS) {
                continue;
            }
            arr.put(trackOf(m));
            cache(m);
        }
        return arr;
    }

    public synchronized String createPlaylist(String name, List<MessageObject> messages) {
        final String id = "pl" + System.currentTimeMillis();
        try {
            final JSONObject p = new JSONObject();
            p.put("id", id);
            p.put("name", name);
            p.put("tracks", tracksOf(messages));
            playlists().put(p);
            save();
        } catch (JSONException ignore) {
        }
        return id;
    }

    /** Keeps the automatic "Last Queue" playlist in sync with what is playing. */
    public synchronized void saveLastQueue(List<MessageObject> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        try {
            JSONObject p = playlist(LAST_QUEUE_ID);
            if (p == null) {
                p = new JSONObject();
                p.put("id", LAST_QUEUE_ID);
                p.put("name", "Last Queue");
                final JSONArray all = playlists();
                final JSONArray shifted = new JSONArray();
                shifted.put(p);
                for (int i = 0; i < all.length(); i++) {
                    shifted.put(all.get(i));
                }
                data().put("playlists", shifted);
            }
            p.put("tracks", tracksOf(messages));
            save();
        } catch (JSONException ignore) {
        }
    }

    public synchronized void renamePlaylist(String id, String name) {
        final JSONObject p = playlist(id);
        if (p != null) {
            try {
                p.put("name", name);
                save();
            } catch (JSONException ignore) {
            }
        }
    }

    public synchronized void deletePlaylist(String id) {
        final JSONArray all = playlists();
        final JSONArray out = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            final JSONObject p = all.optJSONObject(i);
            if (p != null && !id.equals(p.optString("id"))) {
                out.put(p);
            }
        }
        try {
            data().put("playlists", out);
            save();
        } catch (JSONException ignore) {
        }
    }

    public synchronized void removeTrack(String playlistId, int index) {
        final JSONObject p = playlist(playlistId);
        if (p == null) {
            return;
        }
        final JSONArray tracks = p.optJSONArray("tracks");
        final JSONArray out = new JSONArray();
        for (int i = 0; tracks != null && i < tracks.length(); i++) {
            if (i != index) {
                out.put(tracks.opt(i));
            }
        }
        try {
            p.put("tracks", out);
            save();
        } catch (JSONException ignore) {
        }
    }

    /* derived artists: name -> tracks, most tracks first, like the plugin's generated Artists section */

    public synchronized List<Map.Entry<String, ArrayList<JSONObject>>> artists() {
        final LinkedHashMap<String, ArrayList<JSONObject>> byArtist = new LinkedHashMap<>();
        final Set<String> seen = new HashSet<>();
        final JSONArray all = playlists();
        for (int i = 0; i < all.length(); i++) {
            final JSONObject p = all.optJSONObject(i);
            final JSONArray tracks = p == null ? null : p.optJSONArray("tracks");
            for (int j = 0; tracks != null && j < tracks.length(); j++) {
                final JSONObject t = tracks.optJSONObject(j);
                if (t == null) {
                    continue;
                }
                final String author = t.optString("a", "").trim();
                if (author.isEmpty() || !seen.add(t.optLong("d") + "_" + t.optInt("i"))) {
                    continue;
                }
                byArtist.computeIfAbsent(author, k -> new ArrayList<>()).add(t);
            }
        }
        final ArrayList<Map.Entry<String, ArrayList<JSONObject>>> list = new ArrayList<>(byArtist.entrySet());
        list.sort((a, b) -> b.getValue().size() - a.getValue().size());
        return list;
    }

    public static int minutes(List<JSONObject> tracks) {
        long sec = 0;
        for (JSONObject t : tracks) {
            sec += t.optInt("s", 0);
        }
        return tracks.isEmpty() ? 0 : Math.max(1, Math.round(sec / 60f));
    }

    public static ArrayList<JSONObject> trackList(JSONObject playlist) {
        final ArrayList<JSONObject> list = new ArrayList<>();
        final JSONArray arr = playlist == null ? null : playlist.optJSONArray("tracks");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            final JSONObject t = arr.optJSONObject(i);
            if (t != null) {
                list.add(t);
            }
        }
        return list;
    }

    /* message cache + resolving saved tracks to playable messages */

    private static String key(long dialogId, int id) {
        return dialogId + "_" + id;
    }

    public static void cache(MessageObject m) {
        if (m != null) {
            final MusicPlusLibrary lib = getInstance();
            synchronized (lib.messageCache) {
                lib.messageCache.put(key(m.getDialogId(), m.getId()), m);
            }
        }
    }

    public MessageObject cached(JSONObject track) {
        synchronized (messageCache) {
            return messageCache.get(key(track.optLong("d"), track.optInt("i")));
        }
    }

    public interface ResolveCallback {
        /** Messages in the same order as requested; tracks that no longer exist are skipped. */
        void onResolved(ArrayList<MessageObject> messages);
    }

    public void resolve(int account, List<JSONObject> tracks, ResolveCallback callback) {
        final HashMap<Long, ArrayList<Integer>> need = new HashMap<>();
        for (JSONObject t : tracks) {
            final long d = t.optLong("d");
            final int i = t.optInt("i");
            synchronized (messageCache) {
                if (!messageCache.containsKey(key(d, i)) && !missing.contains(key(d, i))) {
                    need.computeIfAbsent(d, k -> new ArrayList<>()).add(i);
                }
            }
        }
        if (need.isEmpty()) {
            finish(tracks, callback);
            return;
        }
        final int[] pending = {need.size()};
        for (Map.Entry<Long, ArrayList<Integer>> e : need.entrySet()) {
            fetch(account, e.getKey(), e.getValue(), () -> {
                if (--pending[0] == 0) {
                    finish(tracks, callback);
                }
            });
        }
    }

    private void finish(List<JSONObject> tracks, ResolveCallback callback) {
        final ArrayList<MessageObject> out = new ArrayList<>();
        for (JSONObject t : tracks) {
            final MessageObject m = cached(t);
            if (m != null) {
                out.add(m);
            }
        }
        AndroidUtilities.runOnUIThread(() -> callback.onResolved(out));
    }

    private void fetch(int account, long dialogId, ArrayList<Integer> ids, Runnable done) {
        final TLObject req;
        if (dialogId < 0 && ChatObject.isChannel(-dialogId, account)) {
            final TLRPC.TL_channels_getMessages r = new TLRPC.TL_channels_getMessages();
            r.channel = MessagesController.getInstance(account).getInputChannel(-dialogId);
            r.id = ids;
            req = r;
        } else {
            final TLRPC.TL_messages_getMessages r = new TLRPC.TL_messages_getMessages();
            r.id = ids;
            req = r;
        }
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.messages_Messages) {
                final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                final HashMap<Long, TLRPC.User> users = new HashMap<>();
                final HashMap<Long, TLRPC.Chat> chats = new HashMap<>();
                for (TLRPC.User u : res.users) {
                    users.put(u.id, u);
                }
                for (TLRPC.Chat c : res.chats) {
                    chats.put(c.id, c);
                }
                MessagesController.getInstance(account).putUsers(res.users, false);
                MessagesController.getInstance(account).putChats(res.chats, false);
                final HashSet<Integer> found = new HashSet<>();
                for (TLRPC.Message msg : res.messages) {
                    msg.dialog_id = dialogId;
                    final MessageObject mo = new MessageObject(account, msg, users, chats, true, true);
                    if (mo.isMusic()) {
                        cache(mo);
                        found.add(msg.id);
                    }
                }
                synchronized (messageCache) {
                    for (int id : ids) {
                        if (!found.contains(id)) {
                            missing.add(key(dialogId, id));
                        }
                    }
                }
            }
            done.run();
        });
    }
}
