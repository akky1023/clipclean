package clipclean.core;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import clipclean.core.Rules.Rule;
import clipclean.core.Rules.Site;

public final class UrlCleaner {
    private static final Pattern LINK = Pattern.compile(
            "(?<![a-z0-9+.-])https?://[^\\s\\p{Z}\\p{Cc}<>\"“”‘’。、，．！？；：「」『』【】（）]+",
            Pattern.CASE_INSENSITIVE);

    private UrlCleaner() { }

    public static final class Result {
        private final String text;
        private final boolean supported;
        private final boolean changed;

        private Result(String text, boolean supported, boolean changed) {
            this.text = text;
            this.supported = supported;
            this.changed = changed;
        }

        public String text() { return text; }
        public boolean supported() { return supported; }
        public boolean changed() { return changed; }
    }

    public static Result clean(String text, Settings settings) {
        if (!settings.embeddedUrls()) {
            int start = 0;
            int end = text.length();
            while (start < end && isSpace(text.charAt(start))) start++;
            while (end > start && isSpace(text.charAt(end - 1))) end--;
            String url = text.substring(start, end);
            String cleaned = cleanUrl(url, settings);
            if (cleaned == null) return new Result(text, false, false);
            if (cleaned.equals(url)) return new Result(text, true, false);
            return new Result(text.substring(0, start) + cleaned + text.substring(end), true, true);
        }

        Matcher matcher = LINK.matcher(text);
        StringBuilder output = new StringBuilder(text.length());
        int previous = 0;
        boolean supported = false;
        boolean changed = false;
        while (matcher.find()) {
            int end = trimProsePunctuation(text, matcher.start(), matcher.end());
            String url = text.substring(matcher.start(), end);
            String cleaned = cleanUrl(url, settings);
            if (cleaned == null) continue;
            supported = true;
            if (!url.equals(cleaned)) {
                output.append(text, previous, matcher.start()).append(cleaned);
                previous = end;
                changed = true;
            }
        }
        return new Result(changed ? output.append(text, previous, text.length()).toString() : text,
                supported, changed);
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    private static int trimProsePunctuation(String text, int start, int end) {
        int parentheses = 0;
        int brackets = 0;
        int braces = 0;
        for (int i = start; i < end; i++) {
            switch (text.charAt(i)) {
                case '(': parentheses++; break;
                case ')': parentheses--; break;
                case '[': brackets++; break;
                case ']': brackets--; break;
                case '{': braces++; break;
                case '}': braces--; break;
                default: break;
            }
        }
        while (end > start) {
            char last = text.charAt(end - 1);
            if (last == ')' && parentheses < 0) parentheses++;
            else if (last == ']' && brackets < 0) brackets++;
            else if (last == '}' && braces < 0) braces++;
            else if (".,;:!?\'".indexOf(last) < 0) break;
            end--;
        }
        return end;
    }

    private static String cleanUrl(String input, Settings settings) {
        URI uri;
        try {
            uri = new URI(input);
        } catch (URISyntaxException e) {
            return null;
        }
        if (uri.getHost() == null || uri.getUserInfo() != null
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
            return null;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        Site site = siteFor(host);
        if (site == null) return null;
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) return input;

        List<String> kept = new ArrayList<>();
        boolean removed = false;
        boolean hasNonEmptyParameter = false;
        for (String entry : query.split("&", -1)) {
            int equals = entry.indexOf('=');
            String name = decodeName(equals < 0 ? entry : entry.substring(0, equals));
            if (shouldRemove(site, host, uri.getPath(), name, settings)) {
                removed = true;
            } else {
                kept.add(entry);
                hasNonEmptyParameter |= !entry.isEmpty();
            }
        }
        if (!removed) return input;

        int question = input.indexOf('?');
        String fragment = uri.getRawFragment();
        return input.substring(0, question)
                + (hasNonEmptyParameter ? "?" + String.join("&", kept) : "")
                + (fragment == null ? "" : "#" + fragment);
    }

    private static String decodeName(String encoded) {
        try {
            return URLDecoder.decode(encoded, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean shouldRemove(Site site, String host, String path, String name, Settings settings) {
        if (name.isEmpty()) return false;
        if (site == Site.YOUTUBE) {
            if (name.equals("v")) return false;
            if (name.equals("list") && (path.equals("/playlist") || path.equals("/playlist/")
                    || path.equals("/embed/videoseries"))) return false;
            if (name.equals("search_query") && path.equals("/results")) return false;
        }
        if (site == Site.X && name.equals("q") && path.equals("/search")) return false;

        for (Rule rule : Rules.forSite(site)) {
            if (rule.id().equals("utm_*") && name.toLowerCase(Locale.ROOT).startsWith("utm_")) {
                return settings.removes(site, rule);
            }
            if (rule.id().equals(name)) {
                // The shortener may point anywhere. X post metadata applies to X URLs only.
                if (host.equals("t.co") && (name.equals("s") || name.equals("t"))) break;
                return settings.removes(site, rule);
            }
        }
        return settings.removesUnknown(site);
    }

    private static Site siteFor(String host) {
        switch (host) {
            case "x.com": case "www.x.com": case "mobile.x.com":
            case "twitter.com": case "www.twitter.com": case "mobile.twitter.com": case "t.co":
                return Site.X;
            case "instagram.com": case "www.instagram.com": case "m.instagram.com":
            case "instagr.am": case "www.instagr.am":
                return Site.INSTAGRAM;
            case "youtube.com": case "www.youtube.com": case "m.youtube.com":
            case "music.youtube.com": case "youtu.be":
                return Site.YOUTUBE;
            default:
                return null;
        }
    }
}
