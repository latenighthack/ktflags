import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.artifacts.Configuration
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File

// A KMP library (via ktflags.kmp-library) plus protobuf codegen from a module-local proto/ dir.
//
// Codegen is driven by protoc directly rather than the com.google.protobuf Gradle plugin (which
// binds to java/android source sets instead of a shared commonMain output). protoc is resolved as
// a pinned Maven artifact; protoc-gen-kt (ktbuf's Kotlin codegen plugin, a Go binary) is discovered
// on PATH with a ~/go/bin fallback. Install it with scripts/bootstrap.sh.
//
// Adapted from social's social.kmp-proto.gradle.kts, which is the reference implementation of this
// pattern in the workspace.
plugins {
    id("ktflags.kmp-library")
}

val libs = the<LibrariesForLibs>()

val protocClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osPart = when {
        os.contains("mac") || os.contains("darwin") -> "osx"
        os.contains("win") -> "windows"
        else -> "linux"
    }
    val archPart = when (arch) {
        "aarch64", "arm64" -> "aarch_64"
        "x86_64", "amd64" -> "x86_64"
        else -> arch
    }
    "$osPart-$archPart"
}

val protocExecutable: Configuration by configurations.creating
dependencies {
    protocExecutable("com.google.protobuf:protoc:${libs.versions.protoc.get()}:$protocClassifier@exe")
}

val protocGenKt: File = run {
    val onPath = System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .map { File(it, "protoc-gen-kt") }
        .firstOrNull { it.canExecute() }
    onPath ?: File(System.getProperty("user.home"), "go/bin/protoc-gen-kt")
}

val generateProto by tasks.registering(Exec::class) {
    group = "build"
    description = "Generate Kotlin protobuf sources via protoc-gen-kt"

    val protoRoot = layout.projectDirectory.dir("proto").asFile
    val protoFiles = fileTree(protoRoot) { include("**/*.proto") }
    val outDir = layout.buildDirectory.dir("generated/ktproto/kotlin")
    val generator = protocGenKt

    inputs.files(protoFiles)
    // A reinstalled protoc-gen-kt can silently change generated output, so make the binary itself
    // an input rather than trusting the .proto files alone to invalidate the task.
    inputs.file(generator)
    outputs.dir(outDir)

    doFirst {
        check(generator.canExecute()) {
            """
            protoc-gen-kt was not found at ${generator.absolutePath}.
            ktflags generates its protobuf types with ktbuf's Go codegen plugin. Install it:
              ./scripts/bootstrap.sh
            or manually:
              go install latenighthack.com/protoc-gen-kt@latest
              export PATH="${'$'}(go env GOPATH)/bin:${'$'}PATH"
            """.trimIndent()
        }

        val protoc = protocExecutable.singleFile.apply { setExecutable(true) }
        val out = outDir.get().asFile
        // protoc appends; a removed message would otherwise leave a stale generated file behind.
        out.deleteRecursively()
        out.mkdirs()

        commandLine(
            buildList {
                add(protoc.absolutePath)
                add("--plugin=protoc-gen-kt=${generator.absolutePath}")
                add("--kt_out=${out.absolutePath}")
                add("-I")
                add(protoRoot.absolutePath)
                addAll(protoFiles.files.map { it.absolutePath })
            },
        )
    }
}

extensions.configure<KotlinMultiplatformExtension> {
    // Generated protobuf code is not explicit-API clean (protoc-gen-kt emits no visibility
    // modifiers), so a module holding generated sources cannot enforce it.
    explicitApi = null

    sourceSets.getByName("commonMain") {
        // Passing the task provider wires the generateProto dependency into every compilation
        // that reads commonMain, with no manual dependsOn needed.
        kotlin.srcDir(generateProto)
        dependencies {
            // Generated proto/rpc types are part of this module's public API and extend ktbuf
            // supertypes (proto.Enum, net.*). Kotlin rejects a public supertype coming from a
            // non-exposed dependency, so these must be `api`, not `implementation`.
            api(libs.ktbuf.library)
            api(libs.ktbuf.rpc)
        }
    }
}
