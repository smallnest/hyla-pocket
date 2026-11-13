// Package jni is the Java_* half of libtailcat.so: the functions the JVM
// finds by name for app.hylapocket.tailcat.Native, written in C because
// only JNIEnv's function table reads Java strings, pins byte arrays and
// throws; jni.c is their body. They call the tc_jni_* functions that
// jnigo exports, which own the streams.
//
// Why not //export in this package: cgo makes a C function of an export
// with a C signature of its own, and nothing in the JVM's link would see
// it as Java_app_…; and cgo compiles C files in a main package only if it
// imports "C", which would then also take this one's C.
package jni

/*
#cgo CFLAGS: -I${SRCDIR}
*/
import "C"
