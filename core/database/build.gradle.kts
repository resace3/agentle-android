import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

plugins {
    id("agentle.android.library")
    id("agentle.room")
    id("agentle.hilt")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:time"))
    // The database key, key manager and paths are part of this module's API (DatabaseProvider, LocalDataReset).
    api(project(":core:security"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.serialization.json)
    // Encryption at rest (docs/ARCHITECTURE.md §5.4): SQLCipherDriver for Room 3; the key comes from :core:security.
    implementation(libs.sqlcipher.android)
    testImplementation(project(":core:testing"))
}

// TEMPORARY: prints the schema Room exports during compilation (gzip + base64, between markers) so it can be copied
// from the CI log into core/database/schemas/ (Google Maven, and so the Room compiler, is not reachable locally).
// Removed in the commit that adds the schema file.
val printRoomSchema =
    tasks.register("printRoomSchema") {
        val roots = listOf(layout.projectDirectory.dir("schemas").asFile, layout.buildDirectory.get().asFile)
        doLast {
            val printed = mutableSetOf<String>()
            roots.forEach { root ->
                val files =
                    root.walkTopDown().filter {
                        it.isFile && it.extension == "json" &&
                            it.parentFile.name.endsWith("AgentleDatabase")
                    }
                files.forEach { file ->
                    val bytes = file.readBytes()
                    val buffer = ByteArrayOutputStream()
                    GZIPOutputStream(buffer).use { it.write(bytes) }
                    val encoded = Base64.getEncoder().encodeToString(buffer.toByteArray())
                    if (printed.add(encoded)) {
                        println("=====ROOM-SCHEMA-BEGIN ${file.relativeTo(root).invariantSeparatorsPath} ${bytes.size}")
                        encoded.chunked(1000).forEach { println("=====ROOM-SCHEMA-LINE $it") }
                        println("=====ROOM-SCHEMA-END")
                    }
                }
            }
        }
    }
tasks.matching { it.name == "testDebugUnitTest" }.configureEach { finalizedBy(printRoomSchema) }
