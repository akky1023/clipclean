package clipclean.core;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Set;

import clipclean.core.Rules.Rule;
import clipclean.core.Rules.Site;

public final class Settings {
    private final boolean embeddedUrls;
    private final EnumMap<Site, Set<String>> removedRules;
    private final EnumMap<Site, Boolean> removeUnknown;

    private Settings(boolean embeddedUrls, EnumMap<Site, Set<String>> removedRules,
                     EnumMap<Site, Boolean> removeUnknown) {
        this.embeddedUrls = embeddedUrls;
        this.removedRules = removedRules;
        this.removeUnknown = removeUnknown;
    }

    public static Settings defaults() {
        EnumMap<Site, Set<String>> removedRules = new EnumMap<>(Site.class);
        EnumMap<Site, Boolean> removeUnknown = new EnumMap<>(Site.class);
        for (Site site : Site.values()) {
            Set<String> rules = new LinkedHashSet<>();
            for (Rule rule : Rules.forSite(site)) {
                if (rule.removeByDefault()) rules.add(rule.id());
            }
            removedRules.put(site, Collections.unmodifiableSet(rules));
            removeUnknown.put(site, false);
        }
        return new Settings(false, removedRules, removeUnknown);
    }

    public boolean embeddedUrls() { return embeddedUrls; }
    public boolean removes(Site site, Rule rule) { return removedRules.get(site).contains(rule.id()); }
    public boolean removesUnknown(Site site) { return removeUnknown.get(site); }

    public Settings withEmbeddedUrls(boolean enable) {
        return new Settings(enable, removedRules, removeUnknown);
    }

    public Settings withRemoved(Site site, Rule rule, boolean remove) {
        if (!Rules.forSite(site).contains(rule)) throw new IllegalArgumentException("Rule does not apply to site");
        EnumMap<Site, Set<String>> nextRules = new EnumMap<>(removedRules);
        Set<String> rules = new LinkedHashSet<>(removedRules.get(site));
        if (remove) rules.add(rule.id());
        else rules.remove(rule.id());
        nextRules.put(site, Collections.unmodifiableSet(rules));
        return new Settings(embeddedUrls, nextRules, removeUnknown);
    }

    public Settings withRemovedUnknown(Site site, boolean remove) {
        EnumMap<Site, Boolean> nextUnknown = new EnumMap<>(removeUnknown);
        nextUnknown.put(site, remove);
        return new Settings(embeddedUrls, removedRules, nextUnknown);
    }
}
