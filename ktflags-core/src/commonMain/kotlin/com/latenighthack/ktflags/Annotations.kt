package com.latenighthack.ktflags

/**
 * Marks a data class as a feature flag set.
 *
 * The class must be a `data class` whose primary-constructor properties are all `Boolean`,
 * `String`, `Int` or `Double`, all carry a default value, and all carry exactly one scope
 * annotation ([ServiceScoped], [UserScoped] or [ContextScoped]) unless excluded with [FlagIgnore].
 *
 * The KSP processor generates a `<ClassName>Schema` object implementing `FlagSchema<ClassName>`
 * alongside it, in the same package.
 *
 * ```
 * @FeatureFlagSet
 * data class AppFlags(
 *     @ServiceScoped           val newCheckout: Boolean = false,
 *     @UserScoped              val darkMode: Boolean = false,
 *     @ContextScoped("tenant") val betaApi: Boolean = false,
 * )
 * ```
 *
 * @param name overrides the schema name used on the wire and in the admin panel. Defaults to the
 *   class's simple name. Changing it after deployment orphans every stored override.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
public annotation class FeatureFlagSet(val name: String = "")

/** One value for the whole service. The classic on/off switch. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
public annotation class ServiceScoped

/**
 * One value per user id, where the user id is whatever the consumer supplies.
 *
 * A user-scoped flag still has a service-wide value underneath it — that is the rollout knob
 * ("on for everybody"), while per-user rows are for QA accounts, escape hatches and support.
 * Evaluating one with no user id is not an error; it resolves to the service-wide value.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
public annotation class UserScoped

/**
 * One value per key within a named dimension — any axis the caller can label, such as
 * `@ContextScoped("tenant")` or `@ContextScoped("region")`.
 *
 * The caller supplies `mapOf("tenant" to "acme")` at evaluation time. A context entry for a
 * dimension no flag declares is ignored rather than rejected, so a client can send a fixed
 * context bag as the schema evolves.
 *
 * @param dimension the axis name. Must not be blank.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
public annotation class ContextScoped(val dimension: String)

/**
 * Excludes a property from the flag set.
 *
 * An unannotated property is an error rather than a silent skip: a typo'd or forgotten scope
 * annotation would otherwise drop a flag from the schema with no diagnostic at all. This is the
 * explicit way to say "not a flag".
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
public annotation class FlagIgnore

/**
 * Overrides the wire key for a flag, which otherwise defaults to the property name.
 *
 * Useful to rename a property without orphaning stored overrides — the key is what rows are
 * keyed by, so keeping it stable across a refactor keeps the data.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
public annotation class FlagKey(val name: String)

/** Human-readable description shown next to the flag in the admin panel. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
public annotation class FlagDescription(val text: String)
