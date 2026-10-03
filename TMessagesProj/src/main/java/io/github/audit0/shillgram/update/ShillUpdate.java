/*
 * SHILLGRAM: tells people a new SHILLGRAM is out (port of the desktop
 * shill_update). The app asks GitHub for the latest release of
 * audit0/SHILLGRAM; when it is newer than SHILLGRAM_VERSION and carries an
 * APK for this phone, Telegram's own update popup offers the download. The
 * APK is signed with the same key, so it installs over the old one.
 * Nothing about the user is sent: a plain anonymous GET.
 */
package io.github.audit0.shillgram.update;

import android.os.Build;

import org.telegram.messenger.BuildConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.audit0.shillgram.vpn.Json;
import io.github.audit0.shillgram.vpn.ShillVpn;
import tw.nekomimi.nekogram.helpers.remote.BaseRemoteHelper;

public final class ShillUpdate {
    private static final String FEED =
            "https://api.github.com/repos/audit0/SHILLGRAM/releases/latest";

    private ShillUpdate() {
    }

    public static void check(BaseRemoteHelper.Delegate delegate) {
        ShillVpn.getInstance().getText(FEED, "application/vnd.github+json", text -> {
            final Map<String, Object> release = text != null ? Json.parseObject(text) : null;
            if (release == null) {
                delegate.onTLResponse(null, ShillVpn.tr(
                        "Could not reach GitHub. Try again later.",
                        "Не удалось связаться с GitHub. Попробуйте позже."));
                return;
            }
            delegate.onTLResponse(parse(release), null);
        });
    }

    private static TLRPC.TL_help_appUpdate parse(Map<String, Object> release) {
        if (Json.bool(release, "draft") || Json.bool(release, "prerelease")) {
            return null;
        }
        String version = Json.string(release, "tag_name");
        if (version.startsWith("v") || version.startsWith("V")) {
            version = version.substring(1);
        }
        if (!isNewer(version, BuildConfig.SHILLGRAM_VERSION)) {
            return null;
        }
        final String url = apkUrl(release, version);
        if (url == null) {
            return null; // A release for other platforms only.
        }
        final TLRPC.TL_help_appUpdate update = new TLRPC.TL_help_appUpdate();
        update.version = version;
        update.text = ShillVpn.tr(
                "A new version of SHILLGRAM is out: " + version + ". You have "
                        + BuildConfig.SHILLGRAM_VERSION + ".\n\nDownload the APK and open "
                        + "it: it installs over this one, your chats and settings stay.",
                "Вышла новая версия SHILLGRAM: " + version + ". У вас "
                        + BuildConfig.SHILLGRAM_VERSION + ".\n\nСкачайте APK и откройте его: "
                        + "он установится поверх, чаты и настройки сохранятся.");
        update.url = url;
        update.flags |= 4;
        return update;
    }

    // This phone's APK, the universal one when there is none for its ABI.
    private static String apkUrl(Map<String, Object> release, String version) {
        final String prefix = "SHILLGRAM-" + version + "-Android-";
        final List<String> wanted = new ArrayList<>();
        for (String abi : Build.SUPPORTED_ABIS) {
            wanted.add(prefix + abi + ".apk");
        }
        wanted.add(prefix + "universal.apk");
        final List<Object> assets = Json.list(release, "assets");
        for (String name : wanted) {
            for (Object value : assets) {
                if (!(value instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                final Map<String, Object> asset = (Map<String, Object>) value;
                final String url = Json.string(asset, "browser_download_url");
                if (name.equals(Json.string(asset, "name")) && url.startsWith("https://")) {
                    return url;
                }
            }
        }
        return null;
    }

    // "1.0.2" against "1.0": numbers part by part, anything after '-' ignored.
    static boolean isNewer(String theirs, String ours) {
        final int[] a = parseVersion(theirs);
        final int[] b = parseVersion(ours);
        if (a == null || b == null) {
            return false;
        }
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            final int x = i < a.length ? a[i] : 0;
            final int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int[] parseVersion(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        final String[] parts = text.split("-", 2)[0].split("\\.");
        final int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (result[i] < 0) {
                return null;
            }
        }
        return result;
    }
}
