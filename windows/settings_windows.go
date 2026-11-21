package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
	"net.neomenia/clipclean/core"
)

const (
	idEmbedded    = 200
	idStartup     = 201
	idExport      = 202
	idImport      = 203
	idUnknown     = 204
	firstSite     = 210
	firstRule     = 220
	wmStartupDone = 0x8002
)

type rect struct{ left, top, right, bottom int32 }

type settingsUI struct {
	dpi             int32
	width, height   int32
	font            uintptr
	ready           bool
	site            core.Site
	siteButtons     []uintptr
	siteControls    []uintptr
	embedded, login uintptr
	startupResult   chan error
}

func (ui *settingsUI) scale(px int32) uintptr { return uintptr((px*ui.dpi + 48) / 96) }

func (ui *settingsUI) measure(style uintptr) {
	dc, _, _ := user32.NewProc("GetDC").Call(0)
	dpi, _, _ := windows.NewLazySystemDLL("gdi32.dll").NewProc("GetDeviceCaps").Call(dc, 88)
	user32.NewProc("ReleaseDC").Call(0, dc)
	ui.dpi = int32(dpi)
	if ui.dpi <= 0 {
		ui.dpi = 96
	}
	bounds := rect{right: int32(ui.scale(440)), bottom: int32(ui.scale(486))}
	user32.NewProc("AdjustWindowRectEx").Call(uintptr(unsafe.Pointer(&bounds)), style, 0, 0)
	ui.width = bounds.right - bounds.left
	ui.height = bounds.bottom - bounds.top
}

func (app *application) showSettings() {
	if !app.ui.ready {
		// DEFAULT_GUI_FONT uses the Windows UI font and its fallback fonts.
		app.ui.font, _, _ = windows.NewLazySystemDLL("gdi32.dll").NewProc("GetStockObject").Call(17)
		app.buildSettings()
		app.ui.ready = true
	}
	showWindow.Call(app.window, 9) // SW_RESTORE
	setForegroundWindow.Call(app.window)
}

func (app *application) control(class, label string, style uintptr, id int, x, y, width, height int32) uintptr {
	s := app.ui.scale
	child, _, _ := createWindow.Call(0, uintptr(unsafe.Pointer(wide(class))), uintptr(unsafe.Pointer(wide(label))),
		0x50000000|style, s(x), s(y), s(width), s(height), app.window, uintptr(id), app.instance, 0)
	sendMessage.Call(child, 0x0030, app.ui.font, 1) // WM_SETFONT
	return child
}

func (app *application) buildSettings() {
	ui := &app.ui
	const checkbox = 0x10003 // WS_TABSTOP | BS_AUTOCHECKBOX
	ui.embedded = app.control("BUTTON", "文章や複数行に含まれるURLも整理する", checkbox, idEmbedded, 16, 12, 405, 24)
	ui.login = app.control("BUTTON", "Windows ログイン時に自動起動", checkbox, idStartup, 16, 38, 405, 24)
	for i := 0; i < core.SiteCount(); i++ {
		site := core.Site(i)
		ui.siteButtons = append(ui.siteButtons, app.control("BUTTON", site.Label(),
			0x11002, firstSite+i, 16+int32(i)*138, 73, 132, 30)) // BS_PUSHLIKE | WS_TABSTOP | BS_CHECKBOX
	}
	app.control("STATIC", "削除するパラメータ", 0, 0, 16, 114, 400, 20)
	app.control("BUTTON", "エクスポート", 0x10000, idExport, 112, 447, 102, 28)
	app.control("BUTTON", "インポート", 0x10000, idImport, 226, 447, 102, 28)
	app.refreshSettings()
}

func (app *application) refreshSettings() {
	check(app.ui.embedded, app.settings.EmbeddedURLs)
	app.refreshStartupCheckbox()
	app.showSite(app.ui.site)
}

