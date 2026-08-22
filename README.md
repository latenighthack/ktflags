# KtFlags

Scoped feature flags for Kotlin Multiplatform, declared once as a data class and served over
protobuf RPC from a Ktor server.

You write one annotated data class. KtFlags gives you a typed, cache-first client on Android, iOS,
macOS, JS and the JVM; a Ktor plugin that serves and stores those flags; and an admin panel to flip
them per user, per tenant, or for everyone.

```kotlin
@FeatureFlagSet
data class AppFlags(
    @ServiceScoped           val newCheckout: Boolean = false,
    @UserScoped              val darkMode: Boolean = false,
    @ContextScoped("tenant") val betaApi: Boolean = false,
    @UserScoped              val variant: String = "control",
    @ServiceScoped           val maxItems: Int = 10,
)
```

## Status

Pre-1.0. Targets: `jvm`, `android`, `iosArm64`, `iosX64`, `iosSimulatorArm64`, `macosArm64`,
`macosX64`, `js(IR)`. Server modules are JVM-only.

## Scopes

Every flag resolves through the same three layers — **subject row → service row → code default**.
The scope only decides *which* subject row is looked up, or whether one is looked up at all.

| Annotation | Layer 1 | Layer 2 | Layer 3 |
|---|---|---|---|
| `@ServiceScoped` | *(skipped)* | the service-wide row | the constructor default |
| `@UserScoped` | this user's row | the service-wide row | the constructor default |
| `@ContextScoped("tenant")` | this tenant's row | the service-wide row | the constructor default |

The service layer sitting under *every* scope is the point, not an accident: it is the rollout
knob. Turn `darkMode` on for everybody with one write, then turn it off for the one customer who
filed a bug. Without it, a user-scoped flag could only ever be changed one user at a time.

A `@UserScoped` flag evaluated with **no** user id is not an error — layer 1 is skipped and you get
the service value. A logged-out app has to boot with sane flags.

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { mavenLocal(); mavenCentral() }
}
```

| Module | Artifact | Where |
|---|---|---|
| Annotations, model, resolver | `com.latenighthack.ktflags:ktflags-core` | your flags module |
| KSP processor | `com.latenighthack.ktflags:ktflags-ksp` | applied by the Gradle plugin |
| Client | `com.latenighthack.ktflags:ktflags-client` | your app |
| Server | `com.latenighthack.ktflags:ktflags-server` | your Ktor service |
| SQLite store | `com.latenighthack.ktflags:ktflags-store-sqlite` | your Ktor service |
| Postgres store | `com.latenighthack.ktflags:ktflags-store-postgres` | multi-instance deployments |
| Test kit | `com.latenighthack.ktflags:ktflags-test` | `testImplementation` |

### Your flags module

```kotlin
plugins {
    kotlin("multiplatform")            // or kotlin("jvm")
    id("com.latenighthack.ktflags")
}
```

That is all the wiring. The plugin adds the processor to `kspCommonMainMetadata`, puts the
generated sources on `commonMain`, and adds `ktflags-core` as an `api` dependency. KSP generates
`AppFlagsSchema` next to your class.

<details>
<summary>Wiring KSP by hand instead</summary>

```kotlin
plugins {
    kotlin("multiplatform")
    id("com.google.devtools.ksp") version "2.3.10"
}
dependencies {
    // kspCommonMainMetadata ONLY: the processor emits pure commonMain Kotlin, and adding it to a
    // per-target configuration as well emits the same object twice ("Redeclaration:").
    add("kspCommonMainMetadata", "com.latenighthack.ktflags:ktflags-ksp:0.1.0")
}
kotlin {
    sourceSets.named("commonMain") {
        kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
        dependencies { api("com.latenighthack.ktflags:ktflags-core:0.1.0") }
    }
}
afterEvaluate {
    tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask::class.java)
        .configureEach {
            if (name != "kspCommonMainKotlinMetadata") dependsOn("kspCommonMainKotlinMetadata")
        }
}
```
</details>

## Client

```kotlin
val flags = FeatureFlagsProvider(AppFlagsSchema, FeatureFlagsConfig {
    // Schemeless host:port for plain HTTP; a full https://host:port for TLS. This is ktbuf's
    // contract -- do NOT write "http://".
    serverAddress = "flags.example.com:8080"
    cacheDirectory = filesDir.absolutePath        // REQUIRED on Android
    userIdProvider = { session.userId }
    contextProvider = { mapOf("tenant" to session.tenant) }
    headersProvider = { mapOf("authorization" to "Bearer ${session.token()}") }
})

