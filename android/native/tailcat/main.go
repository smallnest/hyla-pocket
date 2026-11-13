// Package main builds libtailcat.so, the tailcat client behind the JVM:
// jnigo's streams and goCall, jni's Java_* functions, and the tcgo_*
// functions that jni.c calls (goapi).
package main

import (
	_ "github.com/smallnest/hylapocket/native/tailcat/goapi"
	_ "github.com/smallnest/hylapocket/native/tailcat/jni"
	_ "github.com/smallnest/hylapocket/native/tailcat/jnigo"
)

func main() {}
