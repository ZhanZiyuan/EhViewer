import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RelativePath
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

abstract class NativeTestFixturesTask : DefaultTask() {
    @get:Input abstract val baseline: Property<String>

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @get:Inject abstract val execOperations: ExecOperations

    @get:Inject abstract val fileSystemOperations: FileSystemOperations

    @get:Inject abstract val archiveOperations: ArchiveOperations

    @TaskAction
    fun restore() {
        val archive = temporaryDir.resolve("fixtures.tar")
        execOperations.exec {
            workingDir(outputDirectory.get().asFile.parentFile)
            commandLine("git", "archive", "--format=tar", "--output=${archive.absolutePath}", baseline.get(), "native/test-fixtures")
        }.assertNormalExitValue()
        fileSystemOperations.sync {
            from(archiveOperations.tarTree(archive))
            include("native/test-fixtures/**")
            eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(2).toTypedArray()) }
            includeEmptyDirs = false
            into(outputDirectory)
        }
    }
}