flags.startIn(applicationScope)                   // load cache, then fetch

if (flags.current().newCheckout) { /* ... */ }
flags.flags.collect { render(it) }                // StateFlow<AppFlags>
```

`FeatureFlagsProvider` is a plain class, never a singleton. That is what lets a test hold two with
different identities, and lets you inject it.

- **Construction does no IO and does not suspend.** Values start at your compile-time defaults.
- **`refresh()` never throws.** Failures come back as `RefreshResult.Failed` and show up in
  `state`; the values you already have are left untouched. A stale flag beats a wrong one.
- **Concurrent refreshes coalesce** into one round trip.
- **After a login,** call `setSubject(userId, context)`. It discards the cache — a snapshot fetched
  for one user is never replayed to another.
- **Swift and JS** cannot consume a `StateFlow`; use `watch(scope) { flags -> }`.

The cache is one protobuf blob: a flat file written atomically on JVM, Android and Apple, and a
`localStorage` entry in the browser (Node falls back to memory unless you supply
`config.persistence`).

## Server

```kotlin
fun main() {
    // Fully constructed and migrated before install -- there is no "starting up" state to guard.
    val repository = SqliteFlagsRepository.open("/var/lib/acme/flags.db")

    lateinit var flags: FeatureFlagsService<AppFlags>
    val server = embeddedServer(CIO, port = 8080) {
        flags = installFeatureFlags(AppFlagsSchema, repository) {
            adminPort = 8081                                  // loopback by default
            adminToken = System.getenv("FLAGS_ADMIN_TOKEN")
        }
        routing {
            get("/checkout") {
                val f = flags.evaluate(FlagSubject(call.userId(), mapOf("tenant" to call.tenant())))
                if (f.newCheckout) newCheckout(call, f.maxItems) else legacyCheckout(call)
            }
        }
    }
    server.start(wait = true)
}
```

`installFeatureFlags` returns the typed handle — wire that into your DI graph. It also mounts the
public `Flags` service on your own listener and opens the admin listener on `adminPort`.

`install(WebSockets)` is **not** required: every ktflags RPC is unary.

If your deployment already runs its own admin listener, set `adminPort = null` and call
`mountAdmin(service)` on that server's routing yourself.

### Security

By default the server believes the `user_id` in the request body, so any client can ask for any
user's flags. That is fine when flags are not secrets. When it is not, replace the extractor:

```kotlin
subjectExtractor = SubjectExtractor { context, request ->
    FlagSubject(
        userId = context.headers["x-acme-user"],
        context = request.context.associate { it.dimension to it.key },
    )
}
```

Read from `context.headers` or `context.query`. **`context.extensions` is always empty** — ktbuf's
`serveAll` drops its `contextProcessor` for unary methods.

## Admin panel

`http://127.0.0.1:8081/flags` — one HTML file, vanilla JS, no build step. It lists every flag with
its scope, type, code default and editable service-wide value; looks up any user id or context key
and shows what that subject actually sees with a provenance badge (`code` / `service` / `subject`);
and surfaces overrides orphaned by a renamed flag.

Behind it is a small JSON API (`/flags/api/...`) and the protobuf `FlagsAdmin` service, both thin
adapters over the same implementation. Auth is `x-admin-token` as a header, a cookie, or
`?token=` when you are pasting a URL into a browser.

