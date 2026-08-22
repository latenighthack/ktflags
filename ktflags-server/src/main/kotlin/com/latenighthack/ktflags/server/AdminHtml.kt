package com.latenighthack.ktflags.server

/**
 * The admin panel, read once from the jar.
 *
 * Served with `respondBytes` rather than Ktor's `staticResources`, which brings caching and range
 * semantics that a single 12KB page does not want, and which would need its own route tree.
 *
 * The resource path is namespaced under `com/latenighthack/ktflags/` so it cannot collide when a
 * consumer builds a fat jar. A `minimize()`d shadow jar can still drop an unreferenced resource,
 * so a missing panel reports the likely cause rather than serving a blank page.
 */
private val panel: ByteArray by lazy {
    val resource = "com/latenighthack/ktflags/admin/admin.html"
    FeatureFlagsService::class.java.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
        ?: error(
            "ktflags: $resource is missing from the classpath. If this is a shadow/fat jar, check " +
                "that resource minimization has not stripped it.",
        )
}

internal fun adminHtml(): ByteArray = panel
