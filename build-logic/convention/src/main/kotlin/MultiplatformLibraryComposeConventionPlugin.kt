import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.ehviewer.configureKotlinCompose
import com.ehviewer.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

@Suppress("unused")
class MultiplatformLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        apply<MultiplatformLibraryConventionPlugin>()
        apply(plugin = libs.plugins.compose.compiler.get().pluginId)
        apply(plugin = libs.plugins.compose.multiplatform.get().pluginId)
        apply(plugin = libs.plugins.composeCompilerReportGenerator.get().pluginId)

        plugins.withId(libs.plugins.android.kotlin.multiplatform.library.get().pluginId) {
            configure<KotlinMultiplatformExtension> {
                configure<KotlinMultiplatformAndroidLibraryTarget> {
                    androidResources.enable = true
                }
            }
        }

        configure<KotlinMultiplatformExtension> {
            compilerOptions {
                configureKotlinCompose()
            }
        }
    }
}
