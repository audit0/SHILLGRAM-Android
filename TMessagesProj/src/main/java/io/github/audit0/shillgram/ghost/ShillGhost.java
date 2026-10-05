/*
 * SHILLGRAM: ghost mode («Режим призрака»), the Android side of the desktop
 * (AyuGram) ghost mode. App-wide for all accounts, stored in its own
 * SharedPreferences. The master switch is off by default; each option is on
 * by default and only works while the master switch is on.
 *
 *  - noRead:     chats are read on this device only; the server is not told.
 *  - noStories:  viewing stories does not mark them seen on the server.
 *  - noOnline:   the app never sends "online"; it sends "offline" once.
 *  - noTyping:   no "typing…", "uploading…" or emoji-interaction actions.
 *  - readOnSend: with noRead on, sending a message into a chat sends that
 *                chat's read receipt (an answer shows you read it anyway).
 *
 * The hooks live in MessagesController, LocationController, StoriesController,
 * EmojiAnimationsOverlay and SendMessagesHelper, marked "SHILLGRAM: ghost".
 */
package io.github.audit0.shillgram.ghost;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

public final class ShillGhost {
    private static final String PREFS = "shillgram_ghost";

    public static final String ENABLED = "enabled";
    public static final String NO_READ = "no_read";
    public static final String NO_STORIES = "no_stories";
    public static final String NO_ONLINE = "no_online";
    public static final String NO_TYPING = "no_typing";
    public static final String READ_ON_SEND = "read_on_send";

    // Read on hot paths (the 1 s status timer, every typing event), so the
    // values are cached here and the preferences are only touched on change.
    private static volatile boolean loaded;
    private static volatile boolean enabled;
    private static volatile boolean noRead = true;
    private static volatile boolean noStories = true;
    private static volatile boolean noOnline = true;
    private static volatile boolean noTyping = true;
    private static volatile boolean readOnSend = true;

    private ShillGhost() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void load() {
        if (loaded) {
            return;
        }
        synchronized (ShillGhost.class) {
            if (loaded) {
                return;
            }
            try {
                final SharedPreferences p = prefs();
                enabled = p.getBoolean(ENABLED, false);
                noRead = p.getBoolean(NO_READ, true);
                noStories = p.getBoolean(NO_STORIES, true);
                noOnline = p.getBoolean(NO_ONLINE, true);
                noTyping = p.getBoolean(NO_TYPING, true);
                readOnSend = p.getBoolean(READ_ON_SEND, true);
            } catch (Throwable ignore) {
                // No context yet: ghost stays off.
                return;
            }
            loaded = true;
        }
    }

    // ---- Effective checks (master && option).

    public static boolean isEnabled() {
        load();
        return enabled;
    }

    public static boolean noRead() {
        load();
        return enabled && noRead;
    }

    public static boolean noStories() {
        load();
        return enabled && noStories;
    }

    public static boolean noOnline() {
        load();
        return enabled && noOnline;
    }

    public static boolean noTyping() {
        load();
        return enabled && noTyping;
    }

    public static boolean readOnSend() {
        load();
        return enabled && noRead && readOnSend;
    }

    // ---- Raw option values for the settings dialog.

    public static boolean option(String key) {
        load();
        switch (key) {
            case ENABLED:
                return enabled;
            case NO_READ:
                return noRead;
            case NO_STORIES:
                return noStories;
            case NO_ONLINE:
                return noOnline;
            case NO_TYPING:
                return noTyping;
            case READ_ON_SEND:
                return readOnSend;
            default:
                return false;
        }
    }

    public static void setOption(String key, boolean value) {
        load();
        synchronized (ShillGhost.class) {
            switch (key) {
                case ENABLED:
                    enabled = value;
                    break;
                case NO_READ:
                    noRead = value;
                    break;
                case NO_STORIES:
                    noStories = value;
                    break;
                case NO_ONLINE:
                    noOnline = value;
                    break;
                case NO_TYPING:
                    noTyping = value;
                    break;
                case READ_ON_SEND:
                    readOnSend = value;
                    break;
                default:
                    return;
            }
            prefs().edit().putBoolean(key, value).apply();
        }
        applyStatusNow();
    }

    public static void setEnabled(boolean value) {
        setOption(ENABLED, value);
    }

    // Runs the status timer of every signed-in account right away, so
    // "offline" (ghost on) or "online" (ghost off) goes out without waiting
    // for the next tick.
    private static void applyStatusNow() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            final int account = a;
            if (!UserConfig.getInstance(account).isClientActivated()) {
                continue;
            }
            Utilities.stageQueue.postRunnable(() -> MessagesController.getInstance(account).updateTimerProc());
        }
    }
}
