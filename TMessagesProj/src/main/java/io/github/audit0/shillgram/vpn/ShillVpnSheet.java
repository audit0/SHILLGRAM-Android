/*
 * SHILLGRAM: SHILLVPN built into the app — the screens.
 *
 * The same states and buttons as the desktop SHILLVPN box (VpnBox,
 * RenewBox in shillgramm/shill_vpn.cpp), built from Telegram's own
 * BottomSheet, TextCell, EditTextBoldCursor and theme colors.
 */
package io.github.audit0.shillgram.vpn;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.telegram.messenger.AndroidUtilities.dp;
import static io.github.audit0.shillgram.vpn.ShillVpn.tr;

public final class ShillVpnSheet extends BottomSheet {

    private static boolean greeted;

    private final BaseFragment fragment;
    private final Theme.ResourcesProvider resourcesProvider;
    private final LinearLayout content;
    private final Runnable listener = this::onStateChanged;
    private boolean subscribedLayout;

    private TextView statusView;
    private TextView errorView;
    private ButtonWithCounterView trialButton;
    private ButtonWithCounterView connectButton;
    private ButtonWithCounterView toggleButton;
    private EditTextBoldCursor linkField;
    private boolean linkBusy;

    // ---- Entry points.

    /** The SHILLVPN screen from a menu. */
    public static void show(BaseFragment fragment) {
        tryShow(fragment);
    }

