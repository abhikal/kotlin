// !forceNotNullTypes: false
// !specifyLocalVariableTypeByDefault: true
package test

class Test(internal var myStr: String) {
    fun sout(str: String) {
        // UNNECESSARY_NOT_NULL_ASSERTION heuristic does not work any more, instead we can skip generating !! altogether
        println(str)
    }

    fun dummy(str: String): String {
        return str
    }

    fun test() {
        sout("String")
        val test: String = "String2"
        sout(test)
        sout(dummy(test))
        Test(test)
    }
}