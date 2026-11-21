package clipclean.android;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.PersistableBundle;
import android.widget.Toast;

import clipclean.bridge.mobile.Mobile;
import clipclean.bridge.mobile.Result;
import clipclean.bridge.mobile.Settings;

final class ClipboardActions {
    private static final String SENSITIVE = "android.content.extra.IS_SENSITIVE";
    private static final int MAX_CHARS = 256 * 1024;

    private ClipboardActions() { }

    static void cleanFromClipboard(Context context, Settings settings) {
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        ClipData source = clipboard.getPrimaryClip();
        CharSequence text = plainText(source);
        if (source == null || text == null) {
            message(context, "クリップボードにテキストがありません");
            return;
        }
        PersistableBundle extras = source.getDescription().getExtras();
        if (extras != null && extras.getBoolean(SENSITIVE)) {
            message(context, "機密としてマークされたクリップボードです");
            return;
        }
        clean(context, clipboard, text, settings, source);
    }

    static void copySharedText(Context context, CharSequence text, Settings settings) {
        clean(context, context.getSystemService(ClipboardManager.class), text, settings, null);
    }

    static CharSequence plainText(ClipData clip) {
        if (clip == null || clip.getItemCount() != 1
                || !clip.getDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) return null;
        ClipData.Item item = clip.getItemAt(0);
        return item.getUri() == null && item.getIntent() == null ? item.getText() : null;
    }

    private static void clean(Context context, ClipboardManager clipboard, CharSequence text,
                              Settings settings, ClipData source) {
        if (text == null || text.length() > MAX_CHARS) {
            message(context, "整理できるテキストがありません");
            return;
        }
        Result result = Mobile.clean(text.toString(), settings);
        if (!result.supported()) {
            message(context, "整理できるURLがありません");
            return;
        }
        if (source != null) {
            if (!result.changed()) {
                message(context, "削除するパラメータがありません");
                return;
            }
            ClipDescription latest = clipboard.getPrimaryClipDescription();
            if (latest == null || latest.getTimestamp() != source.getDescription().getTimestamp()) {
                message(context, "クリップボードの内容が変わりました");
                return;
            }
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("ClipClean", result.text()));
        if (Build.VERSION.SDK_INT < 33) message(context, "クリップボードにコピーしました");
    }

    private static void message(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }
}
