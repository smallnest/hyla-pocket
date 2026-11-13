/* The JNI half of libtailcat.so: what JNIEnv's function table alone can
 * do (read Java strings, pin byte arrays, see a pending exception, throw)
 * is here, because cgo cannot dereference that table; jnigo owns the
 * streams.
 *
 * Why no Java_* function touches JNIEnv: Go's runtime does not know a
 * Java thread that calls into it, and a Go call from such a thread leaves
 * the JVM's JNI array copies broken (a pinned array's bytes are lost,
 * later pins see stale data). So each call leaves the JVM's thread at
 * once, and Go's goCall runs it on a goroutine attached to the JVM by
 * plain C, a thread the JVM made and knows. The operations are
 * tc_jni_*_call, and Go calls them back on that same attached thread. */
#include "tailcat_jni.h"

#include <stdlib.h>
#include <string.h>



/* The JVM of tc_jni_attach, the one JNI_OnLoad was given. */
static JavaVM *jvm;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
	(void)reserved;
	jvm = vm;
	return JNI_VERSION_1_6;
}

static void throw_new(JNIEnv *env, const char *cls, const char *msg) {
	jclass c = (*env)->FindClass(env, cls);
	if (c == NULL) {
		return; /* A NoClassDefFoundError is pending. */
	}
	(*env)->ThrowNew(env, c, msg);
	(*env)->DeleteLocalRef(env, c);
}

/* s as modified UTF-8, as the JVM writes it: free with free(). */
char *tc_jni_string(JNIEnv *env, jstring s) {
	const char *p = (*env)->GetStringUTFChars(env, s, NULL);
	if (p == NULL) {
		return NULL; /* OutOfMemoryError is pending. */
	}
	char *r = strdup(p);
	(*env)->ReleaseStringUTFChars(env, s, p);
	return r;
}

/* Creates a Java string, or leaves OutOfMemoryError pending. */
jstring tc_jni_java_string(JNIEnv *env, const char *s) {
	if (s == NULL) {
		throw_new(env, "java/lang/OutOfMemoryError", "tailcat");
		return NULL;
	}
	return (*env)->NewStringUTF(env, s);
}

void tc_jni_exception(JNIEnv *env, const char *cls, const char *msg) {
	throw_new(env, cls, msg);
}

/* Pins a's elements and checks off..off+len, which the array functions
 * do for a whole array only. An invalid range throws, as Java's own
 * array access does, before any byte is read or written. */
static jbyte *pin(JNIEnv *env, jbyteArray a, jint off, jint len) {
	jbyte *p = (*env)->GetByteArrayElements(env, a, NULL);
	if (p == NULL) {
		return NULL; /* NullPointerException or OutOfMemoryError is pending. */
	}
	if (off < 0 || len < 0 || (jlong)off + len > (*env)->GetArrayLength(env, a)) {
		(*env)->ReleaseByteArrayElements(env, a, p, JNI_ABORT);
		throw_new(env, "java/lang/IndexOutOfBoundsException", "tailcat: range outside the buffer");
		return NULL;
	}
	return p;
}

/* The calling thread's JNIEnv, attached to the JVM if it is not: Go calls
 * this on a thread of its own, which this then makes a JVM thread. */
