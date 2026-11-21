package core

import (
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"unicode"
)

var (
	link           = regexp.MustCompile(`(?i)https?://[^\s\p{Z}\p{Cc}<>"“”‘’。、，．！？；：「」『』【】（）]+`)
	invalidURIChar = regexp.MustCompile(`[\p{Z}\p{Cc}<>"{}|\\^\x60]`)
)

type Result struct {
	Text      string
	Supported bool
	Changed   bool
}

func isSpace(r rune) bool {
	return unicode.IsSpace(r) && r != '\u0085' || r >= 0x1c && r <= 0x1f
}

func Clean(text string, settings Settings) Result {
	if !settings.EmbeddedURLs {
		left := strings.TrimLeftFunc(text, isSpace)
		input := strings.TrimRightFunc(left, isSpace)
		cleaned, supported := cleanURL(input, settings)
		if !supported || cleaned == input {
			return Result{text, supported, false}
		}
		start := len(text) - len(left)
		return Result{text[:start] + cleaned + left[len(input):], true, true}
	}

	var output strings.Builder
	previous := 0
	supported := false
	for offset := 0; offset < len(text); {
		match := link.FindStringIndex(text[offset:])
		if match == nil {
			break
		}
		start, end := offset+match[0], offset+match[1]
		if start > 0 && isSchemeChar(text[start-1]) {
			offset = start + len("http://")
			continue
		}
		offset = end
		input := trimProsePunctuation(text[start:end])
		cleaned, ok := cleanURL(input, settings)
		supported = supported || ok
		if ok && cleaned != input {
			output.WriteString(text[previous:start])
			output.WriteString(cleaned)
			previous = start + len(input)
		}
	}
	if previous == 0 {
		return Result{text, supported, false}
	}
	output.WriteString(text[previous:])
	return Result{output.String(), true, true}
}

func isSchemeChar(c byte) bool {
	return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' ||
		c >= '0' && c <= '9' || strings.ContainsRune("+.-", rune(c))
}

func trimProsePunctuation(text string) string {
	parentheses := strings.Count(text, "(") - strings.Count(text, ")")
	brackets := strings.Count(text, "[") - strings.Count(text, "]")
	braces := strings.Count(text, "{") - strings.Count(text, "}")
	for len(text) > 0 {
		last := text[len(text)-1]
		switch {
		case last == ')' && parentheses < 0:
			parentheses++
		case last == ']' && brackets < 0:
			brackets++
		case last == '}' && braces < 0:
			braces++
		case strings.ContainsRune(".,;:!?'", rune(last)):
		default:
			return text
		}
		text = text[:len(text)-1]
	}
	return text
}

func cleanURL(input string, settings Settings) (string, bool) {
	if invalidURIChar.MatchString(input) {
		return "", false
	}
	uri, err := url.Parse(input)
	if err != nil || uri.User != nil || (uri.Scheme != "http" && uri.Scheme != "https") {
		return "", false
	}
	host := strings.ToLower(uri.Hostname())
	site, ok := siteFor(host)
	if !ok || strings.ContainsAny(uri.Host, "[]") {
		return "", false
	}
	if port := uri.Port(); port != "" {
		if _, err := strconv.ParseInt(port, 10, 32); err != nil {
			return "", false
		}
	}
	head, fragment, hasFragment := strings.Cut(input, "#")
	if strings.Contains(fragment, "#") || strings.ContainsAny(strings.SplitN(head, "?", 2)[0], "[]") {
		return "", false
	}
	query := uri.RawQuery
	// net/url validates percent escapes in the path and fragment only.
	if _, err := url.QueryUnescape(query); err != nil {
		return "", false
	}
	if query == "" {
		return input, true
	}

	entries := strings.Split(query, "&")
	kept := entries[:0]
	hasParameter := false
	for _, entry := range entries {
		key, _, _ := strings.Cut(entry, "=")
		name, _ := url.QueryUnescape(key)
		if !shouldRemove(site, host, uri.Path, name, settings) {
			kept = append(kept, entry)
			hasParameter = hasParameter || entry != ""
		}
	}
	if len(kept) == len(entries) {
		return input, true
	}

	output := head[:strings.IndexByte(head, '?')]
	if hasParameter {
		output += "?" + strings.Join(kept, "&")
	}
	if hasFragment {
		output += "#" + fragment
	}
	return output, true
}

func shouldRemove(site Site, host, path, name string, settings Settings) bool {
	if name == "" {
		return false
	}
	if site == YouTube {
		if name == "v" ||
			name == "list" && (path == "/playlist" || path == "/playlist/" || path == "/embed/videoseries") ||
			name == "search_query" && path == "/results" {
			return false
		}
	}
	if site == X && name == "q" && path == "/search" {
		return false
	}

	for _, rule := range sites[site].rules {
		if rule.ID == "utm_*" && strings.HasPrefix(strings.ToLower(name), "utm_") {
			return settings.Removes(site, rule)
		}
		if rule.ID == name {
			// The shortener may point anywhere. X post metadata applies to X URLs only.
			if host == "t.co" && (name == "s" || name == "t") {
				break
			}
			return settings.Removes(site, rule)
		}
	}
	return settings.RemovesUnknown(site)
}

func siteFor(host string) (Site, bool) {
	switch host {
	case "x.com", "www.x.com", "mobile.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com", "t.co":
		return X, true
	case "instagram.com", "www.instagram.com", "m.instagram.com", "instagr.am", "www.instagr.am":
		return Instagram, true
	case "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be":
		return YouTube, true
	default:
		return 0, false
	}
}
