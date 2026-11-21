package clipclean.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;

import clipclean.core.Rules.Rule;
import clipclean.core.Rules.Site;

public final class SettingsJson {
    public static final int MAX_BYTES = 64 * 1024;
    private static final Gson GSON = new GsonBuilder()
            .setStrictness(Strictness.STRICT).setPrettyPrinting().create();

    private SettingsJson() { }

    public static String encode(Settings settings) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("embeddedUrls", settings.embeddedUrls());
        JsonObject sites = new JsonObject();
        for (Site site : Site.values()) {
            JsonObject options = new JsonObject();
            options.addProperty("removeUnknown", settings.removesUnknown(site));
            JsonObject rules = new JsonObject();
            for (Rule rule : Rules.forSite(site)) {
                rules.addProperty(rule.id(), settings.removes(site, rule));
            }
            options.add("remove", rules);
            sites.add(site.id(), options);
        }
        root.add("sites", sites);
        return GSON.toJson(root) + "\n";
    }

    public static Settings decode(String json) {
        if (json.length() > MAX_BYTES) throw new IllegalArgumentException("設定ファイルが大きすぎます");
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) throw new IllegalArgumentException("設定ファイルが空です");
            JsonElement version = root.get("version");
            if (version == null || !version.isJsonPrimitive()
                    || !version.getAsJsonPrimitive().isNumber() || !version.getAsString().equals("1")) {
                throw new IllegalArgumentException("対応していない設定ファイルの形式です");
            }
            Settings settings = Settings.defaults().withEmbeddedUrls(readBoolean(root, "embeddedUrls"));
            JsonObject sites = object(root, "sites");
            for (Site site : Site.values()) {
                JsonObject options = object(sites, site.id());
                settings = settings.withRemovedUnknown(site, readBoolean(options, "removeUnknown"));
                JsonObject rules = object(options, "remove");
                for (Rule rule : Rules.forSite(site)) {
                    // New rules can use their defaults when reading files from older builds.
                    if (rules.has(rule.id())) {
                        settings = settings.withRemoved(site, rule, readBoolean(rules, rule.id()));
                    }
                }
            }
            return settings;
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("設定ファイルのJSONが正しくありません", e);
        }
    }

    private static JsonObject object(JsonObject parent, String field) {
        JsonElement value = parent.get(field);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("設定項目が正しくありません（" + field + "）");
        }
        return value.getAsJsonObject();
    }

    private static boolean readBoolean(JsonObject parent, String field) {
        JsonElement value = parent.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("設定項目が正しくありません（" + field + "）");
        }
        return value.getAsBoolean();
    }
}
