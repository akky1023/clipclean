package main

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
	"net.neomenia/clipclean/core"
)

const (
	wmDestroy         = 0x0002
	wmClose           = 0x0010
	wmCommand         = 0x0111
	wmTimer           = 0x0113
	wmContextMenu     = 0x007b
	wmClipboardUpdate = 0x031d
	wmTray            = 0x8001

	idSettings = 101
	idPause    = 102
	idQuit     = 103
)

var (
	user32              = windows.NewLazySystemDLL("user32.dll")
	kernel32            = windows.NewLazySystemDLL("kernel32.dll")
	shell32             = windows.NewLazySystemDLL("shell32.dll")
	defWindowProc       = user32.NewProc("DefWindowProcW")
	createWindow        = user32.NewProc("CreateWindowExW")
	sendMessage         = user32.NewProc("SendMessageW")
	showWindow          = user32.NewProc("ShowWindow")
	setForegroundWindow = user32.NewProc("SetForegroundWindow")
	destroyWindow       = user32.NewProc("DestroyWindow")
	postMessage         = user32.NewProc("PostMessageW")
	registerMessage     = user32.NewProc("RegisterWindowMessageW")
	notifyIcon          = shell32.NewProc("Shell_NotifyIconW")
	windowCallback      = syscall.NewCallback(windowProc)
	active              *application
)

type point struct{ x, y int32 }

type message struct {
	window         uintptr
	id             uint32
	wparam, lparam uintptr
	time           uint32
	position       point
	private        uint32
}

type windowClass struct {
	size, style                        uint32
	callback                           uintptr
	classExtra, windowExtra            int32
	instance, icon, cursor, background uintptr
	menuName, className                *uint16
	smallIcon                          uintptr
}

type trayIcon struct {
	size                uint32
	window              uintptr
	id, flags, callback uint32
	icon                uintptr
	tip                 [128]uint16
	state, stateMask    uint32
	info                [256]uint16
	version             uint32
	infoTitle           [64]uint16
	infoFlags           uint32
	guid                windows.GUID
	balloonIcon         uintptr
}

type application struct {
	window, instance, taskbarCreated uintptr
	tray                             trayIcon
	monitor                          clipboardMonitor
	paused                           bool
	settings                         core.Settings
	settingsFile                     string
	startup                          startup
	ui                               settingsUI
}

func main() {
	runtime.LockOSThread()
	if err := run(); err != nil {
		errorBox(0, "ClipClean を起動できませんでした。", err)
	}
}

