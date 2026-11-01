package com.ehviewer

import java.io.File
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/** Apple's asset compiler produces real iPhone/iPad icon renditions and their bundle metadata. */
abstract class CompileAppleAppIcons : DefaultTask() {
    @get:InputDirectory abstract val catalog: DirectoryProperty

    @get:Input abstract val sdk: Property<String>

    @get:OutputDirectory abstract val destination: DirectoryProperty

    @TaskAction fun compile() {
        check(sdk.get() in setOf("iphoneos", "iphonesimulator"))
        val output = destination.get().asFile.also { it.mkdirs() }
        NativeBuildCommand(output).run(
            "actool",
            output,
            listOf(
                "xcrun", "--sdk", sdk.get(), "actool", "--compile", output.absolutePath,
                "--platform", sdk.get(), "--minimum-deployment-target", "15.0",
                "--target-device", "iphone", "--target-device", "ipad", "--app-icon", "AppIcon",
                "--output-partial-info-plist", output.resolve("PartialInfo.plist").absolutePath,
                catalog.get().asFile.absolutePath,
            ),
        )
        check(output.resolve("Assets.car").isFile && output.resolve("PartialInfo.plist").isFile)
    }
}

class MergeAppleIconInfo(private val partial: File, private val app: File, private val evidence: File) : Action<Task>, java.io.Serializable {
    override fun execute(task: Task) {
        // PlistBuddy parses its own command string, so quote this filename independently of any shell.
        val quoted = partial.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")
        NativeBuildCommand(evidence).run("merge-icon-info", app, listOf("/usr/libexec/PlistBuddy", "-c", "Merge \"$quoted\"", app.resolve("Info.plist").absolutePath))
    }
}