func (app *application) showSite(site core.Site) {
	ui := &app.ui
	ui.site = site
	for i, button := range ui.siteButtons {
		check(button, i == int(site))
	}
	for _, control := range ui.siteControls {
		destroyWindow.Call(control)
	}
	ui.siteControls = ui.siteControls[:0]
	var y int32 = 136
	for i, rule := range site.Rules() {
		child := app.control("BUTTON", rule.Label, 0x10003, firstRule+i, 24, y, 390, 24)
		check(child, app.settings.Removes(site, rule))
		ui.siteControls = append(ui.siteControls, child)
		y += 24
	}
	unknown := app.control("BUTTON", "リストにないパラメータも削除する", 0x10003, idUnknown, 24, y, 390, 24)
	check(unknown, app.settings.RemovesUnknown(site))
	ui.siteControls = append(ui.siteControls, unknown)
	if site == core.YouTube {
		ui.siteControls = append(ui.siteControls,
			app.control("STATIC", "動画IDとプレイリスト自体のIDは常に保持", 0, 0, 24, y+28, 390, 24))
	}
}

func check(button uintptr, checked bool) {
	sendMessage.Call(button, 0x00f1, boolArg(checked), 0) // BM_SETCHECK
}

func boolArg(value bool) uintptr {
	if value {
		return 1
	}
	return 0
}

func (app *application) settingsCommand(id, notification int, control uintptr) {
	if notification != 0 || control == 0 {
		return
	}
	selected, _, _ := sendMessage.Call(control, 0x00f0, 0, 0) // BM_GETCHECK
	enabled := selected == 1
	site := app.ui.site
	next := app.settings
	switch {
	case id == idEmbedded:
		next.EmbeddedURLs = enabled
	case id == idStartup:
		app.changeStartup(enabled)
		return
	case id == idImport || id == idExport:
		app.transferSettings(id == idExport)
		return
	case id >= firstSite && id < firstSite+core.SiteCount():
		app.showSite(core.Site(id - firstSite))
		return
	case id == idUnknown:
		next = next.WithRemovedUnknown(site, enabled)
	case id >= firstRule && id < firstRule+len(site.Rules()):
		next = next.WithRemoved(site, site.Rules()[id-firstRule], enabled)
	default:
		return
	}
	if err := app.saveSettings(next); err != nil {
		check(control, !enabled)
		errorBox(app.window, "設定を保存できませんでした。", err)
	}
}

func (app *application) saveSettings(next core.Settings) error {
	if err := writeSettings(app.settingsFile, next); err != nil {
		return err
	}
	app.settings = next
	return nil
}

func readSettings(path string) (core.Settings, error) {
	file, err := os.Open(path)
	if err != nil {
		return core.Settings{}, err
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, core.MaxSettingsBytes+1))
	if err != nil {
		return core.Settings{}, err
	}
	return core.DecodeSettings(data)
}

func writeSettings(path string, settings core.Settings) error {
	file, err := os.CreateTemp(filepath.Dir(path), ".clipclean-*.json")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	_, writeErr := file.Write(core.EncodeSettings(settings))
	if err := errors.Join(writeErr, file.Close()); err != nil {
		return err
	}
	return windows.MoveFileEx(wide(file.Name()), wide(path), windows.MOVEFILE_REPLACE_EXISTING|windows.MOVEFILE_WRITE_THROUGH)
}

type openFileName struct {
	size                         uint32
	owner, instance              uintptr
	filter, customFilter         *uint16
	maxCustomFilter, filterIndex uint32
	file                         *uint16
	maxFile                      uint32
	fileTitle                    *uint16
	maxFileTitle                 uint32
	initialDir, title            *uint16
	flags                        uint32
	fileOffset, fileExtension    uint16
	defaultExt                   *uint16
	customData, hook             uintptr
	templateName                 *uint16
	reserved                     uintptr
	reservedFlags, flagsEx       uint32
}

func chooseJSON(window uintptr, export bool) (string, error) {
	dialog := windows.NewLazySystemDLL("comdlg32.dll")
	filter := utf16Filter
	filename := make([]uint16, 32768)
	options := openFileName{
		owner: window, filter: &filter[0], file: &filename[0], maxFile: uint32(len(filename)),
		flags:      0x080808, // OFN_EXPLORER | OFN_PATHMUSTEXIST | OFN_NOCHANGEDIR
		defaultExt: wide("json"),
	}
	options.size = uint32(unsafe.Sizeof(options))
	method := "GetOpenFileNameW"
	if export {
		copy(filename, windows.StringToUTF16("clipclean-settings.json"))
		method = "GetSaveFileNameW"
		options.flags |= 2 // OFN_OVERWRITEPROMPT
	} else {
		options.flags |= 0x1000 // OFN_FILEMUSTEXIST
	}
	result, _, _ := dialog.NewProc(method).Call(uintptr(unsafe.Pointer(&options)))
	if result != 0 {
		return windows.UTF16ToString(filename), nil
	}
	if code, _, _ := dialog.NewProc("CommDlgExtendedError").Call(); code != 0 {
		return "", fmt.Errorf("ファイル選択エラー (0x%x)", code)
	}
	return "", nil // The dialog was cancelled.
}

