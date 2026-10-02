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
val printRoomSchema = tasks.register("printRoomSchema") {
    val schemaDir = layout.projectDirectory.dir("schemas").asFile
    doLast {
        schemaDir.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { file ->
            val buffer = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(buffer).use { it.write(file.readBytes()) }
            val encoded = java.util.Base64.getEncoder().encodeToString(buffer.toByteArray())
            println("=====ROOM-SCHEMA-BEGIN ${file.relativeTo(schemaDir).invariantSeparatorsPath} ${file.length()}")
            encoded.chunked(1000).forEach { println("=====ROOM-SCHEMA-LINE $it") }
            println("=====ROOM-SCHEMA-END")
        }
    }
}
tasks.matching { it.name == "testDebugUnitTest" }.configureEach { finalizedBy(printRoomSchema) }
