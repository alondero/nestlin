package com.github.alondero.nestlin.testutil

import org.opentest4j.AssertionFailedError

/**
 * Shared exception-assertion helpers for Nestlin's test suite.
 *
 * Two footguns keep biting JUnit-5 + Kotlin tests in this project — recorded in
 * separate session-memory files because they kept being re-discovered:
 *
 *  1. `kotlin.test.assertFailsWith` is the obvious thing to reach for, but
 *     `kotlin-test` is intentionally NOT on the classpath here (JUnit 5 +
 *     Hamkrest only). Hand-rolling a 4-line `try/catch` in each test diverges
 *     in subtle ways (which `Throwable` it re-throws on type mismatch, whether
 *     it reports "no exception was thrown" at all, etc).
 *
 *  2. JUnit 5's `Assertions.fail` is overloaded with both `(String)` and
 *     `(Supplier<String>): V` (returning a generic `V`). Kotlin's overload
 *     resolution sometimes picks the `Supplier` variant for a plain `String`
 *     argument and complains it cannot infer `V`. The established workaround
 *     is `throw org.opentest4j.AssertionFailedError(msg)` — what `fail(String)`
 *     does internally anyway — but it ends up smeared across multiple call
 *     sites with comment blocks re-explaining the bug each time.
 *
 * [TestAssertsLintTest] keeps `kotlin.test` imports and raw `Assertions.fail`
 * with non-lambda args out of new test code.
 */

/**
 * Runs [block], asserting it throws an exception of type [T] whose message
 * contains [messageSubstring]. Returns the throwable so the caller can
 * make further assertions on it.
 *
 * The reified type parameter is why this must be `inline` — the JVM-erased
 * `T` is recovered via [Class.isInstance] at the call site.
 *
 * On failure: throws [AssertionError] with both the expected and actual
 * information, including the unexpected throwable as `cause` so JUnit's
 * stack trace points at the underlying cause, not just this helper.
 *
 * Example:
 * ```
 * val e = assertThrowsWithMessage<BadHeaderException>("missing magic") {
 *     GamePak(brokenBytes)
 * }
 * assertThat(e.offset, equalTo(0))
 * ```
 */
inline fun <reified T : Throwable> assertThrowsWithMessage(
    messageSubstring: String,
    block: () -> Unit,
): T {
    val expectedType = T::class.java
    try {
        block()
    } catch (e: Throwable) {
        if (!expectedType.isInstance(e)) {
            throw AssertionError(
                "Expected ${expectedType.simpleName}, got ${e.javaClass.name}: ${e.message}",
                e,
            )
        }
        val message = e.message ?: ""
        if (!message.contains(messageSubstring)) {
            throw AssertionError(
                "Expected ${expectedType.simpleName} message to contain " +
                    "\"$messageSubstring\", got: \"$message\"",
                e,
            )
        }
        @Suppress("UNCHECKED_CAST")
        return e as T
    }
    throw AssertionError(
        "Expected ${expectedType.simpleName} (\"$messageSubstring\"), " +
            "but no exception was thrown",
    )
}

/**
 * Fails the current test with [message], bypassing JUnit 5's
 * `Assertions.fail(...)` overload-resolution bug for non-lambda String
 * arguments.
 *
 * Functionally identical to `Assertions.fail(message)` — JUnit 5's
 * implementation of that overload also just throws an
 * [AssertionFailedError]. Use this whenever the message is anything other
 * than a bare literal lambda body (string concatenations, variable
 * references, and even plain literals all qualify — the consistency is
 * the point).
 *
 * Returns `Nothing` so the compiler tracks this line as terminating,
 * just like `throw`.
 */
fun failTest(message: String): Nothing = throw AssertionFailedError(message)
