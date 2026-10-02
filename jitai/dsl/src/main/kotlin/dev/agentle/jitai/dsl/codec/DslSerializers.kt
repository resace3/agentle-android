package dev.agentle.jitai.dsl.codec

import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.nl.DiscoveredProposal
import dev.agentle.jitai.dsl.nl.JitaiProposal
import dev.agentle.jitai.dsl.rule.Condition
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * The serializers of the DSL documents, looked up once through the library's reified `serializer<T>()`. They are the
 * same instances as the plugin-generated `T.serializer()`, which tools that analyze the sources without the
 * serialization compiler plugin (type-resolved detekt, red team testing-build-04) cannot resolve.
 */
internal object DslSerializers {
    val definition: KSerializer<JitaiDefinition> = serializer()

    val condition: KSerializer<Condition> = serializer()

    val proposal: KSerializer<JitaiProposal> = serializer()

    val discovered: KSerializer<DiscoveredProposal> = serializer()
}
