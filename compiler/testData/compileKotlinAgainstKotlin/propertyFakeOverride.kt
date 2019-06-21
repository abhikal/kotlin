// FILE: A.kt

open class A {
    val ok: String = "OK"
}

class B : A()

// FILE: B.kt

fun box(): String {
    return B().ok
}

