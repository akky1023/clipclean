package core

type Site int

const (
	X Site = iota
	Instagram
	YouTube
	siteCount
)

type Rule struct {
	ID              string
	Label           string
	RemoveByDefault bool
}

type siteDefinition struct {
	id    string
	label string
	rules []Rule
}

var (
	utm    = Rule{"utm_*", "アクセス解析（utm_*）", true}
	fbclid = Rule{"fbclid", "広告クリックID（fbclid）", true}
	sites  = [siteCount]siteDefinition{
		{"x", "X", []Rule{
			utm, fbclid,
			{"s", "投稿の共有情報（s）", true},
			{"t", "投稿の共有情報（t）", true},
			{"a", "共有リンクのパラメータ（a）", true},
			{"ref_src", "参照元（ref_src）", true},
			{"ref_url", "参照元URL（ref_url）", true},
		}},
		{"instagram", "Instagram", []Rule{
			utm, fbclid,
			{"igsh", "共有情報（igsh）", true},
			{"igshid", "共有情報（igshid）", true},
		}},
		{"youtube", "YouTube", []Rule{
			utm, fbclid,
			{"si", "共有情報（si）", true},
			{"feature", "リンクの作成元（feature）", true},
			{"t", "再生開始時刻（t）", false},
			{"start", "再生開始時刻（start）", false},
			{"time_continue", "再生開始時刻（time_continue）", false},
			{"end", "再生終了時刻（end）", false},
			{"list", "動画のプレイリスト指定（list）", false},
			{"index", "プレイリスト内の位置（index）", false},
		}},
	}
)

func SiteCount() int         { return len(sites) }
func (s Site) Label() string { return sites[s].label }
func (s Site) Rules() []Rule { return append([]Rule(nil), sites[s].rules...) }
