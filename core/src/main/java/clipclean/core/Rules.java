package clipclean.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class Rules {
    public enum Site {
        X("x", "X"),
        INSTAGRAM("instagram", "Instagram"),
        YOUTUBE("youtube", "YouTube");

        private final String id;
        private final String label;

        Site(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public String id() { return id; }
        public String label() { return label; }
    }

    public static final class Rule {
        private final String id;
        private final String label;
        private final boolean removeByDefault;

        private Rule(String id, String label, boolean removeByDefault) {
            this.id = id;
            this.label = label;
            this.removeByDefault = removeByDefault;
        }

        public String id() { return id; }
        public String label() { return label; }
        public boolean removeByDefault() { return removeByDefault; }
    }

    private static final Rule UTM = new Rule("utm_*", "アクセス解析（utm_*）", true);
    private static final Rule FBCLID = new Rule("fbclid", "広告クリックID（fbclid）", true);
    private static final List<Rule> X_RULES = rules(
            UTM, FBCLID, new Rule("s", "投稿の共有情報（s）", true),
            new Rule("t", "投稿の共有情報（t）", true),
            new Rule("a", "共有リンクのパラメータ（a）", true),
            new Rule("ref_src", "参照元（ref_src）", true),
            new Rule("ref_url", "参照元URL（ref_url）", true));
    private static final List<Rule> INSTAGRAM_RULES = rules(
            UTM, FBCLID, new Rule("igsh", "共有情報（igsh）", true),
            new Rule("igshid", "共有情報（igshid）", true));
    private static final List<Rule> YOUTUBE_RULES = rules(
            UTM, FBCLID, new Rule("si", "共有情報（si）", true),
            new Rule("feature", "リンクの作成元（feature）", true),
            new Rule("t", "再生開始時刻（t）", false),
            new Rule("start", "再生開始時刻（start）", false),
            new Rule("time_continue", "再生開始時刻（time_continue）", false),
            new Rule("end", "再生終了時刻（end）", false),
            new Rule("list", "動画のプレイリスト指定（list）", false),
            new Rule("index", "プレイリスト内の位置（index）", false));

    private Rules() { }

    private static List<Rule> rules(Rule... rules) {
        return Collections.unmodifiableList(Arrays.asList(rules));
    }

    public static List<Rule> forSite(Site site) {
        switch (site) {
            case X: return X_RULES;
            case INSTAGRAM: return INSTAGRAM_RULES;
            case YOUTUBE: return YOUTUBE_RULES;
            default: throw new AssertionError(site);
        }
    }
}