## Storage

| Store | Use it when |
|---|---|
| `SqliteFlagsRepository.open(path)` | single node, dev, or a container with a volume |
| `SqliteFlagsRepository.inMemory()` | tests, ephemeral dev servers |
| `PostgresFlagsRepository.open(url)` | more than one server instance |
| `InMemoryFlagsRepository()` | unit tests, the in-process harness |

Two JVMs against one SQLite file will race the revision counter; use Postgres for that.

The Postgres store lives politely in your database: tables are prefixed `ktflags_`, migrations ship
under `classpath:ktflags/db/migration`, and Flyway history goes in `ktflags_schema_history` rather
than the shared table.

## Testing

The strongest advice is architectural: **take `T`, not `FeatureFlags<T>`**. Your flags class is a
data class, so the best fake is the data class.

```kotlin
@Test fun upsellShows() = assertContains(renderCheckout(AppFlags(newCheckout = true)), "upsell")
```

When a component genuinely owns a provider:

```kotlin
val flags = FakeFeatureFlags(AppFlagsSchema)
flags.set { it.copy(darkMode = true) }
flags.failNextRefresh(Codes.UNAVAILABLE)      // exercise the offline path
```

For the real client against the real server with **no network and no port**, use ktbuf's generated
in-process transport:

```kotlin
val service = FeatureFlagsService(AppFlagsSchema, InMemoryFlagsRepository(),
    FeatureFlagsConfig().apply { adminPort = null })

val provider = FeatureFlagsProvider(AppFlagsSchema, FeatureFlagsConfig {
    transport = FlagsTransport.of(LocalFlagsServiceRpc(service.flagsServer))
    persistence = InMemoryFlagsCache()
    userIdProvider = { "u-42" }
})

service.setOverride("newCheckout", FlagSubjectRef.Service, FlagValue.of(true))
provider.refresh()
assertEquals(true, provider.current().newCheckout)
```

And one test worth having in every flags module:

```kotlin
@Test fun schemaIsWellFormed() = assertSchemaWellFormed(AppFlagsSchema)
```

`ktflags-test` also publishes `assertWriteContract` / `assertReadContract` / `assertAdminContract`,
the suite every `FlagsRepository` must satisfy. Run them against your own store if you write one.

## Constraints worth knowing

- **Ktor is pinned to 3.0.2.** ktbuf's server routing truncates or hangs on unary bodies over ~8KB
  under 3.3.x. `WireLargeSchemaTest` is the tripwire. If your host app is on 3.3.x you can still
  serve the admin panel via `mountAdmin(routing)`, which uses no ktbuf.
- **Renaming a flag orphans its overrides.** Rows are keyed by the flag key string. Use
  `@FlagKey("old_name")` to rename the property without moving the data. The admin panel lists
  orphans so you can see when it has happened.
- **ktbuf 1.1.8+ is required** for the macOS targets, and is not on Maven Central yet — run
  `./gradlew :library:publishToMavenLocal :rpc:publishToMavenLocal :server:publishToMavenLocal
  :test:publishToMavenLocal` in a ktbuf checkout first.

## Building from source

```bash
./scripts/bootstrap.sh     # installs protoc-gen-kt (a Go binary); protoc comes from Maven
./gradlew build            # needs JDK 17, and macOS + Xcode for the Apple targets
./gradlew :demo-server:run # http://localhost:8080/checkout, panel on :8081/flags

# Consume it from another project on this machine. Signing is on by default for releases, so turn
# it off for a local publish unless you have a PGP key configured.
./gradlew publishToMavenLocal -PRELEASE_SIGNING_ENABLED=false
```

Android needs `local.properties` with `sdk.dir=...` (gitignored). The Postgres suite needs Docker
and skips itself with a printed reason when it is unavailable.

## License

Apache 2.0.
