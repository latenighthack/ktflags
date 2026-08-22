// The sample "flags" module a consumer would write: one annotated data class and nothing else.
//
// It applies the published plugin by id rather than wiring KSP by hand, so the in-repo demo
// dogfoods exactly what ships. `ktflags.useProjectDependencies=true` in the root gradle.properties
// makes the plugin resolve the processor from the working tree instead of Maven.
plugins {
    id("ktflags.kmp-library")
    id("com.latenighthack.ktflags")
}
