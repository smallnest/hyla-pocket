// Package jnigo is the Go half of libtailcat.so: the tailcat client in
// bridge, and goCall, which every call of the JVM's Native goes through.
// It is not main, as cgo compiles C files (jni/jni.c, the Java_*
// functions) only in a main package that imports "C".
package jnigo

/*
#cgo CFLAGS: -I${SRCDIR}/../jni
#include <stdlib.h>
#include "tailcat_jni.h"

// Go's calls of jni.c (tc_jni_*) and jni.c's calls of Go (tcgo_*): cgo
// makes the prototypes of the latter, and these agree with them.
char *tcgo_last_error(void);
int tcgo_connect(const char *code, int port, int timeout_ms);
int tcgo_read(int id, unsigned char *b, int len);
int tcgo_write(int id, unsigned char *b, int len);
void tcgo_close(int id);
void tcgo_release(int id);

static void *tc_jni_op(int op, void *s, int id, void *arr, int off, int len);

// One operation of Native (an op*), on an attached thread, with the JNI
// calls of jni.c: this is where Go and JVM meet. It detaches before it
// returns.
static void *tc_jni_op(int op, void *s, int id, void *arr, int off, int len) {
	JNIEnv *env = tc_jni_begin();
	if (env == NULL) {
		return NULL;
	}
	void *r = NULL;
	switch (op) {
	case 0: {
		// A C string, not a jstring: jni.c makes the Java string on the caller's thread.
		r = (void *)LastError();
		break;
	}
	case 1: {
		char *code = tc_jni_code(env, (jstring)s);
		if (code != NULL) {
			int h = tcgo_connect(code, id, off);
			free(code);
			r = (void *)(intptr_t)h;
		}
		break;
	}
	case 2:
	case 3:
		if (len == 0) {
			r = (void *)(intptr_t)(op == 3 ? -1 : 0);
		} else {
			jbyte *base = tc_jni_io_begin(env, (jbyteArray)arr, off, len);
			if (base != NULL) {
				unsigned char *p = (unsigned char *)base + off;
				int n = op == 3 ? tcgo_write(id, p, len) : tcgo_read(id, p, len);
				// A read's bytes must reach the Java array; a write leaves it untouched.
				int mode = op == 2 ? 0 : JNI_ABORT;
				r = tc_jni_io_done(env, (jbyteArray)arr, base, n, mode);
			} else {
				r = (void *)(intptr_t)(op == 3 ? -1 : -2);
			}
		}
		break;
	case 4:
		tcgo_close(id);
		break;
	case 5:
		tcgo_release(id);
		break;
	}
	tc_jni_detach();
	return r;
}
*/
import "C"

import (
	"runtime/debug"
	"unsafe"

	"github.com/smallnest/hylapocket/native/tailcat/bridge"
	_ "github.com/smallnest/hylapocket/native/tailcat/jni" // jni.c's functions, which goCall calls
)

// The JVM's thread stack is small, and Go's is not: the streams' goroutines
// and tailcat's run below these calls, on the same threads.
func init() {
	debug.SetMaxStack(8 << 20)
	// A Read through JNI waits for data in JNI's caller, not here: see
	// bridge.Read for why the array cannot stay pinned for the wait.
	bridge.Poll = 0
}

// The operations of jni.c's tc_jni_*_call, as goCall is given them.
const (
	opError = iota
	opConnect
	opRead
	opWrite
	opClose
	opRelease
)

// The Go half's calls of jni.c: declared in tailcat_jni.h, which cgo
// makes its prototypes of, so they reach C code below.

// goCall runs one call of Native (an op*) with a JNIEnv that the JVM
// made: a goroutine of its own attaches with plain C (tc_jni_attach),
// every JNI call of the operation is made from that thread, which Go
// does not run, and it detaches at its end. Why not the JVM's thread:
// a Go call from a JVM thread corrupts the JVM's JNI array copies.
//
// Why a new goroutine for each call, not a thread of its own: Go pins a
// goroutine to its OS thread when it enters cgo, and the JVM attaches an
// OS thread once, so each fresh goroutine that goes through is one
// attach and one detach, in balance. A reused goroutine would hold its
// JVM thread, and the JVM would keep a thread no goroutine would use.
//
//export goCall
func goCall(op C.int, s unsafe.Pointer, id C.int, arr unsafe.Pointer, off, len C.int) (r unsafe.Pointer) {
	// The operation runs on the thread Java called in on: its JNIEnv is the one its objects belong
	// to. Attaching a thread of Go's instead left every stream ending right after its first
	// exchange on Android.
	defer func() {
		if p := recover(); p != nil {
			r = nil
			C.tc_jni_failed(C.int(op))
		}
	}()
	return C.tc_jni_op(op, s, id, arr, off, len)
}
