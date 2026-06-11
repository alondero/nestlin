package com.github.alondero.nestlin.testutil

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.containsSubstring
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError

class TestAssertsTest {

    /* ---- assertThrowsWithMessage --------------------------------------- */

    @Test
    fun `happy path returns the throwable for further assertions`() {
        val thrown = assertThrowsWithMessage<IllegalStateException>("oops") {
            throw IllegalStateException("oops, something specific")
        }
        // Returned throwable lets the caller probe fields/messages further.
        assertThat(thrown.message ?: "", equalTo("oops, something specific"))
    }

    @Test
    fun `matches subclasses of the expected type`() {
        // The check uses Class.isInstance, so a subclass of the expected
        // type must satisfy assertThrowsWithMessage<Parent>.
        val thrown = assertThrowsWithMessage<RuntimeException>("boom") {
            throw IllegalArgumentException("boom from a subclass")
        }
        assertThat(thrown::class.java.simpleName, equalTo("IllegalArgumentException"))
    }

    @Test
    fun `wrong type produces an AssertionError naming both expected and actual`() {
        val helperFailure = captureAssertionError {
            assertThrowsWithMessage<IllegalStateException>("anything") {
                throw IllegalArgumentException("different type")
            }
        }
        val message = helperFailure.message ?: ""
        assertThat(message, containsSubstring("IllegalStateException"))
        assertThat(message, containsSubstring("IllegalArgumentException"))
        assertThat(message, containsSubstring("different type"))
        // The underlying throwable is preserved as cause so the stack trace
        // points at where it was thrown, not just at the helper.
        assertThat(helperFailure.cause?.javaClass?.name ?: "", equalTo("java.lang.IllegalArgumentException"))
    }

    @Test
    fun `no throw produces an AssertionError naming the expected type and substring`() {
        val helperFailure = captureAssertionError {
            assertThrowsWithMessage<IllegalStateException>("would-have-said-this") {
                // do nothing
            }
        }
        val message = helperFailure.message ?: ""
        assertThat(message, containsSubstring("IllegalStateException"))
        assertThat(message, containsSubstring("would-have-said-this"))
        assertThat(message, containsSubstring("no exception was thrown"))
    }

    @Test
    fun `message-substring mismatch reports both expected and actual`() {
        val helperFailure = captureAssertionError {
            assertThrowsWithMessage<IllegalStateException>("expected fragment") {
                throw IllegalStateException("totally different wording")
            }
        }
        val message = helperFailure.message ?: ""
        assertThat(message, containsSubstring("expected fragment"))
        assertThat(message, containsSubstring("totally different wording"))
        // Original throwable preserved as cause.
        assertThat(helperFailure.cause?.message ?: "", equalTo("totally different wording"))
    }

    @Test
    fun `null exception message is treated as empty for the substring check`() {
        // A throwable with no message is still a type-match; the message
        // substring check should report a clean miss, not NullPointerException.
        val helperFailure = captureAssertionError {
            assertThrowsWithMessage<IllegalStateException>("anything") {
                throw IllegalStateException()
            }
        }
        assertThat(helperFailure.message ?: "", containsSubstring("anything"))
    }

    /* ---- failTest ------------------------------------------------------ */

    @Test
    fun `failTest throws AssertionFailedError with the exact message`() {
        val thrown = captureThrowable { failTest("specific message") }
        assertThat(thrown::class.java.name, equalTo(AssertionFailedError::class.java.name))
        assertThat(thrown.message ?: "", equalTo("specific message"))
    }

    @Test
    fun `failTest return type Nothing lets the compiler treat callers as terminating`() {
        // If failTest's return type were Unit, the compiler would require
        // an `else` branch returning Int below. The fact that this compiles
        // is the test — a runtime `failTest` call simply makes that line
        // unreachable. Wrapped in a guard so we never actually trip it.
        val triggerFail = false
        val result: Int = if (triggerFail) failTest("would terminate") else 42
        assertThat(result, equalTo(42))
    }

    /* ---- small helpers (deliberately not using assertThrowsWithMessage
           so this test stays a clean black-box test of that helper) ------ */

    private inline fun captureAssertionError(block: () -> Unit): AssertionError {
        try {
            block()
        } catch (e: AssertionError) {
            return e
        } catch (e: Throwable) {
            throw AssertionError("Expected AssertionError, got ${e.javaClass.name}: ${e.message}", e)
        }
        throw AssertionError("Expected AssertionError, but no exception was thrown")
    }

    private inline fun captureThrowable(block: () -> Unit): Throwable {
        try {
            block()
        } catch (e: Throwable) {
            return e
        }
        throw AssertionError("Expected a throwable, but none was thrown")
    }
}
