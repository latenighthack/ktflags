# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] — 0.1.0

### Added

- `ktflags-core`: the `@FeatureFlagSet` annotations, the scoped value model
  (`FlagValue`/`FlagScope`/`FlagDefinition`/`FlagSubject`), the three-layer `FlagEvaluator`, the
  `FlagsRepository` SPI and its in-memory reference implementation. No protobuf dependency, so a
  consumer's flags module stays free of one.
- `ktflags-ksp`: generates a `FlagSchema<T>` from an annotated data class. Defaults are read off a
  default-constructed instance rather than parsed out of the source, which is why every flag needs
  a constructor default.
- `ktflags-proto`: the protobuf schema, its generated ktbuf types, and the single domain-to-wire
  mapping boundary.
- `ktflags-client`: `FeatureFlagsProvider<T>` — cache-first, offline-safe, non-throwing
  `refresh()`, coalesced concurrent refreshes, and a flat protobuf cache with per-platform
  implementations (atomic file writes on JVM/Android/Apple, `localStorage` in the browser).
- `ktflags-server`: `FeatureFlagsService<T>`, a `createApplicationPlugin` Ktor plugin, an admin
  panel on a separate internal listener, and a JSON admin API alongside the protobuf one.
- `ktflags-store-sqlite` and `ktflags-store-postgres`: two `FlagsRepository` implementations held
  to one shared conformance suite plus a step-for-step dialect-parity test.
- `ktflags-test`: `FakeFeatureFlags`, `assertSchemaWellFormed`, and the repository contract.
- `com.latenighthack.ktflags` Gradle plugin for wiring KSP in a consumer's flags module.

### Notes

- Ktor is pinned to **3.0.2**: ktbuf's server routing mishandles unary bodies over ~8KB on 3.3.x.
- Requires **ktbuf 1.1.8+**, the first version publishing macOS targets.
