/*
 * SHILLGRAM: ghost mode UI: the main-menu icon, the "on" bulletin and the
 * settings dialog with the five ghost options (see ShillGhost).
 */
package io.github.audit0.shillgram.ghost;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import static org.telegram.messenger.AndroidUtilities.dp;
import static io.github.audit0.shillgram.vpn.ShillVpn.tr;

public final class ShillGhostSettings {

    private ShillGhostSettings() {
    }

    public static String menuText() {
        return tr("Ghost mode", "Режим призрака");
    }

    public static String settingsText() {
        return tr("Ghost mode settings", "Настройки призрака");
    }

    // The ghost picture (32 dp, Telegram's deleted-account avatar) drawn in
    // a 24 dp box, the size of the other menu icons. Tinting goes through.
    public static Drawable icon(Context context, int sizeDp) {
        return new SizedDrawable(context.getResources().getDrawable(R.drawable.ghost).mutate(), dp(sizeDp), dp(2));
    }

    // Main-menu toggle: flips the master switch and tells the user.
    public static void toggle(BaseFragment fragment) {
        final boolean on = !ShillGhost.isEnabled();
        ShillGhost.setEnabled(on);
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        try {
            final Drawable icon = icon(fragment.getParentActivity(), 28);
            BulletinFactory.of(fragment).createSimpleBulletin(icon, on
                    ? tr("Ghost mode is on", "Режим призрака включён")
                    : tr("Ghost mode is off", "Режим призрака выключен")).show();
        } catch (Throwable ignore) {
        }
    }

    public static void show(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final Context context = fragment.getParentActivity();
        final Theme.ResourcesProvider resources = fragment.getResourceProvider();

        final LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        final TextView info = new TextView(context);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        info.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resources));
        info.setText(tr(
                "Works for all accounts while ghost mode is on. Chats are read only on this device. "
                        + "Sending a message shows you online for a moment (Telegram does it).",
                "Действует для всех аккаунтов, пока режим призрака включён. Чаты читаются только на этом устройстве. "
                        + "Отправка сообщения на мгновение показывает вас в сети (так делает Telegram)."));
        container.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 8));

        final String[] keys = {
                ShillGhost.NO_READ,
                ShillGhost.NO_STORIES,
                ShillGhost.NO_ONLINE,
                ShillGhost.NO_TYPING,
                ShillGhost.READ_ON_SEND,
        };
        final String[] texts = {
                tr("Don't send read receipts", "Не отправлять «прочитано»"),
                tr("Don't mark stories as viewed", "Не отмечать просмотр историй"),
                tr("Don't show \"online\"", "Не показывать «в сети»"),
                tr("Don't show \"typing\"", "Не показывать «печатает»"),
                tr("Read the chat when I reply", "Читать чат при ответе"),
        };
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            final CheckBoxCell cell = new CheckBoxCell(context, 1, resources);
            cell.setBackground(Theme.getSelectorDrawable(false));
            cell.setMultiline(true);
            cell.setText(texts[i], "", ShillGhost.option(key), false);
            cell.setOnClickListener(v -> {
                final boolean value = !cell.isChecked();
                cell.setChecked(value, true);
                ShillGhost.setOption(key, value);
            });
            container.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 8, 0, 8, 0));
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(context, resources);
        builder.setTitle(menuText());
        builder.setView(container);
        builder.setPositiveButton(tr("Done", "Готово"), null);
        fragment.showDialog(builder.create());
    }

    private static final class SizedDrawable extends Drawable {
        private final Drawable inner;
        private final int size;
        private final int inset;

        SizedDrawable(Drawable inner, int size, int inset) {
            this.inner = inner;
            this.size = size;
            this.inset = inset;
        }

        @Override
        public void draw(Canvas canvas) {
            final Rect b = getBounds();
            inner.setBounds(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
            inner.draw(canvas);
        }

        @Override
        public void setAlpha(int alpha) {
            inner.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            inner.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }
    }
}
