import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Test DDL comes from the tracked historical schema, independently of today's database code. */
abstract class GenerateDatabaseMigrationFixtures : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val historicalSchema: RegularFileProperty

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = JsonSlurper().parse(historicalSchema.get().asFile) as Map<*, *>
        val database = root["database"] as Map<*, *>
        check(database["version"] == 17)
        val statements = mutableListOf<String>()
        for (value in database["entities"] as List<*>) {
            val entity = value as Map<*, *>
            val name = entity["tableName"] as String
            fun sql(text: Any?) = (text as String).replace("\${TABLE_NAME}", name)
            statements += sql(entity["createSql"])
            for (index in entity["indices"] as? List<*> ?: emptyList<Any>()) statements += sql((index as Map<*, *>)["createSql"])
            for (trigger in entity["contentSyncTriggers"] as? List<*> ?: emptyList<Any>()) statements += sql(trigger)
        }
        for (value in database["views"] as? List<*> ?: emptyList<Any>()) statements += (value as Map<*, *>)["createSql"] as String
        statements += (database["setupQueries"] as List<*>).map { it as String }
        statements += "PRAGMA user_version=17"
        fun literal(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("\r", "\\r").replace("\n", "\\n") + "\""
        val destination = outputDirectory.file("com/ehviewer/core/data/fixture/LegacySchemaFixtures.kt").get().asFile
        check(destination.parentFile.isDirectory || destination.parentFile.mkdirs())
        destination.writeText(
            "// Generated from the tracked version 17 schema; test-only.\npackage com.ehviewer.core.data.fixture\n\n" +
                "internal val schema17Statements = listOf(\n" + statements.joinToString(",\n") { "    ${literal(it)}" } + ",\n)\n",
        )
    }
}
