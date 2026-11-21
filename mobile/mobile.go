package mobile

import "net.neomenia/clipclean/core"

const (
	MaxSettingsBytes = core.MaxSettingsBytes
	YouTubeSite      = int32(core.YouTube)
)

// Settings exposes the shared preferences through types supported by gomobile.
type Settings struct {
	value core.Settings
}

func Defaults() *Settings { return &Settings{} }

func Decode(json string) (*Settings, error) {
	value, err := core.DecodeSettings([]byte(json))
	if err != nil {
		return nil, err
	}
	return &Settings{value}, nil
}

func (s *Settings) Encode() string {
	return string(core.EncodeSettings(s.value))
}

func SiteCount() int32 { return int32(core.SiteCount()) }

func SiteLabel(site int32) string { return core.Site(site).Label() }

func RuleCount(site int32) int32 { return int32(len(core.Site(site).Rules())) }

func RuleLabel(site, rule int32) string { return core.Site(site).Rules()[rule].Label }

func (s *Settings) EmbeddedUrls() bool { return s.value.EmbeddedURLs }

func (s *Settings) Removes(site, rule int32) bool {
	id := core.Site(site)
	return s.value.Removes(id, id.Rules()[rule])
}

func (s *Settings) RemovesUnknown(site int32) bool {
	return s.value.RemovesUnknown(core.Site(site))
}

func (s *Settings) WithEmbeddedUrls(enabled bool) *Settings {
	next := s.value
	next.EmbeddedURLs = enabled
	return &Settings{next}
}

func (s *Settings) WithRemoved(site, rule int32, enabled bool) *Settings {
	id := core.Site(site)
	return &Settings{s.value.WithRemoved(id, id.Rules()[rule], enabled)}
}

func (s *Settings) WithRemovedUnknown(site int32, enabled bool) *Settings {
	return &Settings{s.value.WithRemovedUnknown(core.Site(site), enabled)}
}

type Result struct {
	value core.Result
}

func (r *Result) Text() string { return r.value.Text }

func (r *Result) Supported() bool { return r.value.Supported }

func (r *Result) Changed() bool { return r.value.Changed }

func Clean(text string, settings *Settings) *Result {
	return &Result{core.Clean(text, settings.value)}
}
