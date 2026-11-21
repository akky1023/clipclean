package main

import (
	_ "embed"
	"unsafe"
)

//go:embed clipclean.ico
var iconFile []byte

func loadIcon() (uintptr, error) {
	// A single 32x32 entry, following the 6-byte header and 16-byte directory entry.
	image := iconFile[22:]
	icon, _, err := user32.NewProc("CreateIconFromResourceEx").Call(
		uintptr(unsafe.Pointer(&image[0])), uintptr(len(image)), 1, 0x00030000, 0, 0, 0)
	if icon == 0 {
		return 0, err
	}
	return icon, nil
}
