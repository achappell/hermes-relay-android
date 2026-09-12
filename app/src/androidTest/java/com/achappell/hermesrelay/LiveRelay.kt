package com.achappell.hermesrelay

/**
 * Marks a test that needs the live household Hermes relay on the tailnet.
 *
 * Excluded from the default instrumentation run by `notAnnotation` in
 * `app/build.gradle.kts`, so ordinary and offline runs neither execute it nor
 * report it as skipped.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class LiveRelay