var utf16Filter = windows.StringToUTF16("JSON 設定ファイル|*.json|すべてのファイル|*.*|")

func init() {
	for i := range utf16Filter {
		if utf16Filter[i] == '|' {
			utf16Filter[i] = 0
		}
	}
}

func (app *application) transferSettings(export bool) {
	path, err := chooseJSON(app.window, export)
	if err == nil && path == "" {
		return
	}
	switch {
	case export:
		if err == nil {
			err = writeSettings(path, app.settings)
		}
		if err != nil {
			errorBox(app.window, "設定をエクスポートできませんでした。", err)
		}
	default:
		var next core.Settings
		if err == nil {
			next, err = readSettings(path)
		}
		if err == nil && messageBox(app.window, "設定をインポート",
			"現在のURL整理設定を置き換えますか？", 0x124) == 6 {
			err = app.saveSettings(next)
			if err == nil {
				app.refreshSettings()
			}
		}
		if err != nil {
			errorBox(app.window, "設定をインポートできませんでした。", err)
		}
	}
}

type startup struct {
	executable, shortcut string
}

func findStartup() startup {
	executable, _ := os.Executable()
	directory, _ := windows.KnownFolderPath(windows.FOLDERID_Startup, 0)
	if directory == "" {
		return startup{}
	}
	return startup{executable, filepath.Join(directory, "ClipClean.lnk")}
}

func (s startup) available() bool {
	return s.shortcut != "" && strings.EqualFold(filepath.Base(s.executable), "ClipClean.exe")
}

func (s startup) registered() bool {
	info, err := os.Stat(s.shortcut)
	return err == nil && info.Mode().IsRegular()
}

func (s startup) set(enabled bool) error {
	if !enabled {
		if err := os.Remove(s.shortcut); err != nil && !os.IsNotExist(err) {
			return err
		}
		return nil
	}

	system, err := windows.GetSystemDirectory()
	if err != nil {
		return err
	}
	const createLink = `
$ErrorActionPreference = 'Stop'
$shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut($env:CLIPCLEAN_LINK)
$shortcut.TargetPath = $env:CLIPCLEAN_EXE
$shortcut.WorkingDirectory = [System.IO.Path]::GetDirectoryName($env:CLIPCLEAN_EXE)
$shortcut.Description = 'ClipClean'
$shortcut.Save()
`
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, filepath.Join(system, "WindowsPowerShell", "v1.0", "powershell.exe"),
		"-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", createLink)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: windows.CREATE_NO_WINDOW}
	cmd.Env = append(os.Environ(), "CLIPCLEAN_LINK="+s.shortcut, "CLIPCLEAN_EXE="+s.executable)
	return cmd.Run()
}

func (app *application) changeStartup(enabled bool) {
	if !app.startup.available() || app.ui.startupResult != nil {
		return
	}
	done := make(chan error, 1)
	app.ui.startupResult = done
	app.refreshStartupCheckbox()
	go func() {
		done <- app.startup.set(enabled)
		postMessage.Call(app.window, wmStartupDone, 0, 0)
	}()
}

func (app *application) finishStartup() {
	var err error
	select {
	case err = <-app.ui.startupResult:
	default:
		return
	}
	app.ui.startupResult = nil
	app.refreshStartupCheckbox()
	if err != nil {
		errorBox(app.window, "ログイン時の自動起動を設定できませんでした。", err)
	}
}

func (app *application) refreshStartupCheckbox() {
	if app.ui.login != 0 {
		check(app.ui.login, app.startup.registered())
		user32.NewProc("EnableWindow").Call(app.ui.login, boolArg(app.startup.available() && app.ui.startupResult == nil))
	}
}
