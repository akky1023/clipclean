package clipclean.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;

import clipclean.bridge.mobile.Mobile;
import clipclean.bridge.mobile.Settings;

public final class MainActivity extends Activity {
    private static final int EXPORT = 1;
    private static final int IMPORT = 2;

    private LocalSettings store;
    private Settings settings;
    private ScrollView scroll;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new LocalSettings(this);
        settings = store.load();
        render(state == null ? 0 : state.getInt("scrollPosition"));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("scrollPosition", scroll.getScrollY());
    }

    @SuppressWarnings("deprecation")
    private void render(int scrollPosition) {
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            int left = insets.getSystemWindowInsetLeft();
            int top = insets.getSystemWindowInsetTop();
            int right = insets.getSystemWindowInsetRight();
            int bottom = insets.getSystemWindowInsetBottom();
            view.setPadding(left, top, right, bottom);
            return insets;
        });
        setContentView(scroll);
        scroll.requestApplyInsets();

        TextView title = heading("ClipClean", 28);
        content.addView(title);
        Button clipboard = new Button(this);
        clipboard.setText("クリップボードを整理");
        clipboard.setOnClickListener(view -> ClipboardActions.cleanFromClipboard(this, settings));
        content.addView(clipboard);
        addSwitch(content, "文章や複数行に含まれるURLも整理する", settings.embeddedUrls(),
                Settings::withEmbeddedUrls);

        LinearLayout files = new LinearLayout(this);
        Button export = new Button(this);
        export.setText("エクスポート");
        export.setOnClickListener(view -> exportSettings());
        Button importFile = new Button(this);
        importFile.setText("インポート");
        importFile.setOnClickListener(view -> importSettings());
        files.addView(export, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        files.addView(importFile, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        content.addView(files);

        TextView ruleHeading = heading("削除するパラメータ", 20);
        ruleHeading.setPadding(0, dp(16), 0, 0);
        content.addView(ruleHeading);
        for (int i = 0; i < Mobile.siteCount(); i++) {
            int site = i;
            TextView label = heading(Mobile.siteLabel(site), 17);
            label.setPadding(0, dp(12), 0, 0);
            content.addView(label);
            for (int j = 0; j < Mobile.ruleCount(site); j++) {
                int rule = j;
                addSwitch(content, Mobile.ruleLabel(site, rule), settings.removes(site, rule),
                        (current, checked) -> current.withRemoved(site, rule, checked));
            }
            addSwitch(content, "リストにないパラメータも削除する", settings.removesUnknown(site),
                    (current, checked) -> current.withRemovedUnknown(site, checked));
            if (site == Mobile.YouTubeSite) {
                TextView retained = new TextView(this);
                retained.setText(R.string.always_keep_youtube_ids);
                content.addView(retained);
            }
        }
        ScrollView current = scroll;
        current.post(() -> current.scrollTo(0, scrollPosition));
    }

    private interface Toggle {
        Settings apply(Settings previous, boolean checked);
    }

    private void addSwitch(LinearLayout parent, String label, boolean selected, Toggle toggle) {
        Switch row = new Switch(this);
        row.setText(label);
        row.setMinHeight(dp(48));
        row.setSwitchPadding(dp(6));
        row.setChecked(selected);
        row.setOnCheckedChangeListener((button, checked) -> {
            if (!update(current -> toggle.apply(current, checked))) render(scroll.getScrollY());
        });
        parent.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private TextView heading(String text, int size) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTypeface(null, Typeface.BOLD);
        if (Build.VERSION.SDK_INT >= 28) label.setAccessibilityHeading(true);
        return label;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean update(UnaryOperator<Settings> change) {
        Settings next = change.apply(settings);
        if (!store.save(next)) {
            message("設定を保存できませんでした");
            return false;
        }
        settings = next;
        return true;
    }

    @SuppressWarnings("deprecation")
    private void exportSettings() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "clipclean-settings.json");
        try {
            startActivityForResult(intent, EXPORT);
        } catch (ActivityNotFoundException e) {
            message("ファイルの保存先を選べません");
        }
    }

    @SuppressWarnings("deprecation")
    private void importSettings() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, IMPORT);
        } catch (ActivityNotFoundException e) {
            message("設定ファイルを選べません");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == EXPORT) {
            try (OutputStream stream = getContentResolver().openOutputStream(uri, "wt")) {
                if (stream == null) throw new IOException("ファイルを開けません");
                stream.write(settings.encode().getBytes(StandardCharsets.UTF_8));
            } catch (IOException | SecurityException e) {
                message("設定をエクスポートできませんでした");
                return;
            }
            message("設定をエクスポートしました");
        } else if (request == IMPORT) {
            try {
                Settings imported = readSettings(uri);
                new AlertDialog.Builder(this)
                        .setTitle("設定をインポート")
                        .setMessage("現在のURL整理設定を置き換えますか？")
                        .setNegativeButton("キャンセル", null)
                        .setPositiveButton("置き換える", (dialog, which) -> {
                            if (update(current -> imported)) render(scroll.getScrollY());
                        })
                        .show();
            } catch (IOException | IllegalArgumentException | SecurityException e) {
                message("設定をインポートできませんでした");
            }
        }
    }

    private Settings readSettings(Uri uri) throws IOException {
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IOException("ファイルを開けません");
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (data.size() + count > Mobile.MaxSettingsBytes) {
                    throw new IOException("設定ファイルが大きすぎます");
                }
                data.write(buffer, 0, count);
            }
            String json = StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(data.toByteArray())).toString();
            try {
                return Mobile.decode(json);
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }

    private void message(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
