/*
 * SHILLGRAM: SHILLVPN built into the app.
 *
 * The user's SHILLVPN subscription (the link from the bot or the site
 * cabinet) is fetched, its VLESS / Hysteria 2 entries become an Xray config,
 * and the bundled Xray core serves a local SOCKS5 port with a random login.
 * Telegram's own connection (every account) goes through that port, so the
 * app works before the Telegram login too. An in-app proxy only: no Android
 * VpnService, other apps are not touched.
 *
 * The link, the fetched entries and the cabinet key are secrets: they live
 * encrypted with an Android Keystore key (SecretStore), the config goes to
 * Xray through stdin, and none of them is ever logged.
 *
 * A port of the desktop client (shillgramm/shill_vpn.cpp). Everything here
 * runs on the main thread; network and process work goes to a background
 * executor and comes back with AndroidUtilities.runOnUIThread.
 */
package io.github.audit0.shillgram.vpn;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.proxy.ProxySettings;
import org.telegram.tgnet.ConnectionsManager;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class ShillVpn {

    public enum State {
        NONE, // No subscription yet.
        OFF,
        LOADING, // Fetching the subscription.
        STARTING, // The core is starting.
        ON,
        ERROR,
    }

    public interface Callback<T> {
        void run(T value);
    }

    public static final class Plan {
        public final String title;
        public final int days;
        public final int priceRub;

        Plan(String title, int days, int priceRub) {
            this.title = title;
            this.days = days;
            this.priceRub = priceRub;
        }
    }

    public static final class ShopInfo {
        public final List<Plan> plans = new ArrayList<>();
        public int referralDays;
        public int devices;
    }

    // Where the subscription is served when the pasted link does not say it.
    static final String SITE = XrayConfig.SITE;
    static final String BOT = "SHILLVPN_bot";
    static final String TERMS_URL = "https://shillvpn.site/legal/terms";
    private static final String USER_PREFIX = "shill";
    private static final int PORT_ATTEMPTS = 50; // x 150 ms: the core has 7.5 s to listen.
    private static final int MAX_RESTARTS = 5;
    private static final long REFRESH_INTERVAL = 6 * 3600 * 1000L;
    private static final int FETCH_TIMEOUT = 15 * 1000;
    private static final int TRIAL_POLLS = 30; // x 2 s: the site's worker issues the trial.
    private static final long SHOP_INFO_TTL = 6 * 3600 * 1000L;
    private static final long REMIND_BEFORE = 24 * 3600;
    private static final String CORE_NAME = "libxray.so";
    private static final String FLAGS = "shillgram_vpn";

    private static volatile ShillVpn instance;

    public static ShillVpn getInstance() {
        ShillVpn local = instance;
        if (local == null) {
            synchronized (ShillVpn.class) {
                local = instance;
                if (local == null) {
                    instance = local = new ShillVpn(ApplicationLoader.applicationContext);
                }
            }
        }
        return local;
    }

    private final Context context;
    private final SecretStore secrets;
    private final SharedPreferences flags;
    private final ExecutorService io = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "shillvpn");
        thread.setDaemon(true);
        return thread;
    });
    private final SecureRandom random = new SecureRandom();
    private final OkHttpClient direct;
    private final List<Runnable> listeners = new ArrayList<>();

    private String link = "";
    private String token = "";
    private byte[] body = null; // Last fetched subscription body, as served.
    private List<XrayConfig.Entry> entries = Collections.emptyList();
    private long expiresAt;
    private boolean enabled;

    private String cabinetKey = ""; // Site cabinet of the app's own trial.
    private long remindedAt;
    private ShopInfo shopInfo;
    private long shopInfoAt;
    private boolean trialBusy;

    private Process core;
    private int coreGeneration;
    private int port;
    private String user = "";
    private String password = "";
    private int restarts;
    private boolean started;

    private State state = State.NONE;
    private String error = "";

    private final Runnable restartRunnable = this::launch;
    private final Runnable refreshRunnable = this::refresh;
    private final Runnable expiryRunnable = this::refresh;

    private ShillVpn(Context context) {
        this.context = context.getApplicationContext();
        secrets = new SecretStore(this.context);
        flags = this.context.getSharedPreferences(FLAGS, Context.MODE_PRIVATE);
        direct = new OkHttpClient.Builder()
                // Straight to the site: Telegram's proxy may be our own port.
                .proxy(Proxy.NO_PROXY)
                .connectTimeout(FETCH_TIMEOUT, TimeUnit.MILLISECONDS)
                .readTimeout(FETCH_TIMEOUT, TimeUnit.MILLISECONDS)
                .writeTimeout(FETCH_TIMEOUT, TimeUnit.MILLISECONDS)
                .callTimeout(FETCH_TIMEOUT, TimeUnit.MILLISECONDS)
                .build();
        loadFlags();
        loadSecrets();
        state = token.isEmpty() ? State.NONE : State.OFF;
    }

    // ---- Texts: the desktop Tr(en, ru) pairs.

    public static boolean isRussian() {
        try {
            final Locale locale = LocaleController.getInstance().getCurrentLocale();
            return locale != null && locale.getLanguage().startsWith("ru");
        } catch (Throwable e) {
            return Locale.getDefault().getLanguage().startsWith("ru");
        }
    }

    public static String tr(String en, String ru) {
        return isRussian() ? ru : en;
    }

    public static String daysText(int days) {
        if (!isRussian()) {
            return days + (days == 1 ? " day" : " days");
        }
        final int mod10 = days % 10;
        final int mod100 = days % 100;
        final String word = (mod10 == 1 && mod100 != 11)
                ? "день"
                : (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14))
                ? "дня"
                : "дней";
        return days + " " + word;
    }

    public static String hoursText(int hours) {
        if (!isRussian()) {
            return hours + (hours == 1 ? " hour" : " hours");
        }
        final int mod10 = hours % 10;
        final int mod100 = hours % 100;
        final String word = (mod10 == 1 && mod100 != 11)
                ? "час"
                : (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14))
                ? "часа"
                : "часов";
        return hours + " " + word;
    }

    /**
     * Every page of the site opened from the app carries where it came
     * from, so the site's statistics show what the app sells. The marks go
     * after '#' with the page's own parameters (k=): the site answers 404
     * to its pages with a query string, and the page reads the fragment.
     */
    static String siteUrl(String path, String campaign, String fragment) {
        String result = SITE
                + path
                + "#utm_source=shillgram&utm_medium=app&utm_campaign="
                + (campaign == null || campaign.isEmpty() ? "app" : campaign);
        if (fragment != null && !fragment.isEmpty()) {
            result += '&' + fragment;
        }
        return result;
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    // ---- Listeners (main thread). Each screen removes its own on close.

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void setState(State state, String error) {
        this.state = state;
        this.error = error == null ? "" : error;
        for (Runnable listener : new ArrayList<>(listeners)) {
            listener.run();
        }
    }

    private void setState(State state) {
        setState(state, "");
    }

    // ---- Lifecycle.

    /**
     * Application start, after Telegram read its settings: brings the core
     * up again if it was on, drops a stale local proxy otherwise.
     */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        if (enabled && !token.isEmpty()) {
            if (!entries.isEmpty()) {
                launch(); // From the kept list: works when the site is slow.
            }
            refresh();
        } else if (isOurProxy(currentProxySettings())) {
            useProxy(false); // The core is not coming: do not wait on its port.
        }
        scheduleExpiryCheck();
        AndroidUtilities.runOnUIThread(this::checkReminder, 10000);
    }

    public boolean hasSubscription() {
        return !token.isEmpty();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public State getState() {
        return state;
    }

    public String getError() {
        return error;
    }

    /** Unix time the access ends, 0 when unknown. */
    public long getExpiresAt() {
        return expiresAt;
    }

    public String statusText() {
        final long now = now();
        final String left = expiresAt > now
                ? daysText((int) ((expiresAt - now + 86399) / 86400))
                : "";
        switch (state) {
            case NONE:
                return tr("Not connected", "Не подключён");
            case OFF:
                if (accessEnded()) {
                    return tr(
                            "Access ended · Telegram connects directly",
                            "Доступ закончился · Telegram подключается напрямую");
                }
                return tr("Off", "Выключен") + (left.isEmpty() ? "" : " · " + left);
            case LOADING:
                return tr("Loading the subscription…", "Загружаю подписку…");
            case STARTING:
                return tr("Connecting…", "Подключаю…");
            case ON:
                if (expiresAt != 0 && expiresAt <= now) {
                    return tr("Access ended", "Доступ закончился");
                }
                return tr("On", "Включён") + (left.isEmpty() ? "" : " · " + left);
            case ERROR:
                return error;
        }
        return "";
    }

    /** Short line for menus: "SHILLVPN · 3 дня". */
    public String menuText() {
        final long now = now();
        if (state == State.NONE) {
            return "SHILLVPN";
        } else if (expiresAt > now) {
            return "SHILLVPN · " + daysText((int) ((expiresAt - now + 86399) / 86400));
        } else if (expiresAt != 0) {
            return "SHILLVPN · " + tr("renew", "продлить");
        }
        return "SHILLVPN";
    }

    public boolean hasCabinet() {
        return !cabinetKey.isEmpty();
    }

    public String cabinetUrl(String campaign) {
        // The key goes in the fragment: it stays in the browser.
        return siteUrl("/app/buy/", campaign, "k=" + cabinetKey);
    }

    /** Page on the site to connect another device (INCY, Happ...). */
    public String connectPageUrl() {
        return token.isEmpty() ? SITE + "/app/buy/" : SITE + "/c/" + token;
    }

    /**
     * The paid time is over (the server's expire already counts the grace
     * days): the tunnel is stopped and Telegram connects directly.
     */
    public boolean accessEnded() {
        return expiresAt != 0 && expiresAt <= now();
    }

    private void scheduleExpiryCheck() {
        AndroidUtilities.cancelRunOnUIThread(expiryRunnable);
        final long now = now();
        if (expiresAt == 0 || expiresAt <= now) {
            return;
        }
        // A day before the end (the reminder) and at the end itself.
        final long left = expiresAt - now;
        final long next = left > REMIND_BEFORE ? left - REMIND_BEFORE : left;
        AndroidUtilities.runOnUIThread(expiryRunnable, (Math.min(next, 7 * 86400L) + 5) * 1000);
    }

    /**
     * Once a day before the end and every 12 hours after it: a box with the
     * live prices and «Продлить».
     */
    public void checkReminder() {
        if (token.isEmpty() || expiresAt == 0) {
            return;
        }
        final long now = now();
        final long left = expiresAt - now;
        final boolean ended = left <= 0;
        if (!ended && left > REMIND_BEFORE) {
            return;
        }
        final boolean due = ended
                ? (remindedAt == 0 || remindedAt < expiresAt || now - remindedAt >= 12 * 3600)
                : (remindedAt == 0 || now - remindedAt >= 20 * 3600);
        if (!due || !ShillVpnSheet.canShow()) {
            return;
        }
        remindedAt = now;
        saveFlags();
        loadShopInfo(info -> ShillVpnSheet.showRenew(ended, left, info));
    }

    // ---- shillvpn.site.

    /** shop_info of the site, kept for 6 hours; done(null) when unknown. */
    public void loadShopInfo(Callback<ShopInfo> done) {
        if (shopInfo != null && System.currentTimeMillis() - shopInfoAt < SHOP_INFO_TTL) {
            done.run(shopInfo);
            return;
        }
        api(Json.object("action", "shop_info"), reply -> {
            if (reply == null || !Json.bool(reply, "ok")) {
                done.run(shopInfo);
                return;
            }
            final ShopInfo info = new ShopInfo();
            for (Object value : Json.list(reply, "plans")) {
                if (!(value instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked") final Map<String, Object> plan = (Map<String, Object>) value;
                final int days = (int) Json.number(plan, "days");
                final int price = (int) Json.number(plan, "price_rub");
                if (days > 0 && price > 0) {
                    info.plans.add(new Plan(Json.string(plan, "title"), days, price));
                }
            }
            info.referralDays = (int) Json.number(reply, "referral_days");
            info.devices = (int) Json.number(reply, "devices");
            if (!info.plans.isEmpty()) {
                shopInfo = info;
                shopInfoAt = System.currentTimeMillis();
            }
            done.run(shopInfo);
        }, false);
    }

    /** POST to shillvpn.site/app/api; done(null) when out of reach. */
    private void api(Map<String, Object> request, Callback<Map<String, Object>> done, boolean viaCore) {
        final OkHttpClient client = viaCore ? tunnelClient() : direct;
        final String payload = Json.write(request);
        io.execute(() -> {
            int code = 0;
            Map<String, Object> object = null;
            try {
                final Request http = new Request.Builder()
                        .url(SITE + "/app/api")
                        .header("User-Agent", "SHILLGRAM/1.0")
                        .post(RequestBody.create(payload, MediaType.get("application/json")))
                        .build();
                try (Response response = client.newCall(http).execute()) {
                    code = response.code();
                    object = Json.parseObject(response.body().string());
                }
            } catch (Throwable ignored) {
                // Never logged: the request may carry the cabinet key.
            }
            final int finalCode = code;
            final Map<String, Object> finalObject = object;
            AndroidUtilities.runOnUIThread(() -> {
                if (finalCode == 429 || finalCode == 503) {
                    // Plain text from the front server: wait and ask again.
                    done.run(Json.object("ok", false, "error", "busy"));
                } else if (finalObject != null) {
                    done.run(finalObject);
                } else if (!viaCore && core != null && port != 0 && state == State.ON) {
                    // The site is out of reach directly: through our own tunnel.
                    api(request, done, true);
                } else {
                    done.run(null);
                }
            });
        });
    }

    /**
     * GET a public page (the GitHub release feed): directly, then through
     * our tunnel when it is on. done(null) when out of reach.
     */
    public void getText(String url, String accept, Callback<String> done) {
        getTextVia(url, accept, false, done);
    }

    private void getTextVia(String url, String accept, boolean viaCore, Callback<String> done) {
        final OkHttpClient client = viaCore ? tunnelClient() : direct;
        io.execute(() -> {
            String text = null;
            try {
                final Request http = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "SHILLGRAM/" + BuildConfig.SHILLGRAM_VERSION)
                        .header("Accept", accept)
                        .get()
                        .build();
                try (Response response = client.newCall(http).execute()) {
                    if (response.code() == 200) {
                        text = response.body().string();
                    }
                }
            } catch (Throwable ignored) {
                text = null;
            }
            final String result = text;
            AndroidUtilities.runOnUIThread(() -> {
                if (result != null) {
                    done.run(result);
                } else if (!viaCore && core != null && port != 0 && state == State.ON) {
                    getTextVia(url, accept, true, done);
                } else {
                    done.run(null);
                }
            });
        });
    }

    private OkHttpClient tunnelClient() {
        return direct.newBuilder()
                .connectionPool(new ConnectionPool())
                .socketFactory(new Socks5SocketFactory(port, user, password))
                .dns(Socks5SocketFactory::singleUnresolved)
                .build();
    }

    // ---- The free trial.

    private static String trialError(String code) {
        if ("trial_used".equals(code)) {
            return tr(
                    "This device has already had its free days. Buy SHILLVPN on "
                            + "the site or paste your subscription link.",
                    "На этом устройстве бесплатные дни уже были. Купите SHILLVPN "
                            + "на сайте или вставьте ссылку подписки.");
        } else if ("busy".equals(code) || "pow".equals(code)) {
            return tr(
                    "Too many requests right now. Try again in a few minutes.",
                    "Сейчас много запросов. Попробуйте через несколько минут.");
        } else if ("closed".equals(code) || "unavailable".equals(code)) {
            return tr(
                    "Free days are not given out right now. Buy SHILLVPN on the "
                            + "site or paste your subscription link.",
                    "Бесплатные дни сейчас не выдаются. Купите SHILLVPN на сайте "
                            + "или вставьте ссылку подписки.");
        }
        return tr(
                "Free days in the app are coming soon. For now take 3 days in "
                        + "@SHILLVPN_bot and paste the subscription link here.",
                "Бесплатные дни в приложении скоро появятся. Пока возьмите "
                        + "3 дня в @SHILLVPN_bot и вставьте ссылку подписки сюда.");
    }

    /**
     * One trial per device: the site keeps only a hash of this value, and
     * the value itself is a keyed hash of the device id, never the id.
     */
    private String deviceId() {
        String id = null;
        try {
            id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {
        }
        if (id == null || id.isEmpty() || "9774d56d682e549c".equals(id)) {
            id = secrets.readString("device");
            if (id == null || id.isEmpty()) {
                id = Long.toHexString(random.nextLong()) + Long.toHexString(random.nextLong());
                secrets.write("device", id);
            }
        }
        return TrialWork.deviceHash(id.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * First launch: three free days for this device from shillvpn.site (one
     * per device, the site decides). done("") once the tunnel runs on the
     * new subscription.
     */
    public void startTrial(Callback<String> done) {
        if (trialBusy) {
            return;
        }
        trialBusy = true;
        final Callback<String> finish = error -> {
            trialBusy = false;
            done.run(error);
        };
        if (!cabinetKey.isEmpty()) {
            // Taken before, the app closed while the site prepared it.
            waitTrialReady(0, finish);
            return;
        }
        io.execute(() -> {
            final String device = deviceId();
            final long hour = System.currentTimeMillis() / 1000 / 3600;
            final String work = TrialWork.solve(TrialWork.prefix(device, hour));
            AndroidUtilities.runOnUIThread(() -> requestTrial(device, hour, work, finish));
        });
    }

    private void requestTrial(String device, long hour, String work, Callback<String> finish) {
        api(Json.object(
                "action", "shop_app_trial",
                "device", device,
                "accept_terms", true,
                "lang", isRussian() ? "ru" : "en",
                "pow_hour", hour,
                "pow", work), reply -> {
            if (reply == null) {
                finish.run(tr(
                        "shillvpn.site does not open from this network. Buy "
                                + "SHILLVPN from another network or from your phone and "
                                + "paste the subscription link here.",
                        "shillvpn.site не открывается из этой сети. Купите "
                                + "SHILLVPN из другой сети или с телефона и вставьте "
                                + "ссылку подписки сюда."));
                return;
            }
            final String key = Json.string(reply, "key");
            if (!Json.bool(reply, "ok") || key.isEmpty()) {
                finish.run(trialError(Json.string(reply, "error")));
                return;
            }
            cabinetKey = key;
            secrets.write("cabinet", key);
            waitTrialReady(0, finish);
        }, false);
    }

    private void waitTrialReady(int attempt, Callback<String> done) {
        api(Json.object(
                "action", "shop_app_status",
                "key", cabinetKey), reply -> {
            if (reply != null && Json.bool(reply, "ok")) {
                final String status = Json.string(Json.child(reply, "subscription"), "status");
                if ("expired".equals(status)) {
                    done.run(tr(
                            "The free days of this device are over. Buy SHILLVPN "
                                    + "to go on.",
                            "Бесплатные дни на этом устройстве закончились. Купите "
                                    + "SHILLVPN, чтобы продолжить."));
                    return;
                }
                final String url = Json.string(reply, "subscription_url");
                if (Json.bool(reply, "ready") && XrayConfig.tokenFromLink(url) != null) {
                    setLink(url, done);
                    return;
                }
            } else if (reply != null && "bad_key".equals(Json.string(reply, "error"))) {
                cabinetKey = "";
                secrets.remove("cabinet");
                done.run(trialError(""));
                return;
            }
            if (attempt + 1 >= TRIAL_POLLS) {
                done.run(tr(
                        "The free days are still being prepared. Try again in "
                                + "a minute.",
                        "Бесплатные дни ещё готовятся. Попробуйте через минуту."));
                return;
            }
            AndroidUtilities.runOnUIThread(() -> waitTrialReady(attempt + 1, done), 2000);
        }, false);
    }

    // ---- The subscription.

    /**
     * Accepts /sub/, /c/, /r/<app>/ links or the bare token; fetches it,
     * keeps it and turns the tunnel on. done("") on success.
     */
    public void setLink(String newLink, Callback<String> done) {
        final String newToken = XrayConfig.tokenFromLink(newLink);
        if (newToken == null) {
            done.run(tr(
                    "This is not a SHILLVPN subscription link.",
                    "Это не ссылка подписки SHILLVPN."));
            return;
        }
        final String wasLink = link;
        final String wasToken = token;
        link = newLink.trim();
        token = newToken;
        setState(State.LOADING);
        fetch(error -> {
            if (!error.isEmpty()) {
                link = wasLink;
                token = wasToken;
                setState(token.isEmpty() ? State.NONE : State.OFF);
                done.run(error);
                return;
            }
            saveSecrets();
            enabled = true;
            saveFlags();
            restarts = 0;
            launch();
            done.run("");
        });
    }

    public void setEnabled(boolean value) {
        if (token.isEmpty() || enabled == value) {
            return;
        }
        enabled = value;
        saveFlags();
        if (value) {
            restarts = 0;
            if (entries.isEmpty()) {
                refresh();
            } else {
                launch();
            }
        } else {
            stopCore();
            setState(State.OFF);
        }
    }

    public void refresh() {
        if (token.isEmpty()) {
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(refreshRunnable);
        AndroidUtilities.runOnUIThread(refreshRunnable, REFRESH_INTERVAL);
        final byte[] was = body;
        if (state != State.ON) {
            setState(State.LOADING);
        }
        fetch(error -> {
            if (!error.isEmpty()) {
                if (state == State.LOADING) {
                    setState(entries.isEmpty() ? State.ERROR : State.OFF, error);
                    if (enabled && !entries.isEmpty()) {
                        launch();
                    }
                }
                return;
            }
            saveSecrets();
            if (accessEnded()) {
                // Not a hostage: without paid time Telegram goes direct.
                stopCore();
                setState(State.OFF);
            } else if (!enabled) {
                setState(State.OFF);
            } else if (!java.util.Arrays.equals(body, was) || core == null) {
                launch();
            } else {
                setState(State.ON); // Same list: refreshes the days left.
            }
            scheduleExpiryCheck();
            checkReminder();
        });
    }

    public void forget() {
        setEnabled(false);
        link = "";
        token = "";
        body = null;
        entries = Collections.emptyList();
        expiresAt = 0;
        secrets.remove("link");
        secrets.remove("body");
        saveFlags();
        setState(State.NONE);
    }

    private void fetch(Callback<String> done) {
        final String url = XrayConfig.subscriptionUrl(link, token);
        fetchVia(url, false, done);
    }

    private void fetchVia(String url, boolean viaCore, Callback<String> done) {
        final OkHttpClient client = viaCore ? tunnelClient() : direct;
        io.execute(() -> {
            int code = 0;
            byte[] data = null;
            String userInfo = null;
            try {
                final Request request = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "ShillGramm/1.0")
                        .get()
                        .build();
                try (Response response = client.newCall(request).execute()) {
                    code = response.code();
                    if (code == 200) {
                        userInfo = response.header("subscription-userinfo");
                        data = response.body().bytes();
                    }
                }
            } catch (Throwable ignored) {
                // Never logged: the error text may carry the link.
                code = 0;
                data = null;
            }
            final int finalCode = code;
            final byte[] finalData = data;
            final String finalUserInfo = userInfo;
            final List<XrayConfig.Entry> parsed = finalData != null
                    ? XrayConfig.parseBody(finalData)
                    : Collections.emptyList();
            AndroidUtilities.runOnUIThread(() -> {
                if (finalCode == 200 && finalData != null) {
                    if (parsed.isEmpty()) {
                        done.run(tr(
                                "The subscription has no servers this app can use.",
                                "В подписке нет серверов, которые открывает приложение."));
                        return;
                    }
                    body = finalData;
                    entries = parsed;
                    expiresAt = XrayConfig.expireFromUserInfo(finalUserInfo);
                    saveFlags();
                    done.run("");
                } else if (finalCode == 404 || finalCode == 403) {
                    done.run(tr(
                            "The link is not valid anymore. Take a new one in "
                                    + "@SHILLVPN_bot or in the cabinet on the site.",
                            "Ссылка больше не действует. Возьмите новую в "
                                    + "@SHILLVPN_bot или в кабинете на сайте."));
                } else if (!viaCore && core != null && port != 0) {
                    // The site is out of reach directly: try through the tunnel.
                    fetchVia(url, true, done);
                } else {
                    done.run(tr(
                            "Could not reach shillvpn.site. Check the internet "
                                    + "and try again.",
                            "Не удалось связаться с shillvpn.site. Проверьте "
                                    + "интернет и попробуйте ещё раз."));
                }
            });
        });
    }

    // ---- The core.

    private String corePath() {
        try {
            final File file = new File(context.getApplicationInfo().nativeLibraryDir, CORE_NAME);
            return file.isFile() ? file.getAbsolutePath() : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private String randomString(int length) {
        final String chars = "abcdefghijkmnpqrstuvwxyz23456789";
        final StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            result.append(chars.charAt(random.nextInt(chars.length())));
        }
        return result.toString();
    }

    private static int freePort() {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }))) {
            return server.getLocalPort();
        } catch (Throwable e) {
            return 0;
        }
    }

    private void launch() {
        if (!enabled || entries.isEmpty()) {
            return;
        } else if (accessEnded()) {
            stopCore();
            setState(State.OFF);
            return;
        }
        final String path = corePath();
        if (path == null) {
            killCore();
            useProxy(false);
            setState(State.ERROR, tr(
                    "The VPN core is missing from this build.",
                    "В этой сборке нет ядра VPN."));
            return;
        }
        killCore();
        port = freePort();
        if (port == 0) {
            useProxy(false);
            setState(State.ERROR, tr(
                    "No free local port for the VPN.",
                    "Нет свободного локального порта для VPN."));
            return;
        }
        user = USER_PREFIX + randomString(8);
        password = randomString(24);
        setState(State.STARTING);

        final int generation = ++coreGeneration;
        final byte[] config = XrayConfig.build(entries, port, user, password)
                .getBytes(StandardCharsets.UTF_8);
        final File workDir = new File(context.getFilesDir(), "shillvpn");
        io.execute(() -> {
            final Process process;
            try {
                //noinspection ResultOfMethodCallIgnored
                workDir.mkdirs();
                final ProcessBuilder builder = new ProcessBuilder(path, "run", "-config", "stdin:")
                        .directory(workDir)
                        .redirectErrorStream(true);
                builder.environment().put("XRAY_LOCATION_ASSET", workDir.getAbsolutePath());
                process = builder.start();
            } catch (Throwable e) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (generation == coreGeneration) {
                        coreFinished();
                    }
                });
                return;
            }
            // Its lines may name servers: read and drop, never logged.
            final Thread drain = new Thread(() -> {
                final byte[] buffer = new byte[4096];
                try (InputStream in = process.getInputStream()) {
                    //noinspection StatementWithEmptyBody
                    while (in.read(buffer) >= 0) {
                    }
                } catch (Throwable ignored) {
                }
            }, "shillvpn-core-out");
            drain.setDaemon(true);
            drain.start();
            try (OutputStream out = process.getOutputStream()) {
                out.write(config);
                out.flush();
            } catch (Throwable ignored) {
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (generation != coreGeneration) {
                    process.destroy();
                    return;
                }
                core = process;
                waitForPort(0, generation);
            });
            try {
                process.waitFor();
            } catch (InterruptedException ignored) {
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (generation == coreGeneration && core == process) {
                    coreFinished();
                }
            });
        });
    }

    private void waitForPort(int attempt, int generation) {
        if (generation != coreGeneration || core == null) {
            return;
        }
        final int checkPort = port;
        io.execute(() -> {
            boolean ok;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), checkPort), 500);
                ok = true;
            } catch (Throwable e) {
                ok = false;
            }
            final boolean listening = ok;
            AndroidUtilities.runOnUIThread(() -> {
                if (generation != coreGeneration || core == null || port != checkPort) {
                    return;
                } else if (listening) {
                    useProxy(true);
                    setState(State.ON);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (generation == coreGeneration) {
                            restarts = 0;
                        }
                    }, 60 * 1000);
                } else if (attempt + 1 < PORT_ATTEMPTS) {
                    AndroidUtilities.runOnUIThread(() -> waitForPort(attempt + 1, generation), 150);
                } else {
                    useProxy(false);
                    setState(State.ERROR, tr(
                            "The VPN core did not start.",
                            "Ядро VPN не запустилось."));
                }
            });
        });
    }

    private void coreFinished() {
        core = null;
        if (!enabled) {
            return;
        }
        if (++restarts <= MAX_RESTARTS) {
            setState(State.STARTING);
            AndroidUtilities.cancelRunOnUIThread(restartRunnable);
            AndroidUtilities.runOnUIThread(restartRunnable, 1000L * restarts * restarts);
        } else {
            useProxy(false);
            setState(State.ERROR, tr(
                    "The VPN core keeps stopping. Turn it off and on again.",
                    "Ядро VPN останавливается. Выключите и включите его снова."));
        }
    }

    /** Stops the running core without touching Telegram's proxy. */
    private void killCore() {
        AndroidUtilities.cancelRunOnUIThread(restartRunnable);
        ++coreGeneration; // Its exit is not a crash to restart from.
        final Process process = core;
        core = null;
        if (process != null) {
            process.destroy();
        }
    }

    private void stopCore() {
        killCore();
        useProxy(false);
    }

    // ---- Telegram's proxy.

    private static SharedPreferences mainConfig() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
    }

    private static ProxySettings currentProxySettings() {
        return ProxySettings.fromSharedPreferences(mainConfig());
    }

    static boolean isOurProxy(ProxySettings proxy) {
        return proxy != null
                && proxy.getType() == ProxySettings.Type.SOCKS5
                && "127.0.0.1".equals(proxy.getAddress())
                && proxy.getUser().startsWith(USER_PREFIX);
    }

    /** Drops our old local entries from Telegram's proxy list. */
    private static void removeOurProxies() {
        boolean changed = false;
        for (Iterator<SharedConfig.ProxyInfo> i = SharedConfig.proxyList.iterator(); i.hasNext(); ) {
            final SharedConfig.ProxyInfo info = i.next();
            if (isOurProxy(info.settings)) {
                if (SharedConfig.currentProxy == info) {
                    SharedConfig.currentProxy = null;
                }
                i.remove();
                changed = true;
            }
        }
        if (changed) {
            SharedConfig.saveProxyList();
        }
    }

    private void useProxy(boolean use) {
        try {
            SharedConfig.loadProxyList();
            final SharedPreferences preferences = mainConfig();
            final ProxySettings current = ProxySettings.fromSharedPreferences(preferences);
            if (use) {
                if (!isOurProxy(current)
                        && current.isValid()
                        && preferences.getBoolean("proxy_enabled", false)) {
                    // Someone's own proxy: bring it back when we turn off.
                    secrets.write("previous_proxy", Json.write(Json.object(
                            "type", ProxySettings.typeToInt(current.getType()),
                            "host", current.getAddress(),
                            "port", current.getPort(),
                            "user", current.getUser(),
                            "password", current.getPassword(),
                            "secret", current.getSecret())));
                }
                final ProxySettings ours = ProxySettings.builder()
                        .setType(ProxySettings.Type.SOCKS5)
                        .setAddress("127.0.0.1")
                        .setPort(port)
                        .setUser(user)
                        .setPassword(password)
                        .build();
                removeOurProxies();
                final SharedConfig.ProxyInfo info = SharedConfig.addProxy(new SharedConfig.ProxyInfo(ours));
                SharedConfig.currentProxy = info;
                final SharedPreferences.Editor editor = preferences.edit();
                ours.toSharedPreferences(editor);
                editor.putBoolean("proxy_enabled", true);
                // Calls are UDP: the local port serves TCP only.
                editor.putBoolean("proxy_enabled_calls", false);
                editor.commit();
                ConnectionsManager.setProxySettings(true, ours);
            } else {
                if (!isOurProxy(current)) {
                    removeOurProxies();
                    return;
                }
                ProxySettings previous = null;
                final String saved = secrets.readString("previous_proxy");
                if (saved != null) {
                    final Map<String, Object> object = Json.parseObject(saved);
                    if (object != null) {
                        previous = ProxySettings.builder()
                                .setType(ProxySettings.intToType((int) Json.number(object, "type")))
                                .setAddress(Json.string(object, "host"))
                                .setPort((int) Json.number(object, "port"))
                                .setUser(Json.string(object, "user"))
                                .setPassword(Json.string(object, "password"))
                                .setSecret(Json.string(object, "secret"))
                                .build();
                    }
                    secrets.remove("previous_proxy");
                }
                removeOurProxies();
                final SharedPreferences.Editor editor = preferences.edit();
                if (previous != null && previous.isValid() && !isOurProxy(previous)) {
                    SharedConfig.currentProxy = SharedConfig.addProxy(new SharedConfig.ProxyInfo(previous));
                    previous.toSharedPreferences(editor);
                    editor.putBoolean("proxy_enabled", true);
                    editor.commit();
                    ConnectionsManager.setProxySettings(true, previous);
                } else {
                    SharedConfig.currentProxy = null;
                    editor.putString("proxy_ip", "");
                    editor.putString("proxy_pass", "");
                    editor.putString("proxy_user", "");
                    editor.putString("proxy_secret", "");
                    editor.putInt("proxy_type", 0);
                    editor.putInt("proxy_port", 1080);
                    editor.putBoolean("proxy_enabled", false);
                    editor.putBoolean("proxy_enabled_calls", false);
                    editor.commit();
                    ConnectionsManager.setProxySettings(false, null);
                }
            }
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        } catch (Throwable ignored) {
            // Never logged: the settings carry the local login.
        }
    }

    // ---- Storage.

    private void saveSecrets() {
        secrets.write("link", link);
        if (body != null) {
            secrets.write("body", body);
        }
    }

    private void loadSecrets() {
        final String key = secrets.readString("cabinet");
        if (key != null) {
            cabinetKey = key;
        }
        final String savedLink = secrets.readString("link");
        if (savedLink == null) {
            return;
        }
        final String savedToken = XrayConfig.tokenFromLink(savedLink);
        if (savedToken == null) {
            return;
        }
        link = savedLink;
        token = savedToken;
        final byte[] savedBody = secrets.read("body");
        if (savedBody != null) {
            final List<XrayConfig.Entry> parsed = XrayConfig.parseBody(savedBody);
            if (!parsed.isEmpty()) {
                body = savedBody;
                entries = parsed;
            }
        }
    }

    private void saveFlags() {
        flags.edit()
                .putBoolean("enabled", enabled)
                .putLong("expires", expiresAt)
                .putLong("reminded", remindedAt)
                .apply();
    }

    private void loadFlags() {
        enabled = flags.getBoolean("enabled", false);
        expiresAt = flags.getLong("expires", 0);
        remindedAt = flags.getLong("reminded", 0);
    }

    // ---- Opening pages.

    /** Whether a Telegram account is signed in on this device. */
    static boolean hasTelegramAccount() {
        try {
            return UserConfig.getActivatedAccountsCount() > 0;
        } catch (Throwable e) {
            return false;
        }
    }
}
