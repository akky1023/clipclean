package clipclean.android;

import android.app.Activity;
import android.content.Intent;

public final class ShareActivity extends Activity {
    private boolean processed;

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || processed) return;
        processed = true;

        Intent intent = getIntent();
        if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text == null) text = ClipboardActions.plainText(intent.getClipData());
            ClipboardActions.copySharedText(this, text, new LocalSettings(this).load());
        }
        finish();
    }
}
