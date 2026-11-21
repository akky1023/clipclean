package main

import (
	"unicode/utf16"
	"unsafe"

	"net.neomenia/clipclean/core"
)

const (
	clipboardTimer    = 1
	cfUnicodeText     = 13
	maxClipboardBytes = 512 * 1024
)

var (
	clipboardSequence          = user32.NewProc("GetClipboardSequenceNumber")
	clipboardOwner             = user32.NewProc("GetClipboardOwner")
	openClipboard              = user32.NewProc("OpenClipboard")
	closeClipboard             = user32.NewProc("CloseClipboard")
	emptyClipboard             = user32.NewProc("EmptyClipboard")
	setClipboardData           = user32.NewProc("SetClipboardData")
	getClipboardData           = user32.NewProc("GetClipboardData")
	isClipboardFormatAvailable = user32.NewProc("IsClipboardFormatAvailable")
	killTimer                  = user32.NewProc("KillTimer")
	setTimer                   = user32.NewProc("SetTimer")
	globalAlloc                = kernel32.NewProc("GlobalAlloc")
	globalLock                 = kernel32.NewProc("GlobalLock")
	globalUnlock               = kernel32.NewProc("GlobalUnlock")
	globalSize                 = kernel32.NewProc("GlobalSize")
	globalFree                 = kernel32.NewProc("GlobalFree")
	copyMemory                 = kernel32.NewProc("RtlMoveMemory")
)

type clipboardMonitor struct {
	listening      bool
	lastSequence   uintptr
	retries        int
	skippedFormats []uintptr
}

func (monitor *clipboardMonitor) start(window uintptr) error {
	monitor.lastSequence, _, _ = clipboardSequence.Call()
	registerFormat := user32.NewProc("RegisterClipboardFormatW")
	protected, _, err := registerFormat.Call(uintptr(unsafe.Pointer(wide("ExcludeClipboardContentFromMonitorProcessing"))))
	if protected == 0 {
		return err
	}
	password, _, err := registerFormat.Call(uintptr(unsafe.Pointer(wide("Clipboard Viewer Ignore"))))
	if password == 0 {
		return err
	}
	monitor.skippedFormats = []uintptr{protected, password, 2, 8, 17, 15} // Bitmap, DIB, DIBV5, file drop.
	result, _, err := user32.NewProc("AddClipboardFormatListener").Call(window)
	monitor.listening = result != 0
	if !monitor.listening {
		return err
	}
	return nil
}

func (monitor *clipboardMonitor) stop(window uintptr) {
	killTimer.Call(window, clipboardTimer)
	user32.NewProc("RemoveClipboardFormatListener").Call(window)
}

func (monitor *clipboardMonitor) ignoreCurrent(window uintptr) {
	killTimer.Call(window, clipboardTimer)
	monitor.lastSequence, _, _ = clipboardSequence.Call()
}

func (monitor *clipboardMonitor) update(window uintptr, changed, paused bool, settings core.Settings) {
	if !monitor.listening {
		return
	}
	if changed {
		monitor.retries = 0
	}
	killTimer.Call(window, clipboardTimer)
	sequence, _, _ := clipboardSequence.Call()
	if sequence == monitor.lastSequence || paused {
		monitor.lastSequence = sequence
		return
	}
	if opened, _, _ := openClipboard.Call(window); opened == 0 {
		monitor.retries++
		if monitor.retries <= 5 {
			setTimer.Call(window, clipboardTimer, 30, 0)
		}
		return
	}
	defer closeClipboard.Call()

	// Read and replace while holding the clipboard lock.
	monitor.lastSequence, _, _ = clipboardSequence.Call()
	if owner, _, _ := clipboardOwner.Call(); owner == window {
		return
	}
	for _, format := range monitor.skippedFormats {
		if present, _, _ := isClipboardFormatAvailable.Call(format); present != 0 {
			return
		}
	}
	text, ok := readClipboardText()
	if ok {
		if result := core.Clean(text, settings); result.Changed {
			replaceClipboardText(text, result.Text)
		}
	}
}

func readClipboardText() (string, bool) {
	handle, _, _ := getClipboardData.Call(cfUnicodeText)
	if handle == 0 {
		return "", false
	}
	size, _, _ := globalSize.Call(handle)
	if size < 2 || size > maxClipboardBytes {
		return "", false
	}
	data, _, _ := globalLock.Call(handle)
	if data == 0 {
		return "", false
	}
	buf := make([]uint16, size/2)
	copyMemory.Call(uintptr(unsafe.Pointer(&buf[0])), data, size&^1)
	globalUnlock.Call(handle)
	for i, char := range buf {
		if char == 0 {
			return string(utf16.Decode(buf[:i])), true
		}
	}
	return "", false
}

func allocateClipboardText(text string) uintptr {
	buf := append(utf16.Encode([]rune(text)), 0)
	size := uintptr(len(buf) * 2)
	handle, _, _ := globalAlloc.Call(2, size) // GMEM_MOVEABLE
	if handle == 0 {
		return 0
	}
	data, _, _ := globalLock.Call(handle)
	if data == 0 {
		globalFree.Call(handle)
		return 0
	}
	copyMemory.Call(data, uintptr(unsafe.Pointer(&buf[0])), size)
	globalUnlock.Call(handle)
	return handle
}

func replaceClipboardText(input, output string) {
	replacement := allocateClipboardText(output)
	if replacement == 0 {
		return
	}
	backup := allocateClipboardText(input)
	defer func() {
		if replacement != 0 {
			globalFree.Call(replacement)
		}
		if backup != 0 {
			globalFree.Call(backup)
		}
	}()
	if backup == 0 {
		return
	}
	if emptied, _, _ := emptyClipboard.Call(); emptied == 0 {
		return
	}
	if set, _, _ := setClipboardData.Call(cfUnicodeText, replacement); set != 0 {
		replacement = 0 // Windows owns the handle after a successful transfer.
	} else if set, _, _ := setClipboardData.Call(cfUnicodeText, backup); set != 0 {
		backup = 0
	}
}
