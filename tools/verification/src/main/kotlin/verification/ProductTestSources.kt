package verification

import java.nio.file.Path

/** Declare shared directories once, then count the actual Kotlin inputs separately for each host. */
fun productTestSources(repository: Path, module: String, target: String): List<Path> {
    require(module in setOf("network", "shared-ui") && target in setOf("android", "jvm", "ios"))
    val declarations = readJson(repository.resolve(".github/ci-plan.json"))["product_test_sources"].obj()
    require(declarations.keys == setOf("network", "shared-ui"))
    val targets = declarations.getValue(module).obj()
    require(targets.keys == setOf("android", "jvm", "ios"))
    val roots = targets.mapValues { (_, value) ->
        value.array().map { it as String }.also { names ->
            require(names.isNotEmpty() && names.distinct() == names && "commonTest" in names && names.all { Regex("[A-Za-z][A-Za-z0-9]*Test").matches(it) }) { "Invalid declared product test directory" }
        }
    }
    val source = repository.resolve(if (module == "network") "core/network/src" else "app/shared/src")
    val declared = roots.values.flatten().toSet()
    val actual = filesBelow(source, ".kt").filter { source.relativize(it).getName(0).toString().endsWith("Test") }
    require(actual.all { source.relativize(it).getName(0).toString() in declared }) { "Product test directory is absent from the declared target plan" }
    return roots.getValue(target).flatMap { filesBelow(source.resolve(it), ".kt") }.distinct().sorted()
}
