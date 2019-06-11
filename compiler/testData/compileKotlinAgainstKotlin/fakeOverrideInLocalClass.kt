// FILE: 1.kt

open class Base {
    fun ok() = "OK"
}

inline fun inlineWithLocalClass(): String {
    return (object : Base() {}).ok()
}

// FILE: 2.kt

fun box(): String = inlineWithLocalClass()