// LANGUAGE_VERSION: 1.3

import kotlin.internal.contracts.*

inline fun <T> myRun(block: () -> T): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return block()
}

fun getBoolean(): Boolean = false

fun getBoolean() = false

fun test() {
    val x: Int

    if (getBoolean())
        run {
            while (getBoolean()) {
                do {
                    run {
                        if (getBoolean()) {
                            x = 42
                        } else {
                            x = 43
                        }
                    }
                    break
                } while (getBoolean())
                run { x.inc() }
                run { x = 42 }
                break
            }
            x = 42
        }
    else
        run {
            x = 42
        }

    x.inc()
}