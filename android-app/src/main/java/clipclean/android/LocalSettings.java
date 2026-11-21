package clipclean.android;

import android.content.Context;
import android.content.SharedPreferences;

import clipclean.core.Settings;
import clipclean.core.SettingsJson;

final class LocalSettings {
    private static final String KEY = "settings";
    private final SharedPreferences preferences;

    LocalSettings(Context context) {
        preferences = context.getSharedPreferences("clipclean", Context.MODE_PRIVATE);
    }

    Settings load() {
        String json = preferences.getString(KEY, null);
        if (json == null) return Settings.defaults();
        try {
            return SettingsJson.decode(json);
        } catch (IllegalArgumentException e) {
            return Settings.defaults();
        }
    }

    boolean save(Settings settings) {
        return preferences.edit().putString(KEY, SettingsJson.encode(settings)).commit();
    }
}
