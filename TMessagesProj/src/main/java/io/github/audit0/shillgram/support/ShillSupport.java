/*
 * SHILLGRAM: «Report a problem» (port of the desktop shill_support). Opens
 * the support chat @SHILLSUP with a draft that names the SHILLGRAM version,
 * Android and the SHILLVPN state. The user writes what happened and sends it:
 * nothing is sent by itself, and the draft has no account, phone, ID or
 * subscription link in it.
 */
package io.github.audit0.shillgram.support;

import android.net.Uri;
import android.os.Build;

import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.BaseFragment;

import io.github.audit0.shillgram.vpn.ShillVpn;

public final class ShillSupport {
    private static final String SUPPORT = "https://t.me/SHILLSUP";

    private ShillSupport() {
    }

    // "SHILLGRAM 1.2 · Android 15 (arm64-v8a) · SHILLVPN: включён".
    public static String header() {
        final String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?";
        return "SHILLGRAM " + BuildConfig.SHILLGRAM_VERSION
                + " · Android " + Build.VERSION.RELEASE + " (" + abi + ")"
                + " · SHILLVPN: " + vpnText();
    }

    public static void report(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final String draft = header() + "\n\n" + ShillVpn.tr("What happened: ", "Что случилось: ");
        Browser.openUrl(fragment.getParentActivity(), SUPPORT + "?text=" + Uri.encode(draft));
    }

    private static String vpnText() {
        final ShillVpn vpn = ShillVpn.getInstance();
        if (!vpn.hasSubscription()) {
            return ShillVpn.tr("no subscription", "нет подписки");
        }
        switch (vpn.getState()) {
            case ON:
                return ShillVpn.tr("on", "включён");
            case LOADING:
            case STARTING:
                return ShillVpn.tr("connecting", "подключается");
            case ERROR:
                return ShillVpn.tr("error", "ошибка");
            default:
                return ShillVpn.tr("off", "выключен");
        }
    }
}
