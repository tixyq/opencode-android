package com.opencode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Chat alerts as system notifications. Polls the bundled server:
 * pending permission requests, pending question forms, and fresh
 * assistant replies. Tap always just opens the app.
 */
public class Notifier {
    private static final String TAG = "OpenCode";
    private static final String CHANNEL_ID = "oc_chat";
    private static final String CHANNEL_ID_OLD = "opencode_chat";
    private static final int ID_PERMISSION = 101;
    private static final int ID_QUESTION = 102;
    private static final int ID_REPLY = 103;
    // PendingIntent.FLAG_IMMUTABLE (API 31 literal: our compile SDK is 28).
    private static final int FLAG_IMMUTABLE = 0x02000000;

    private static final long INTERVAL_MS = 8000;

    private final Context context;
    private volatile boolean running;
    private Thread thread;

    private final Set<String> seenPerm = new HashSet<String>();
    private final Set<String> seenForm = new HashSet<String>();
    private final Map<String, String> lastMsg = new HashMap<String, String>();
    private final Set<String> notifiedMsg = new java.util.LinkedHashSet<String>();
    /** Notification titles pulled from the bundled web UI locale files. */
    private final Map<String, String> uiText = new HashMap<String, String>();
    private boolean seeded;
    private boolean permShown;
    private boolean formShown;

    public Notifier(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void start() {
        if (thread != null) {
            return;
        }
        running = true;
        ensureChannel();
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "oc-notify");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        thread = null;
    }

