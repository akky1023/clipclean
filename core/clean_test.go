package core

import (
	"bytes"
	"strings"
	"testing"
)

func TestClean(t *testing.T) {
	defaults := Settings{}
	embedded := Settings{EmbeddedURLs: true}
	custom := embedded.WithRemovedUnknown(YouTube, true)
	for _, rule := range YouTube.Rules() {
		if rule.ID == "list" || rule.ID == "t" {
			custom = custom.WithRemoved(YouTube, rule, true)
		}
	}
	for _, rule := range Instagram.Rules() {
		if rule.ID == "igsh" {
			custom = custom.WithRemoved(Instagram, rule, false)
		}
	}
	cases := []struct {
		settings    Settings
		input, want string
	}{
		{defaults, "https://x.com/u/status/1?s=20&t=abc&a=share", "https://x.com/u/status/1"},
		{defaults, "http://mobile.twitter.com/u/status/1?ref_src=old&a=", "http://mobile.twitter.com/u/status/1"},
		{defaults, "https://t.co/a?utm_source=x&a=share&destination=anything", "https://t.co/a?destination=anything"},
		{defaults, "https://t.co/a?s=link&t=link", "https://t.co/a?s=link&t=link"},
		{defaults, "https://t.co/a", "https://t.co/a"},
		{defaults, "https://www.instagram.com/reel/abc/?igsh=123#fragment", "https://www.instagram.com/reel/abc/#fragment"},
		{defaults, "https://www.instagram.com/share/reel/xyz/?igsh=abc", "https://www.instagram.com/share/reel/xyz/"},
		{defaults, "https://instagr.am/p/abc/?igshid=123", "https://instagr.am/p/abc/"},
		{defaults, "https://youtu.be/abc?si=x&t=45&list=PL1&index=2", "https://youtu.be/abc?t=45&list=PL1&index=2"},
		{defaults, "https://www.youtube.com/watch?v=abc&si=x#t=10", "https://www.youtube.com/watch?v=abc#t=10"},
		{defaults, " https://www.youtube.com/watch?%76=abc&q=A%20B&si=x&q=A+B\t", " https://www.youtube.com/watch?%76=abc&q=A%20B&q=A+B\t"},
		{defaults, "https://www.youtube.com/watch?v=abc&%73i=a&si=b", "https://www.youtube.com/watch?v=abc"},
		{defaults, "https://youtu.be/abc?a=unknown", "https://youtu.be/abc?a=unknown"},
		{defaults, "HTTPS://YouTu.Be:443/あ%2Fabc?&UTM_id=1&v=xyz&&#", "HTTPS://YouTu.Be:443/あ%2Fabc?&v=xyz&&#"},
		{defaults, "https://youtu.be/abc?&si=x&&#", "https://youtu.be/abc#"},
		{embedded, "タイトル（https://youtu.be/abc?si=x&t=3）。\n(https://x.com/u/status/1?s=20), https://example.org/?s=20",
			"タイトル（https://youtu.be/abc?t=3）。\n(https://x.com/u/status/1), https://example.org/?s=20"},
		{embedded, "[https://youtu.be/abc?t=1&si=x] とリンク。", "[https://youtu.be/abc?t=1] とリンク。"},
		{embedded, "xhttps://example.org(https://youtu.be/abc?si=x)", "xhttps://example.org(https://youtu.be/abc)"},
		{custom, "https://youtube.com/watch?v=abc&other=1&t=3&list=PL1&si=x", "https://youtube.com/watch?v=abc"},
		{custom, "https://youtube.com/playlist?list=PL1&other=1", "https://youtube.com/playlist?list=PL1"},
		{custom, "https://youtube.com/embed/videoseries?list=PL1&other=1", "https://youtube.com/embed/videoseries?list=PL1"},
		{custom, "https://youtube.com/results?search_query=abc&other=1", "https://youtube.com/results?search_query=abc"},
		{custom, "https://instagram.com/p/abc/?igsh=x&other=1", "https://instagram.com/p/abc/?igsh=x&other=1"},
	}

	for _, tc := range cases {
		got := Clean(tc.input, tc.settings)
		want := Result{tc.want, true, tc.input != tc.want}
		if got != want {
			t.Errorf("Clean(%q) = %#v, want %#v", tc.input, got, want)
		}
		if Clean(got.Text, tc.settings).Changed {
			t.Errorf("cleaning twice changed %q", got.Text)
		}
	}
}

func TestUnsupportedURLs(t *testing.T) {
	for _, input := range []string{
		"文章 https://youtu.be/abc?si=123",
		"https://youtu.be/abc\nhttps://x.com/u/status/1?s=20",
		"https://youtu.be.example/abc?si=123",
		"https://youtu.be@example.com/abc?si=123",
		"https://person@youtu.be/abc?si=123",
		"https://youtu.be/abc?si=%x",
		"https://youtu.be/abc?si=x&v=%x",
		"https://youtu.be/abc?si=x#%",
		"https://[youtu.be]/abc?si=123",
		"https://youtu.be/abc?si=short text",
		"mailto:person@example.com",
	} {
		if got := Clean(input, Settings{}); got != (Result{Text: input}) {
			t.Errorf("Clean(%q) = %#v, want unsupported", input, got)
		}
	}
	input := "xhttps://youtu.be/abc?si=123"
	if got := Clean(input, Settings{EmbeddedURLs: true}); got != (Result{Text: input}) {
		t.Errorf("embedded Clean(%q) = %#v, want unsupported", input, got)
	}
}

func TestSettingsCompatibility(t *testing.T) {
	// A settings file saved before X's "a" rule was added.
	legacy := []byte(`{
	  "version": 1,
	  "embeddedUrls": true,
	  "sites": {
	    "x": { "removeUnknown": false, "remove": { "s": false } },
	    "instagram": { "removeUnknown": true, "remove": { "igsh": false } },
	    "youtube": { "removeUnknown": false, "remove": { "t": true } }
	  }
	}`)
	settings, err := DecodeSettings(legacy)
	if err != nil {
		t.Fatal(err)
	}
	input := "投稿 https://x.com/u/status/1?a=share&s=20"
	if got := Clean(input, settings).Text; got != "投稿 https://x.com/u/status/1?s=20" {
		t.Errorf("legacy settings cleaned to %q", got)
	}

	encoded := EncodeSettings(settings)
	imported, err := DecodeSettings(encoded)
	if err != nil || !bytes.Equal(EncodeSettings(imported), encoded) {
		t.Fatalf("settings round trip: %v", err)
	}
	for _, input := range []string{
		strings.ReplaceAll(string(encoded), `"version": 1`, `"version": 2`),
		strings.ReplaceAll(string(encoded), `"version": 1`, `"version": 1.0`),
		strings.ReplaceAll(string(encoded), `"embeddedUrls": true`, `"embeddedUrls": "true"`),
		strings.ReplaceAll(string(encoded), `"embeddedUrls": true`, `"embeddedUrls": null`),
		string(encoded) + " {}",
		"{ broken",
		"\xff",
		strings.Repeat(" ", MaxSettingsBytes) + string(encoded),
	} {
		if _, err := DecodeSettings([]byte(input)); err == nil {
			t.Error("invalid settings accepted")
		}
	}
}
