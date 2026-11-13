/* What the two halves of libtailcat.so share. jni.c has the Java_*
 * functions and the JNIEnv work; jnigo's export.go owns the streams and
 * is where each Java_* call goes (goCall), to run on a thread
 * attached to the JVM (tc_jni_attach, tc_jni_detach) and come back to
 * an operation (tc_jni_*_call) on it. Why that detour: see jni.c. */
#ifndef TAILCAT_JNI_H
#define TAILCAT_JNI_H

#include <jni.h>

/* The Go half's tc_jni_* functions take cgo's types, and jni.c sees them
 * by name alone: a hand prototype clashes with cgo's own, and cgo's
 * header is in the other package's build. */

/* Go's way into jni.c (//export goCall in export.go): the op (0 error,
 * 1 connect, 2 read, 3 write, 4 close, 5 release), its arguments (connect:
 * s, id=port, off=timeout; read and write: id, arr, off, len), and the
 * call's result as a pointer. */
void *goCall(int op, void *s, int id, void *arr, int off, int len);

/* //export LastError in export.go: why the last call failed, calloc'd. */
char *LastError(void);

/* C's way in from Go. */
JNIEnv *tc_jni_attach(void);
void tc_jni_detach(void);
char *tc_jni_string(JNIEnv *env, jstring s);
jstring tc_jni_java_string(JNIEnv *env, const char *s);
void tc_jni_exception(JNIEnv *env, const char *cls, const char *msg);
void tc_jni_failed(int op);
JNIEnv *tc_jni_begin(void);
void *tc_jni_string_value(JNIEnv *env, char *s);
char *tc_jni_code(JNIEnv *env, jstring code);
jbyte *tc_jni_io_begin(JNIEnv *env, jbyteArray arr, int off, int len);
void *tc_jni_io_done(JNIEnv *env, jbyteArray arr, jbyte *p, int n, int mode);
const char *tc_jni_reason(void);

#endif
