// Package goapi exports the Go half of libtailcat.so. Its //exports are
// declared before any jni.c preamble, so that jni.c, which calls them,
// can declare them its own way in tailcat_jni.h, and Go's cgo_export.h,
// which holds no jni.c preamble, stands as cgo makes it.
package goapi

import "C"

import (
	"errors"
	"io"
	"time"
	"unsafe"

	"github.com/smallnest/hylapocket/native/tailcat/bridge"
)

// LastError is why the last call failed, calloc'd: jni.c frees it.

//export LastError
func LastError() *C.char {
	return C.CString(bridge.LastError())
}

//export tcgo_connect
func tcgo_connect(code *C.char, port, timeoutMs C.int) C.int {
	if timeoutMs <= 0 {
		timeoutMs = 30000
	}
	id, err := bridge.Connect(C.GoString(code), int(port), time.Duration(timeoutMs)*time.Millisecond)
	if err != nil {
		return -1
	}
	return C.int(id)
}

// Read and Write copy at once through a slice over the bytes jni.c pins:
// no Go pointer reaches the caller, and none is held after the call.

//export tcgo_read
func tcgo_read(id C.int, b *C.uchar, n C.int) C.int {
	got, err := bridge.Read(int(id), unsafe.Slice((*byte)(unsafe.Pointer(b)), int(n)))
	bridge.NoteError(err)
	switch {
	case errors.Is(err, bridge.ErrPolled):
		return 0
	case errors.Is(err, io.EOF):
		return bridge.EOF
	case err != nil:
		return bridge.Failed
	}
	return C.int(got)
}

//export tcgo_write
func tcgo_write(id C.int, b *C.uchar, n C.int) C.int {
	got, err := bridge.Write(int(id), unsafe.Slice((*byte)(unsafe.Pointer(b)), int(n)))
	bridge.NoteError(err)
	if err != nil {
		return bridge.WriteFail
	}
	return C.int(got)
}

//export tcgo_close
func tcgo_close(id C.int) { bridge.Close(int(id)) }

//export tcgo_release
func tcgo_release(id C.int) { bridge.Release(int(id)) }