    private static boolean tryShow(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return false;
        }
        return fragment.showDialog(new ShillVpnSheet(fragment)) != null;
    }

    /**
     * The intro and login screens: the connection first, then the login.
     * Without a subscription the SHILLVPN screen greets the user, once per
     * app start.
     */
    public static void greetIfNeeded(BaseFragment fragment) {
        if (greeted || ShillVpn.getInstance().hasSubscription()) {
            return;
        }
        greeted = true;
        greet(fragment, 0);
    }

    private static void greet(BaseFragment fragment, int attempt) {
        // A screen transition may still run: try again a little later.
        AndroidUtilities.runOnUIThread(() -> {
            if (fragment.getParentActivity() == null
                    || fragment.getParentActivity().isFinishing()
                    || ShillVpn.getInstance().hasSubscription()) {
                return;
            }
            if (!tryShow(fragment) && attempt < 5) {
                greet(fragment, attempt + 1);
            }
        }, 400 + 300L * attempt);
    }

    static boolean canShow() {
        return visibleFragment() != null;
    }

    private static BaseFragment visibleFragment() {
        final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) {
            return null;
        }
        final Activity activity = fragment.getParentActivity();
        return (activity != null && !activity.isFinishing()) ? fragment : null;
    }

    private static Context anyContext() {
        final BaseFragment fragment = visibleFragment();
        return fragment != null ? fragment.getParentActivity() : null;
    }

    /**
     * Renew: the SHILLVPN Mini App inside the app when logged in, the site
     * cabinet otherwise. campaign: where the purchase started, for the
     * site's utm_campaign.
     */
    public static void openRenew(Context context, String campaign) {
        if (context == null) {
            return;
        }
        final String source = TextUtils.isEmpty(campaign) ? "renew" : campaign;
        final ShillVpn vpn = ShillVpn.getInstance();
        if (vpn.hasCabinet()) {
            // The app's own trial is a site account, not the Telegram one.
            Browser.openUrl(context, vpn.cabinetUrl(source));
        } else if (ShillVpn.hasTelegramAccount()) {
            // The bot's Mini App right in the app: Stars, SBP, crypto.
            Browser.openUrl(context, "https://t.me/" + ShillVpn.BOT + "?startapp");
        } else {
            Browser.openUrl(context, ShillVpn.siteUrl("/app/buy/", source, null));
        }
    }

    /** The bot's «Пригласить друга» screen (a Telegram account's subscription). */
    public static void openInvite(Context context) {
        if (context != null && ShillVpn.hasTelegramAccount()) {
            Browser.openUrl(context, "https://t.me/" + ShillVpn.BOT + "?start=invite");
        }
    }

    // ---- The sheet.

    private ShillVpnSheet(BaseFragment fragment) {
        super(fragment.getParentActivity(), true, fragment.getResourceProvider());
        this.fragment = fragment;
        this.resourcesProvider = fragment.getResourceProvider();
        setTitle("SHILLVPN", true);
        fixNavigationBar(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));

        content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(8));
        final ScrollView scroll = new ScrollView(getContext());
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        setCustomView(scroll);
        rebuild();
    }

    @Override
    public void show() {
        super.show();
        ShillVpn.getInstance().addListener(listener);
    }

    @Override
    public void dismissInternal() {
        ShillVpn.getInstance().removeListener(listener);
        super.dismissInternal();
    }

    private void onStateChanged() {
        if (subscribedLayout != ShillVpn.getInstance().hasSubscription()) {
            if (!linkBusy && trialButton != null && !trialButton.isLoading()) {
                rebuild();
            }
            return;
        }
        if (statusView != null) {
            statusView.setText(ShillVpn.getInstance().statusText());
        }
        if (toggleButton != null) {
            toggleButton.setText(ShillVpn.getInstance().isEnabled()
                    ? tr("Turn off", "Выключить")
                    : tr("Turn on", "Включить"), true);
        }
    }

    private void rebuild() {
        content.removeAllViews();
        statusView = null;
        errorView = null;
        trialButton = null;
        connectButton = null;
        toggleButton = null;
        linkField = null;
        subscribedLayout = ShillVpn.getInstance().hasSubscription();

        statusView = addText(ShillVpn.getInstance().statusText(), 14, Theme.key_dialogTextGray2);
        if (!subscribedLayout) {
            buildNoSubscription();
        } else {
            buildSubscribed();
        }
    }

    private void buildNoSubscription() {
        addText(tr(
                "If Telegram does not open for you, connect SHILLVPN. One "
                        + "subscription works here, on your phone and on your "
                        + "computer, and Telegram in SHILLGRAM works right away.",
                "Если Telegram у вас не открывается, подключите SHILLVPN. "
                        + "Одна подписка работает здесь, на телефоне и на компьютере, "
                        + "а Telegram в SHILLGRAM заработает сразу."),
                15, Theme.key_dialogTextBlack).setPadding(dp(21), dp(10), dp(21), dp(4));

        trialButton = new ButtonWithCounterView(getContext(), true, resourcesProvider);
        trialButton.setText(tr("Get 3 days free", "Получить 3 дня бесплатно"), false);
        trialButton.setOnClickListener(v -> onTrial());
        content.addView(trialButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 12, 16, 0));

        final String terms = tr(
                "One trial per device. By taking it you accept the **terms**.",
                "Одна проба на устройство. Получая её, вы принимаете **условия**.");
        final TextView termsView = addText("", 13, Theme.key_dialogTextGray3);
        termsView.setText(AndroidUtilities.replaceSingleTag(
                terms,
                Theme.key_dialogTextLink,
                AndroidUtilities.REPLACING_TAG_TYPE_LINK,
                () -> Browser.openUrl(getContext(), ShillVpn.TERMS_URL),
                resourcesProvider));
        termsView.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        termsView.setPadding(dp(21), dp(8), dp(21), dp(4));

        addAction(tr("Buy SHILLVPN", "Купить SHILLVPN"), () ->
                Browser.openUrl(getContext(), ShillVpn.siteUrl("/app/buy/", "buy", null)));

        addText(tr(
                "Already have a subscription? Paste its link from "
                        + "@SHILLVPN_bot or from the cabinet on shillvpn.site.",
                "Уже есть подписка? Вставьте ссылку из @SHILLVPN_bot "
                        + "или из личного кабинета на shillvpn.site."),
                13, Theme.key_dialogTextGray3).setPadding(dp(21), dp(8), dp(21), dp(4));

        linkField = new EditTextBoldCursor(getContext());
        linkField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        linkField.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        linkField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, resourcesProvider));
        linkField.setHint(tr("Subscription link", "Ссылка подписки"));
        linkField.setBackground(null);
        linkField.setLineColors(
                Theme.getColor(Theme.key_windowBackgroundWhiteInputField, resourcesProvider),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourcesProvider),
                Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
        linkField.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        linkField.setCursorSize(dp(20));
        linkField.setCursorWidth(1.5f);
        linkField.setSingleLine(true);
        linkField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        linkField.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        linkField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onConnect();
                return true;
            }
            return false;
        });
        linkField.setPadding(0, dp(4), 0, dp(8));
        content.addView(linkField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 21, 4, 21, 0));

        errorView = addText("", 13, Theme.key_text_RedRegular);
        errorView.setPadding(dp(21), dp(6), dp(21), 0);
        errorView.setVisibility(View.GONE);

        connectButton = new ButtonWithCounterView(getContext(), true, resourcesProvider);
        connectButton.setText(tr("Connect", "Подключить"), false);
        connectButton.setOnClickListener(v -> onConnect());
        content.addView(connectButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 12, 16, 0));

        final ButtonWithCounterView later = new ButtonWithCounterView(getContext(), false, resourcesProvider);
        later.setText(tr("Later", "Позже"), false);
        later.setOnClickListener(v -> dismiss());
        content.addView(later, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 4, 16, 0));
    }

    private void buildSubscribed() {
        final ShillVpn vpn = ShillVpn.getInstance();
        final View spacer = new View(getContext());
        content.addView(spacer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));

        addAction(tr("Renew subscription", "Продлить подписку"), () -> {
            dismiss();
            openRenew(fragment.getParentActivity(), "box");
        });
        if (!vpn.hasCabinet() && ShillVpn.hasTelegramAccount()) {
            // The bot's referral program belongs to the Telegram account
            // whose subscription this is; the app's own site cabinet has none.
            final TextCell invite = addAction(tr(
                    "Invite a friend: bonus days for both of you",
                    "Пригласить друга: бонусные дни и вам, и ему"), () -> {
                dismiss();
                openInvite(fragment.getParentActivity());
            });
            vpn.loadShopInfo(info -> {
                if (info != null && info.referralDays > 0 && isShowing()) {
                    invite.setText(String.format(tr(
                            "Invite a friend: +%1$s to their first payment, bonus "
                                    + "days for you",
                            "Пригласить друга: ему +%1$s к первой оплате, вам "
                                    + "бонусные дни"), ShillVpn.daysText(info.referralDays)), false);
                }
            });
        }
        addAction(tr(
                "Connect a phone or another device",
                "Подключить телефон или другое устройство"), () ->
                Browser.openUrl(getContext(), ShillVpn.getInstance().connectPageUrl()));
        addAction(tr("Update the server list", "Обновить список серверов"), () ->
                ShillVpn.getInstance().refresh());
        addAction(tr("Use another link", "Сменить ссылку"), () -> {
            ShillVpn.getInstance().forget();
            dismiss();
            AndroidUtilities.runOnUIThread(() -> show(fragment), 250);
        });

        toggleButton = new ButtonWithCounterView(getContext(), true, resourcesProvider);
        toggleButton.setText(vpn.isEnabled()
                ? tr("Turn off", "Выключить")
                : tr("Turn on", "Включить"), false);
        toggleButton.setOnClickListener(v -> {
            final ShillVpn instance = ShillVpn.getInstance();
            instance.setEnabled(!instance.isEnabled());
            onStateChanged();
        });
        content.addView(toggleButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 12, 16, 0));

        final ButtonWithCounterView close = new ButtonWithCounterView(getContext(), false, resourcesProvider);
        close.setText(tr("Close", "Закрыть"), false);
        close.setOnClickListener(v -> dismiss());
        content.addView(close, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 4, 16, 0));
    }

    private void onTrial() {
        if (trialButton == null || trialButton.isLoading()) {
            return;
        }
        showError(null);
        trialButton.setLoading(true);
        ShillVpn.getInstance().startTrial(error -> {
            if (!isShowing()) {
                return;
            }
            if (TextUtils.isEmpty(error)) {
                dismiss();
            } else {
                if (trialButton != null) {
                    trialButton.setLoading(false);
                }
                showError(error);
            }
        });
    }

    private void onConnect() {
        if (linkBusy || linkField == null) {
            return;
        }
        final String link = linkField.getText() == null ? "" : linkField.getText().toString().trim();
        if (XrayConfig.tokenFromLink(link) == null) {
            AndroidUtilities.shakeViewSpring(linkField, -6);
            linkField.setErrorText(" ");
            showError(tr(
                    "This is not a SHILLVPN subscription link.",
                    "Это не ссылка подписки SHILLVPN."));
            return;
        }
        linkBusy = true;
        showError(null);
        if (connectButton != null) {
            connectButton.setLoading(true);
        }
        ShillVpn.getInstance().setLink(link, error -> {
            linkBusy = false;
            if (!isShowing()) {
                return;
            }
            if (TextUtils.isEmpty(error)) {
                dismiss();
            } else {
                if (connectButton != null) {
                    connectButton.setLoading(false);
                }
                if (linkField != null) {
                    AndroidUtilities.shakeViewSpring(linkField, -6);
                }
                showError(error);
            }
        });
    }

    private void showError(String error) {
        if (errorView == null) {
            return;
        }
        if (TextUtils.isEmpty(error)) {
            errorView.setVisibility(View.GONE);
            if (linkField != null) {
                linkField.setErrorText(null);
            }
        } else {
            errorView.setText(error);
            errorView.setVisibility(View.VISIBLE);
        }
    }

    private TextView addText(String text, int size, int colorKey) {
        final TextView view = new TextView(getContext());
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(Theme.getColor(colorKey, resourcesProvider));
        view.setLinkTextColor(Theme.getColor(Theme.key_dialogTextLink, resourcesProvider));
        view.setGravity(Gravity.LEFT);
        view.setText(text);
        view.setPadding(dp(21), 0, dp(21), 0);
        content.addView(view, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return view;
    }

    private TextCell addAction(String text, Runnable click) {
        final TextCell cell = new TextCell(getContext(), 21, true, false, resourcesProvider);
        cell.setText(text, false);
        cell.setColors(-1, Theme.key_dialogTextBlue);
        cell.setBackground(Theme.getSelectorDrawable(false, resourcesProvider));
        cell.setOnClickListener(v -> click.run());
        content.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return cell;
    }

    // ---- The renewal reminder.

    static void showRenew(boolean ended, long left, ShillVpn.ShopInfo info) {
        final BaseFragment fragment = visibleFragment();
        if (fragment == null) {
            return;
        }
        final Context context = fragment.getParentActivity();
        final boolean ru = ShillVpn.isRussian();
        final int hours = (int) Math.max(1, (left + 3599) / 3600);
        final StringBuilder text = new StringBuilder(ended
                ? tr(
                "Your SHILLVPN access has ended. Telegram now connects "
                        + "directly and may not open on some networks. Renew to bring "
                        + "the protection back.",
                "Доступ к SHILLVPN закончился. Telegram подключается напрямую "
                        + "и в некоторых сетях может не открываться. Продлите, чтобы "
                        + "защита вернулась.")
                : String.format(tr(
                "SHILLVPN access ends in %1$s. Renew so that Telegram in "
                        + "SHILLGRAM and VPN on your other devices keep working "
                        + "without a break.",
                "Доступ к SHILLVPN закончится через %1$s. Продлите, чтобы "
                        + "Telegram в SHILLGRAM и VPN на других устройствах работали "
                        + "без перерыва."), ShillVpn.hoursText(hours)));
        if (info != null && !info.plans.isEmpty()) {
            // The longest plan first: the lowest price per month.
            final List<ShillVpn.Plan> plans = new ArrayList<>(info.plans);
            Collections.sort(plans, (a, b) -> Integer.compare(b.days, a.days));
            final ShillVpn.Plan best = plans.get(0);
            final ShillVpn.Plan cheapest = plans.get(plans.size() - 1);
            final int perMonth = (int) Math.round(best.priceRub * 30.0 / best.days);
            text.append("\n\n");
            if (best.days > 45) {
                text.append(String.format(
                        tr("%1$s — %2$d ₽, about %3$d ₽ a month", "%1$s — %2$d ₽, это около %3$d ₽ в месяц"),
                        planTitle(best, ru), best.priceRub, perMonth));
            } else {
                text.append(String.format("%1$s — %2$d ₽", planTitle(best, ru), best.priceRub));
            }
            if (cheapest.days != best.days) {
                text.append('\n').append(String.format("%1$s — %2$d ₽", planTitle(cheapest, ru), cheapest.priceRub));
            }
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(context, fragment.getResourceProvider());
        builder.setTitle("SHILLVPN");
        builder.setMessage(text.toString());
        builder.setPositiveButton(tr("Renew", "Продлить"), (dialog, which) ->
                openRenew(context, ended ? "ended" : "reminder"));
        builder.setNegativeButton(tr("Later", "Позже"), null);
        fragment.showDialog(builder.create());
    }

    private static String planTitle(ShillVpn.Plan plan, boolean ru) {
        final int months = (int) Math.max(1, Math.round(plan.days / 30.0));
        return (ru && !TextUtils.isEmpty(plan.title))
                ? plan.title
                : (months + (months == 1 ? " month" : " months"));
    }
}
