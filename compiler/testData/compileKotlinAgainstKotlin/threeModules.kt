// FILE: A.kt
package a

fun okA() = "OK"

// FILE: B.kt
package b

import a.okA

inline fun okB() = okA()

// FILE: C.kt
import b.okB

fun box() = okB()