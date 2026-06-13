package com.github.alondero.nestlin.compare

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Marks a test (or test class) as requiring the Mesen2 reference emulator.
 *
 * Default behaviour when Mesen2 is absent: the test is DISABLED with a loud
 * reason naming the resolved path — never a silent green (the anti-pattern from
 * docs/TESTING_STRATEGY.md).
 *
 * Strict mode: set the env var NESTLIN_REQUIRE_MESEN2 (to any non-blank value)
 * and a missing Mesen2 becomes a hard FAILURE instead of a skip. Use this on
 * machines/CI lanes where Mesen2 is supposed to exist, so a broken MESEN2_PATH
 * cannot false-green the comparison suite. Forwarded by the testMesenComparison
 * Gradle task.
 *
 * Lane assignment is automatic: this annotation is meta-tagged [@Tag][Tag]`("mesen")`,
 * so every test that requires Mesen2 is excluded from `./gradlew test` and included by
 * `./gradlew testMesenComparison` *with no build-script edit*. This is what retired the
 * hand-maintained `mesenTests` list that silently went stale (Mapper24/26/64 were never
 * added to it). A test that needs Mesen2 should carry THIS annotation rather than a bare
 * `assumeTrue(mesen2Available)`, so it lands in the right lane and skips loudly when absent.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag("mesen")
@ExtendWith(RequiresMesen2Condition::class)
annotation class RequiresMesen2

class RequiresMesen2Condition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        if (Mesen2StateCapturer.isMesen2Available()) {
            return ConditionEvaluationResult.enabled("Mesen2 found at ${Mesen2StateCapturer.getMesen2Path()}")
        }

        val resolvedPath = Mesen2StateCapturer.getMesen2Path().toAbsolutePath()
        val strict = System.getenv("NESTLIN_REQUIRE_MESEN2")
        if (!strict.isNullOrBlank()) {
            throw IllegalStateException(
                "Mesen2 required but not found at $resolvedPath (NESTLIN_REQUIRE_MESEN2 strict mode)"
            )
        }

        return ConditionEvaluationResult.disabled(
            "MESEN2 NOT AVAILABLE: resolved path $resolvedPath does not exist. " +
                "Set MESEN2_PATH (or -Dmesen2.path) to your Mesen2 executable, or set " +
                "NESTLIN_REQUIRE_MESEN2=1 to make this a hard failure instead of a skip. " +
                "Remember the Gradle daemon may hold stale env: ./gradlew --stop, then re-run."
        )
    }
}
