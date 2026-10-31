import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.register

/** Put the same reviewed SQLite runtime on root and standalone Desktop classpaths. */
class DesktopSqliteConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.withPlugin("java") {
            val catalog = target.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
            val version = catalog.findVersion("androidx-sqlite").get().requiredVersion
            val upstream = target.configurations.create("desktopSqliteSource").apply { isTransitive = false }
            val platform = when {
                System.getProperty("os.name").startsWith("Mac") -> "osx"
                System.getProperty("os.name").startsWith("Windows") -> "windows"
                System.getProperty("os.name") == "Linux" -> "linux"
                else -> error("Unsupported desktop SQLite operating system")
            } + "_" + when (System.getProperty("os.arch")) {
                "aarch64", "arm64" -> "arm64"
                "amd64", "x86_64" -> "x64"
                else -> error("Unsupported desktop SQLite architecture")
            }
            val selected = target.tasks.register<SelectDesktopSqlite>("selectDesktopSqlite") {
                source.from(upstream)
                this.target.set(platform)
                sourceSha256.set("e61efb0647288b486e595950724cc53c4f15eeeca03ce2184751d02de3cfaa17")
                destination.set(target.layout.buildDirectory.file("native-dependencies/sqlite-bundled-$platform-$version.jar"))
            }
            target.configurations.named("runtimeClasspath") {
                exclude(mapOf("group" to "androidx.sqlite", "module" to "sqlite-bundled"))
                exclude(mapOf("group" to "androidx.sqlite", "module" to "sqlite-bundled-jvm"))
            }
            target.dependencies.add(upstream.name, "androidx.sqlite:sqlite-bundled-jvm:$version")
            target.dependencies.add("runtimeOnly", target.files(selected.flatMap { it.destination }))
        }
    }
}
