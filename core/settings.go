package core

import (
	"bytes"
	"encoding/json"
	"fmt"
	"maps"
	"unicode/utf8"
)

const MaxSettingsBytes = 64 * 1024

// Settings holds URL cleaning preferences. Its zero value uses the defaults.
type Settings struct {
	EmbeddedURLs  bool
	removeUnknown [siteCount]bool
	remove        [siteCount]map[string]bool
}

func (s Settings) Removes(site Site, rule Rule) bool {
	if value, ok := s.remove[site][rule.ID]; ok {
		return value
	}
	return rule.RemoveByDefault
}

func (s Settings) RemovesUnknown(site Site) bool {
	return s.removeUnknown[site]
}

func (s Settings) WithRemoved(site Site, rule Rule, enabled bool) Settings {
	options := maps.Clone(s.remove[site])
	if options == nil {
		options = make(map[string]bool)
	}
	options[rule.ID] = enabled
	s.remove[site] = options
	return s
}

func (s Settings) WithRemovedUnknown(site Site, enabled bool) Settings {
	s.removeUnknown[site] = enabled
	return s
}

type settingsFile struct {
	Version      int                    `json:"version"`
	EmbeddedURLs bool                   `json:"embeddedUrls"`
	Sites        map[string]siteOptions `json:"sites"`
}

type siteOptions struct {
	RemoveUnknown bool            `json:"removeUnknown"`
	Remove        map[string]bool `json:"remove"`
}

func EncodeSettings(s Settings) []byte {
	file := settingsFile{1, s.EmbeddedURLs, make(map[string]siteOptions)}
	for index, site := range sites {
		rules := make(map[string]bool, len(site.rules))
		for _, rule := range site.rules {
			rules[rule.ID] = s.Removes(Site(index), rule)
		}
		file.Sites[site.id] = siteOptions{s.RemovesUnknown(Site(index)), rules}
	}
	data, _ := json.MarshalIndent(file, "", "  ") // Only maps, strings, booleans and integers.
	return append(data, '\n')
}

type jsonObject map[string]json.RawMessage

func decodeObject(data []byte, field string) (jsonObject, error) {
	var object jsonObject
	if json.Unmarshal(data, &object) != nil || object == nil {
		return nil, invalidField(field)
	}
	return object, nil
}

func readBool(object jsonObject, field string) (bool, error) {
	switch string(object[field]) {
	case "true":
		return true, nil
	case "false":
		return false, nil
	default:
		return false, invalidField(field)
	}
}

func invalidField(field string) error {
	return fmt.Errorf("設定項目が正しくありません（%s）", field)
}

func DecodeSettings(data []byte) (Settings, error) {
	var s Settings
	if len(data) > MaxSettingsBytes {
		return s, fmt.Errorf("設定ファイルが大きすぎます")
	}
	if !utf8.Valid(data) {
		return s, fmt.Errorf("設定ファイルをUTF-8として読み込めません")
	}
	root, err := decodeObject(bytes.TrimPrefix(data, []byte("\xef\xbb\xbf")), "JSON")
	if err != nil {
		return s, err
	}
	if string(root["version"]) != "1" {
		return s, fmt.Errorf("対応していない設定ファイルの形式です")
	}
	s.EmbeddedURLs, err = readBool(root, "embeddedUrls")
	if err != nil {
		return Settings{}, err
	}
	options, err := decodeObject(root["sites"], "sites")
	if err != nil {
		return Settings{}, err
	}
	for i, site := range sites {
		entry, err := decodeObject(options[site.id], site.id)
		if err != nil {
			return Settings{}, err
		}
		s.removeUnknown[i], err = readBool(entry, "removeUnknown")
		if err != nil {
			return Settings{}, err
		}
		rules, err := decodeObject(entry["remove"], "remove")
		if err != nil {
			return Settings{}, err
		}
		s.remove[i] = make(map[string]bool)
		for _, rule := range site.rules {
			if _, present := rules[rule.ID]; present {
				value, err := readBool(rules, rule.ID)
				if err != nil {
					return Settings{}, err
				}
				s.remove[i][rule.ID] = value
			}
		}
	}
	return s, nil
}
