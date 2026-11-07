import com.ehviewer.GeneratePlatformIcons
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

class PlatformIconsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        tasks.register<GeneratePlatformIcons>("generatePlatformIcons") {
            vector.set(layout.projectDirectory.file("../../app/src/main/res/drawable/ic_launcher_foreground.xml"))
            destination.set(layout.buildDirectory.dir("platform-icons"))
        }
        Unit
    }
}
