package clipclean.core;

import clipclean.core.Rules.Rule;
import clipclean.core.Rules.Site;

public final class CoreChecks {
    public static void main(String[] args) {
        Settings defaults = Settings.defaults();
        check(defaults, "https://x.com/u/status/1?s=20&t=abc&a=share", "https://x.com/u/status/1");
        check(defaults, "http://mobile.twitter.com/u/status/1?ref_src=old&a=", "http://mobile.twitter.com/u/status/1");
        check(defaults, "https://t.co/a?utm_source=x&a=share&destination=anything", "https://t.co/a?destination=anything");
        check(defaults, "https://t.co/a", "https://t.co/a");
        check(defaults, "https://www.instagram.com/reel/abc/?igsh=123#fragment", "https://www.instagram.com/reel/abc/#fragment");
        check(defaults, "https://www.instagram.com/share/reel/xyz/?igsh=abc", "https://www.instagram.com/share/reel/xyz/");
        check(defaults, "https://instagr.am/p/abc/?igshid=123", "https://instagr.am/p/abc/");
        check(defaults, "https://youtu.be/abc?si=x&t=45&list=PL1&index=2", "https://youtu.be/abc?t=45&list=PL1&index=2");
        check(defaults, "https://www.youtube.com/watch?v=abc&si=x#t=10", "https://www.youtube.com/watch?v=abc#t=10");
        check(defaults, " https://www.youtube.com/watch?%76=abc&q=A%20B&si=x&q=A+B\t",
                " https://www.youtube.com/watch?%76=abc&q=A%20B&q=A+B\t");
        check(defaults, "https://www.youtube.com/watch?v=abc&%73i=a&si=b", "https://www.youtube.com/watch?v=abc");
        check(defaults, "https://youtu.be/abc?a=unknown", "https://youtu.be/abc?a=unknown");

        unsupported(defaults, "文章 https://youtu.be/abc?si=123");
        unsupported(defaults, "https://youtu.be/abc\nhttps://x.com/u/status/1?s=20");
        unsupported(defaults, "https://youtu.be.example/abc?si=123");
        unsupported(defaults, "https://youtu.be@example.com/abc?si=123");
        unsupported(defaults, "https://person@youtu.be/abc?si=123");
        unsupported(defaults, "https://youtu.be/abc?si=%x");
        unsupported(defaults, "mailto:person@example.com");

        Settings embedded = defaults.withEmbeddedUrls(true);
        check(embedded, "タイトル（https://youtu.be/abc?si=x&t=3）。\n"
                        + "(https://x.com/u/status/1?s=20), https://example.org/?s=20",
                "タイトル（https://youtu.be/abc?t=3）。\n(https://x.com/u/status/1), https://example.org/?s=20");
        check(embedded, "[https://youtu.be/abc?t=1&si=x] とリンク。",
                "[https://youtu.be/abc?t=1] とリンク。");

        Settings custom = embedded.withRemovedUnknown(Site.YOUTUBE, true)
                .withRemoved(Site.YOUTUBE, rule(Site.YOUTUBE, "t"), true)
                .withRemoved(Site.YOUTUBE, rule(Site.YOUTUBE, "list"), true)
                .withRemoved(Site.INSTAGRAM, rule(Site.INSTAGRAM, "igsh"), false);
        check(custom, "https://youtube.com/watch?v=abc&other=1&t=3&list=PL1&si=x",
                "https://youtube.com/watch?v=abc");
        check(custom, "https://youtube.com/playlist?list=PL1&other=1", "https://youtube.com/playlist?list=PL1");
        check(custom, "https://youtube.com/embed/videoseries?list=PL1&other=1",
                "https://youtube.com/embed/videoseries?list=PL1");
        check(custom, "https://instagram.com/p/abc/?igsh=x&other=1",
                "https://instagram.com/p/abc/?igsh=x&other=1");

        Settings oldSettings = SettingsJson.decode(SettingsJson.encode(custom).replace("\"a\": true,", ""));
        check(oldSettings, "https://x.com/u/status/1?a=share", "https://x.com/u/status/1");
        custom = custom.withRemoved(Site.X, rule(Site.X, "a"), false);
        String encoded = SettingsJson.encode(custom);
        Settings imported = SettingsJson.decode(encoded);
        require(SettingsJson.encode(imported).equals(encoded), "settings round trip");
        check(imported, "https://x.com/u/status/1?a=share&s=20", "https://x.com/u/status/1?a=share");
        invalid(encoded.replace("\"version\": 1", "\"version\": 2"));
        invalid(encoded.replace("\"embeddedUrls\": true", "\"embeddedUrls\": \"true\""));
        invalid(encoded + " {}");
        invalid("{ broken");
        System.out.println("URL and settings checks passed");
    }

    private static Rule rule(Site site, String id) {
        for (Rule rule : Rules.forSite(site)) {
            if (rule.id().equals(id)) return rule;
        }
        throw new AssertionError(id);
    }

    private static void check(Settings settings, String input, String expected) {
        UrlCleaner.Result result = UrlCleaner.clean(input, settings);
        require(result.supported(), "supported: " + input);
        require(result.text().equals(expected), "expected " + expected + ", got " + result.text());
        require(result.changed() == !input.equals(expected), "change flag: " + input);
        require(!UrlCleaner.clean(result.text(), settings).changed(), "idempotence: " + input);
    }

    private static void unsupported(Settings settings, String input) {
        UrlCleaner.Result result = UrlCleaner.clean(input, settings);
        require(!result.supported() && !result.changed() && result.text().equals(input), "unsupported: " + input);
    }

    private static void invalid(String json) {
        try {
            SettingsJson.decode(json);
            throw new AssertionError("Invalid settings accepted");
        } catch (IllegalArgumentException expected) {
            // The current settings can remain in use when validation fails.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