func run() error {
	local, ok := os.LookupEnv("LOCALAPPDATA")
	if !ok {
		var err error
		local, err = os.UserHomeDir()
		if err != nil {
			return err
		}
	}
	directory := filepath.Join(local, "ClipClean")
	if err := os.MkdirAll(directory, 0700); err != nil {
		return err
	}

	// Lock the existing file so the Java edition also stays out of the clipboard.
	lock, err := os.OpenFile(filepath.Join(directory, "instance.lock"), os.O_CREATE|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	defer lock.Close()
	var lockRange windows.Overlapped
	err = windows.LockFileEx(windows.Handle(lock.Fd()), windows.LOCKFILE_EXCLUSIVE_LOCK|windows.LOCKFILE_FAIL_IMMEDIATELY,
		0, 1, 0, &lockRange)
	if err == windows.ERROR_LOCK_VIOLATION {
		return nil
	}
	if err != nil {
		return err
	}

	app := &application{
		settingsFile: filepath.Join(directory, "settings.json"),
		startup:      findStartup(),
	}
	if settings, err := readSettings(app.settingsFile); err == nil {
		app.settings = settings
	} else if !os.IsNotExist(err) {
		errorBox(0, "保存した設定を読み込めません。初期設定で起動します。", err)
	}
	active = app
	if err := app.open(); err != nil {
		return err
	}
	defer destroyWindow.Call(app.window)
	if err := app.addTray(); err != nil {
		return err
	}
	defer notifyIcon.Call(2, uintptr(unsafe.Pointer(&app.tray))) // NIM_DELETE

	if err := app.monitor.start(app.window); err != nil {
		errorBox(app.window, "クリップボードの監視を開始できませんでした。", err)
	} else {
		defer app.monitor.stop(app.window)
	}
	if app.startup.available() && app.startup.registered() {
		app.changeStartup(true)
	}

	var msg message
	getMessage := user32.NewProc("GetMessageW")
	isDialogMessage := user32.NewProc("IsDialogMessageW")
	isWindowVisible := user32.NewProc("IsWindowVisible")
	translate := user32.NewProc("TranslateMessage")
	dispatch := user32.NewProc("DispatchMessageW")
	for {
		result, _, err := getMessage.Call(uintptr(unsafe.Pointer(&msg)), 0, 0, 0)
		switch int32(result) {
		case -1:
			return err
		case 0:
			return nil
		}
		if visible, _, _ := isWindowVisible.Call(app.window); visible != 0 {
			if handled, _, _ := isDialogMessage.Call(app.window, uintptr(unsafe.Pointer(&msg))); handled != 0 {
				continue
			}
		}
		translate.Call(uintptr(unsafe.Pointer(&msg)))
		dispatch.Call(uintptr(unsafe.Pointer(&msg)))
	}
}

func (app *application) open() error {
	user32.NewProc("SetProcessDPIAware").Call()
	app.instance, _, _ = kernel32.NewProc("GetModuleHandleW").Call(0)
	app.taskbarCreated, _, _ = registerMessage.Call(uintptr(unsafe.Pointer(wide("TaskbarCreated"))))
	icon, err := loadIcon()
	if err != nil {
		return err
	}
	cursor, _, _ := user32.NewProc("LoadCursorW").Call(0, 32512) // IDC_ARROW
	class := windowClass{
		callback: windowCallback, instance: app.instance, icon: icon, smallIcon: icon,
		cursor: cursor, background: 16, className: wide("ClipClean.Settings"),
	}
	class.size = uint32(unsafe.Sizeof(class))
	if result, _, err := user32.NewProc("RegisterClassExW").Call(uintptr(unsafe.Pointer(&class))); result == 0 {
		return err
	}
	const useDefault = 0x80000000
	const style = 0x00ca0000 // Caption, system menu and minimize button.
	app.ui.measure(style)
	window, _, err := createWindow.Call(0, uintptr(unsafe.Pointer(class.className)),
		uintptr(unsafe.Pointer(wide("ClipClean の設定"))), style, useDefault, useDefault,
		uintptr(app.ui.width), uintptr(app.ui.height), 0, 0, app.instance, 0)
	runtime.KeepAlive(class)
	if window == 0 {
		return err
	}
	app.window = window
	app.tray = trayIcon{window: window, id: 1, flags: 7, callback: wmTray, icon: icon}
	app.tray.size = uint32(unsafe.Sizeof(app.tray))
	return nil
}

func (app *application) addTray() error {
	app.updateTip()
	if result, _, err := notifyIcon.Call(0, uintptr(unsafe.Pointer(&app.tray))); result == 0 {
		return err
	}
	return nil
}

func (app *application) updateTip() {
	tip := "ClipClean"
	if app.paused {
		tip = "ClipClean（一時停止中）"
	}
	copy(app.tray.tip[:], windows.StringToUTF16(tip))
}

func (app *application) trayMenu() {
	menu, _, _ := user32.NewProc("CreatePopupMenu").Call()
	if menu == 0 {
		return
	}
	defer user32.NewProc("DestroyMenu").Call(menu)
	appendMenu := user32.NewProc("AppendMenuW")
	appendMenu.Call(menu, 0, idSettings, uintptr(unsafe.Pointer(wide("設定"))))
	pause := "一時停止"
	if app.paused {
		pause = "再開"
	}
	var flags uintptr
	if !app.monitor.listening {
		flags = 1 // MF_GRAYED
	}
	appendMenu.Call(menu, flags, idPause, uintptr(unsafe.Pointer(wide(pause))))
	appendMenu.Call(menu, 0x0800, 0, 0) // MF_SEPARATOR
	appendMenu.Call(menu, 0, idQuit, uintptr(unsafe.Pointer(wide("終了"))))
	var cursor point
	user32.NewProc("GetCursorPos").Call(uintptr(unsafe.Pointer(&cursor)))
	setForegroundWindow.Call(app.window)
	handle, _, _ := user32.NewProc("TrackPopupMenu").Call(menu, 0x0102, uintptr(cursor.x), uintptr(cursor.y), 0, app.window, 0)
	postMessage.Call(app.window, 0, 0, 0)
	switch handle {
	case idSettings:
		app.showSettings()
	case idPause:
		app.paused = !app.paused
		if !app.paused {
			app.monitor.ignoreCurrent(app.window)
		}
		app.updateTip()
		notifyIcon.Call(1, uintptr(unsafe.Pointer(&app.tray))) // NIM_MODIFY
	case idQuit:
		user32.NewProc("PostQuitMessage").Call(0)
	}
}

func windowProc(window uintptr, msg uint32, wparam, lparam uintptr) uintptr {
	app := active
	if app != nil && app.window == window {
		switch msg {
		case wmTray:
			switch uint32(lparam) {
			case 0x0202, 0x0203: // Left-button release and double click.
				app.showSettings()
			case 0x0205, wmContextMenu: // Right-button release and keyboard context menu.
				app.trayMenu()
			}
			return 0
		case wmClose:
			showWindow.Call(window, 0)
			return 0
		case wmDestroy:
			user32.NewProc("PostQuitMessage").Call(0)
			return 0
		case wmCommand:
			app.settingsCommand(int(wparam&0xffff), int(wparam>>16), lparam)
			return 0
		case wmClipboardUpdate:
			app.monitor.update(window, true, app.paused, app.settings)
			return 0
		case wmTimer:
			if wparam == clipboardTimer {
				app.monitor.update(window, false, app.paused, app.settings)
				return 0
			}
		case uint32(app.taskbarCreated):
			if msg != 0 {
				_ = app.addTray()
			}
		case wmStartupDone:
			app.finishStartup()
			return 0
		}
	}
	result, _, _ := defWindowProc.Call(window, uintptr(msg), wparam, lparam)
	return result
}

func wide(text string) *uint16 {
	head, _, _ := strings.Cut(text, "\x00")
	return windows.StringToUTF16Ptr(head)
}

func messageBox(window uintptr, title, text string, style uintptr) uintptr {
	result, _, _ := user32.NewProc("MessageBoxW").Call(window,
		uintptr(unsafe.Pointer(wide(text))), uintptr(unsafe.Pointer(wide(title))), style)
	return result
}

func errorBox(window uintptr, text string, err error) {
	messageBox(window, "ClipClean", fmt.Sprintf("%s\n%s", text, err), 0x10)
}
