package clipclean.android;

import android.content.Context;
import android.content.SharedPreferences;

import clipclean.bridge.mobile.Mobile;
import clipclean.bridge.mobile.Settings;

final class LocalSettings {
    private static final String KEY = "settings";
    private final SharedPreferences preferences;

    LocalSettings(Context context) {
        preferences = context.getSharedPreferences("clipclean", Context.MODE_PRIVATE);
    }

    Settings load() {
        String json = preferences.getString(KEY, null);
        if (json == null) return Mobile.defaults();
        try {
            return Mobile.decode(json);
        } catch (Exception e) {
            return Mobile.defaults();
        }
    }

    boolean save(Settings settings) {
        return preferences.edit().putString(KEY, settings.encode()).commit();
    }
}