    private boolean russian() {
        try {
            return "ru".equals(Locale.getDefault().getLanguage());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Notification titles come from the bundled web UI itself
     * (www/_assets/&lt;lang&gt;-*.js), not from hardcoded copies: the same
     * strings the interface shows, in the phone language.
     */
    private String uiTitle(String key, String fallback) {
        if (uiText.isEmpty()) {
            loadUiText();
        }
        String value = uiText.get(key);
        return value == null || value.length() == 0 ? fallback : value;
    }

    private synchronized void loadUiText() {
        if (!uiText.isEmpty()) {
            return;
        }
        String lang = "";
        try {
            lang = Locale.getDefault().getLanguage();
        } catch (Exception ignored) {
        }
        String[] tries = {lang, "en"};
        for (int t = 0; t < tries.length; t++) {
            if (tries[t] == null || tries[t].length() == 0) {
                continue;
            }
            File dir = new File(new File(context.getFilesDir(), "www"), "_assets");
            File[] files = dir.listFiles();
            if (files == null) {
                continue;
            }
            for (int i = 0; i < files.length; i++) {
                String name = files[i].getName();
                if (!name.startsWith(tries[t] + "-") || !name.endsWith(".js")) {
                    continue;
                }
                if (parseUiText(files[i])) {
                    Log.i(TAG, "notify strings from " + name);
                    return;
                }
            }
        }
    }

    /** Pulls "notification.*.title" values out of a web UI locale bundle. */
    private boolean parseUiText(File file) {
        String[] keys = {
                "notification.permission.title",
                "notification.question.title",
                "notification.session.responseReady.title",
        };
        try {
            FileInputStream fis = new FileInputStream(file);
            try {
                long len = file.length();
                if (len <= 0 || len > 1048576) {
                    return false;
                }
                byte[] buffer = new byte[(int) len];
                int off = 0;
                int n;
                while (off < buffer.length
                        && (n = fis.read(buffer, off, buffer.length - off)) >= 0) {
                    off += n;
                }
                String js = new String(buffer, 0, off, "UTF-8");
                boolean any = false;
                for (int k = 0; k < keys.length; k++) {
                    int at = js.indexOf('"' + keys[k] + "\":");
                    if (at < 0) {
                        continue;
                    }
                    int open = js.indexOf('`', at);
                    if (open < 0) {
                        continue;
                    }
                    int close = js.indexOf('`', open + 1);
                    if (close < 0) {
                        continue;
                    }
                    String value = js.substring(open + 1, close).trim();
                    if (value.length() > 0 && value.length() < 200) {
                        uiText.put(keys[k], value);
                        any = true;
                    }
                }
                return any;
            } finally {
                fis.close();
            }
        } catch (IOException e) {
            return false;
        }
    }

    private String titlePermission() {
        return uiTitle("notification.permission.title",
                russian() ? "Требуется разрешение" : "Permission needed");
    }

    private String titleQuestion() {
        return uiTitle("notification.question.title",
                russian() ? "Вопрос" : "Question");
    }

    private String titleReply() {
        return uiTitle("notification.session.responseReady.title",
                russian() ? "Ответ готов" : "Reply ready");
    }

    private void ensureChannel() {
        try {
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    russian() ? "Чат" : "Chat",
                    NotificationManager.IMPORTANCE_HIGH);
            manager.createNotificationChannel(channel);
            try {
                manager.deleteNotificationChannel(CHANNEL_ID_OLD);
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            Log.i(TAG, "notify channel failed: " + e);
        }
    }

    private void loop() {
        boolean wasForeground = false;
        while (running) {
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (!running) {
                return;
            }
            boolean fg = MainActivity.foreground;
            if (wasForeground && !fg) {
                // Just left the app: still-pending prompts must alert anew,
                // but replies stay marked as read.
                permShown = false;
                formShown = false;
            }
            wasForeground = fg;
            try {
                poll();
            } catch (Exception e) {
                Log.i(TAG, "notify poll failed: " + e);
            }
        }
    }

    private String password() {
        File file = new File(new File(
                context.getFilesDir(), "home/.config/opencode"), "service.json");
        if (!file.isFile()) {
            return null;
        }
        try {
            FileInputStream fis = new FileInputStream(file);
            try {
                byte[] buffer = new byte[(int) Math.min(file.length(), 1024)];
                int n = fis.read(buffer);
                if (n <= 0) {
                    return null;
                }
                String pw = new JSONObject(new String(buffer, 0, n, "UTF-8"))
                        .optString("password", "");
                return pw.length() == 0 ? null : pw;
            } finally {
                fis.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String apiGet(String path, String password) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://127.0.0.1:"
                    + ServerService.API_PORT + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(8000);
            connection.setRequestMethod("GET");
            String raw = "opencode:" + password;
            String auth = "Basic " + android.util.Base64.encodeToString(
                    raw.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            connection.setRequestProperty("Authorization", auth);
            if (connection.getResponseCode() != 200) {
                return null;
            }
            InputStream in = connection.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > 65536) {
                        break;
                    }
                }
                return out.toString("UTF-8");
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static JSONArray dataArray(String body) {
        try {
            JSONObject root = new JSONObject(body);
            Object data = root.opt("data");
            if (data instanceof JSONArray) {
                return (JSONArray) data;
            }
        } catch (Exception ignored) {
        }
        return new JSONArray();
    }

    private void poll() {
        String pw = password();
        if (pw == null) {
            return;
        }
        pollPermissions(pw);
        pollForms(pw);
        pollReplies(pw);
    }

    /** Pending action approvals ("ask to perform an action"). */
    private void pollPermissions(String pw) {
        String body = apiGet("/api/permission/request", pw);
        if (body == null) {
            return;
        }
        JSONArray items = dataArray(body);
        List<String> ids = new ArrayList<String>();
        List<String> heads = new ArrayList<String>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String id = item.optString("id", "");
            if (id.length() == 0) {
                continue;
            }
            ids.add(id);
            heads.add(permissionHeading(item));
        }
        boolean fresh = false;
        for (int i = 0; i < ids.size(); i++) {
            if (!seenPerm.contains(ids.get(i))) {
                seenPerm.add(ids.get(i));
                fresh = true;
            }
        }
        // Forget answered ones so a repeat asks again.
        seenPerm.retainAll(ids);
        if (ids.isEmpty()) {
            if (permShown) {
                cancel(ID_PERMISSION);
                permShown = false;
            }
            return;
        }
        if (fresh || !permShown) {
            String text = heads.get(0);
            if (ids.size() > 1) {
                text = text + " (1/" + ids.size() + ")";
            }
            show(ID_PERMISSION, titlePermission(), text);
            permShown = true;
        }
    }

    private static String permissionHeading(JSONObject item) {
        String title = item.optString("title", "").trim();
        if (title.length() > 0) {
            return shorten(title);
        }
        String permission = item.optString("permission", "").trim();
        JSONArray patterns = item.optJSONArray("patterns");
        String first = "";
        if (patterns != null && patterns.length() > 0) {
            first = patterns.optString(0, "").trim();
        }
        String head = (permission + (first.length() > 0 ? ": " + first : "")).trim();
        return head.length() > 0 ? shorten(head) : "?";
    }

    /** Pending question forms ("n of n questions"). */
    private void pollForms(String pw) {
        String body = apiGet("/api/form", pw);
        if (body == null) {
            return;
        }
        JSONArray items = dataArray(body);
        List<String> ids = new ArrayList<String>();
        List<String> heads = new ArrayList<String>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String id = item.optString("id", "");
            if (id.length() == 0) {
                continue;
            }
            ids.add(id);
            heads.add(formHeading(item));
        }
        boolean fresh = false;
        for (int i = 0; i < ids.size(); i++) {
            if (!seenForm.contains(ids.get(i))) {
                seenForm.add(ids.get(i));
                fresh = true;
            }
        }
        seenForm.retainAll(ids);
        if (ids.isEmpty()) {
            if (formShown) {
                cancel(ID_QUESTION);
                formShown = false;
            }
            return;
        }
        if (fresh || !formShown) {
            String text = heads.get(0);
            if (ids.size() > 1) {
                text = text + " (1/" + ids.size() + ")";
            }
            show(ID_QUESTION, titleQuestion(), text);
            formShown = true;
        }
    }

    private static String formHeading(JSONObject item) {
        String title = item.optString("title", "").trim();
        if (title.length() > 0) {
            return shorten(title);
        }
        JSONArray fields = item.optJSONArray("fields");
        if (fields != null && fields.length() > 0) {
            JSONObject first = fields.optJSONObject(0);
            if (first != null) {
                String field = first.optString("title", "").trim();
                if (field.length() > 0) {
                    return shorten(field);
                }
            }
        }
        return "?";
    }

    /** Fresh assistant replies: text is the reply's first line. */
    private void pollReplies(String pw) {
        String sessions = apiGet("/api/session", pw);
        if (sessions == null) {
            return;
        }
        JSONArray list = dataArray(sessions);
        for (int i = 0; i < list.length(); i++) {
            JSONObject session = list.optJSONObject(i);
            if (session == null) {
                continue;
            }
            String sid = session.optString("id", "");
            if (sid.length() == 0) {
                continue;
            }
            String messages = apiGet("/api/session/" + sid + "/message?limit=3", pw);
            if (messages == null) {
                continue;
            }
            JSONArray mails = dataArray(messages);
            // Newest first. "Seen" and "notified" are separate: a reply
            // watched streaming (or finished while the chat is open) is
            // marked delivered and never alerts after exit; an unfinished
            // one only marks seen until it completes.
            for (int j = 0; j < mails.length(); j++) {
                JSONObject msg = mails.optJSONObject(j);
                if (msg == null || !"assistant".equals(msg.optString("type", ""))) {
                    continue;
                }
                String mid = msg.optString("id", "");
                if (mid.length() == 0) {
                    continue;
                }
                if (!lastMsg.containsKey(sid)) {
                    // First sight: seed silently, never spam history.
                    lastMsg.put(sid, mid);
                    markNotified(mid);
                    break;
                }
                lastMsg.put(sid, mid);
                if (notifiedMsg.contains(mid)) {
                    break;
                }
                if (!msg.has("finish") && !msg.has("completed")) {
                    break;
                }
                // Delivered even when the open chat suppresses the popup.
                markNotified(mid);
                String first = firstLine(msg);
                if (first.length() > 0) {
                    show(ID_REPLY, titleReply(), first);
                }
                break;
            }
            if (!lastMsg.containsKey(sid) && mails.length() > 0) {
                JSONObject newest = mails.optJSONObject(0);
                if (newest != null && newest.optString("id", "").length() > 0) {
                    lastMsg.put(sid, newest.optString("id", ""));
                }
            }
        }
        if (!seeded) {
            seeded = true;
        }
    }

    /** Remembers a delivered reply id, trimming ancient ones. */
    private void markNotified(String mid) {
        notifiedMsg.add(mid);
        while (notifiedMsg.size() > 300) {
            java.util.Iterator<String> it = notifiedMsg.iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            } else {
                break;
            }
        }
    }

    private static String firstLine(JSONObject msg) {        JSONArray content = msg.optJSONArray("content");
        if (content == null) {
            return "";
        }
        for (int i = 0; i < content.length(); i++) {
            JSONObject part = content.optJSONObject(i);
            if (part == null || !"text".equals(part.optString("type", ""))) {
                continue;
            }
            String text = part.optString("text", "").trim();
            if (text.length() == 0) {
                continue;
            }
            int cut = text.indexOf('\n');
            if (cut >= 0) {
                text = text.substring(0, cut).trim();
            }
            return shorten(text);
        }
        return "";
    }

    private static String shorten(String value) {
        if (value.length() > 120) {
            return value.substring(0, 120).trim() + "…";
        }
        return value;
    }

    private void show(int id, String title, String text) {
        // Open chat shows everything itself: record state, stay silent.
        if (MainActivity.foreground) {
            return;
        }
        try {
            Intent open = new Intent(context, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent tap = PendingIntent.getActivity(context, id, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE);
            Notification notification;
            if (Build.VERSION.SDK_INT >= 26) {
                notification = new Notification.Builder(context, CHANNEL_ID)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setContentIntent(tap)
                        .setAutoCancel(true)
                        .build();
            } else {
                notification = new Notification.Builder(context)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setContentIntent(tap)
                        .setAutoCancel(true)
                        .build();
            }
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(id, notification);
            }
            Log.i(TAG, "notify " + id + ": " + title);
        } catch (Exception e) {
            Log.i(TAG, "notify failed: " + e);
        }
    }

    private void cancel(int id) {
        try {
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.cancel(id);
            }
        } catch (Exception ignored) {
        }
    }
}