JNIEnv *tc_jni_attach(void) {
	JNIEnv *env = NULL;
	if (jvm == NULL) {
		return NULL;
	}
	if ((*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
		return env;
	}
	if ((*jvm)->AttachCurrentThread(jvm, (void **)&env, NULL) != JNI_OK) {
		return NULL;
	}
	return env;
}

/* No detach: the operation runs on the thread Java called in on, which the JVM already owns. */
void tc_jni_detach(void) {}

/* The operations, on an attached thread, where Go's goCall went: what
 * they share with Go's half (export.go's C code calls them) is here, and
 * only the JNIEnv work of each. */

JNIEnv *tc_jni_begin(void) { return tc_jni_attach(); }

void *tc_jni_string_value(JNIEnv *env, char *s) {
	void *r = (void *)tc_jni_java_string(env, s);
	free(s);
	return r;
}

/* The host's pair code, from the Java string: free with free(). */
char *tc_jni_code(JNIEnv *env, jstring code) {
	if (code == NULL) {
		throw_new(env, "java/lang/NullPointerException", "tailcat: no pair code");
		return NULL;
	}
	return tc_jni_string(env, code);
}

/* The bytes of a read or write, pinned: NULL if a's range is not. The base of the array, which
 * ReleaseByteArrayElements takes, not the start of the range. */
jbyte *tc_jni_io_begin(JNIEnv *env, jbyteArray arr, int off, int len) {
	return pin(env, arr, off, len);
}

/* The result of a read or write into pinned bytes. A read's bytes go back into the array (mode 0);
 * a write changed nothing in it, and its copy is dropped (JNI_ABORT). */
void *tc_jni_io_done(JNIEnv *env, jbyteArray arr, jbyte *p, int n, int mode) {
	(*env)->ReleaseByteArrayElements(env, arr, p, mode);
	return (void *)(intptr_t)n;
}

/* Why Go's call failed, for the message of the exception, and the
 * exception itself: a Go panic would else end the app instead. */
const char *tc_jni_reason(void) {
	return "tailcat: internal error";
}

void tc_jni_failed(int op) {
	JNIEnv *env = tc_jni_attach();
	if (env == NULL) {
		return;
	}
	throw_new(env, "java/lang/IllegalStateException", "tailcat: internal error");
	tc_jni_detach();
}

/* What Java calls: straight to Go, which attaches a thread and comes
 * back to the operation above. */
/* A reference Go's thread may use: a local one belongs to the thread and
 * to the frame that made it, and goCall runs the operation on neither. */
static jobject global(JNIEnv *env, jobject o) {
	return o == NULL ? NULL : (*env)->NewGlobalRef(env, o);
}

JNIEXPORT jstring JNICALL
Java_io_rpcx_hylapocket_tailcat_Native_lastError(JNIEnv *env, jclass cls) {
	(void)cls;
	char *msg = (char *)goCall(0, NULL, 0, NULL, 0, 0);
	if (msg == NULL) {
		return NULL;
	}
	jstring s = (*env)->NewStringUTF(env, msg);
	free(msg);
	return s;
}

JNIEXPORT jint JNICALL
Java_io_rpcx_hylapocket_tailcat_Native_connect(JNIEnv *env, jclass cls, jstring code, jint port, jint timeout_ms) {
	(void)cls;
	jobject g = global(env, code);
	if (g == NULL) {
		return -1;
	}
	jint r = (jint)(intptr_t)goCall(1, (jstring)g, port, NULL, 0, timeout_ms);
	(*env)->DeleteGlobalRef(env, g);
	return r;
}

JNIEXPORT jint JNICALL
Java_io_rpcx_hylapocket_tailcat_Native_read(JNIEnv *env, jclass cls, jint id, jbyteArray a, jint off, jint len) {
	(void)cls;
	jobject g = global(env, a);
	if (g == NULL) {
		return -2;
	}
	jint r = (jint)(intptr_t)goCall(2, NULL, id, (jbyteArray)g, off, len);
	(*env)->DeleteGlobalRef(env, g);
	return r;
}

JNIEXPORT jint JNICALL
Java_io_rpcx_hylapocket_tailcat_Native_write(JNIEnv *env, jclass cls, jint id, jbyteArray a, jint off, jint len) {
	(void)cls;
	jobject g = global(env, a);
	if (g == NULL) {
		return -1;
	}
	jint r = (jint)(intptr_t)goCall(3, NULL, id, (jbyteArray)g, off, len);
	(*env)->DeleteGlobalRef(env, g);
	return r;
}

JNIEXPORT void JNICALL
Java_io_rpcx_hylapocket_tailcat_Native_close(JNIEnv *env, jclass cls, jint id) {
	(void)cls;
	(void)env;
	goCall(4, NULL, id, NULL, 0, 0);
}

JNIEXPORT void JNICALL
Java_io_rpcx_hylapocket_tailcat_Native_release(JNIEnv *env, jclass cls, jint id) {
	(void)cls;
	(void)env;
	goCall(5, NULL, id, NULL, 0, 0);
}
