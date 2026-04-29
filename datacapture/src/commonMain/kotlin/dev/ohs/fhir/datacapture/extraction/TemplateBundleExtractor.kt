/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.ohs.fhir.datacapture.extraction

import dev.ohs.fhir.datacapture.extraction.repository.ResolveTemplateUseCase
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Extension
import dev.ohs.fhir.model.r4.FhirR4Json
import dev.ohs.fhir.model.r4.Questionnaire
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.fhir.model.r4.Resource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.random.Random

/**
 * High-level adapter that runs the template engine over a QuestionnaireResponse and turns the
 * resolved JSON-like output back into a FHIR Bundle.
 */
class TemplateBundleExtractor(
    private val resolveTemplateUseCase: ResolveTemplateUseCase =
        ResolveTemplateUseCase(DefaultTemplateRepository()),
    private val fhirJson: FhirR4Json = FhirR4Json(),
    private val templateJson: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * Resolves one or more mapping templates, executes them against the response payload, and
     * packages the resolved resources into a Bundle for logging or persistence.
     *
     * Implementers have two ways to supply templates:
     * - pass `templateJsons` explicitly, which is ideal when templates live in separate files
     * - omit `templateJsons` and let the extractor read them from questionnaire extensions
     */
    fun extract(
        questionnaireResponse: QuestionnaireResponse,
        questionnaire: Questionnaire? = null,
        templateJsons: List<String>? = null,
    ): Bundle {
        val resourceNode =
            templateJson
                .parseToJsonElement(fhirJson.encodeToString(questionnaireResponse))
                .toDynamicNode()

        val templates =
            resolveTemplates(
                questionnaire = questionnaire,
                templateJsons = templateJsons,
            )

        val resolved =
            resolveTemplateUseCase(
                TemplateExecutionRequest(
                    resource = resourceNode,
                    templates = templates,
                    context = mapOf("resource" to resourceNode),
                    options =
                        FpOptions(
                            modelProfile = ModelProfile.FHIR_R4,
                            userFunctions = extractionUserFunctions(),
                        ),
                )
            )

        val entryNodes =
            flattenCollection(resolved.values)
                .filterNotNull()
                .mapNotNull { value ->
                    normalizeBundleEntry(value).getOrElse { error ->
                        println("Skipping invalid extracted entry: ${error.message}")
                        return@mapNotNull null
                    }
                }

        val bundleNode: DynamicNode =
            mapOf(
                "resourceType" to "Bundle",
                "type" to if (entryNodes.any { it["request"] != null }) "transaction" else "collection",
                "entry" to entryNodes,
            )

        return fhirJson.decodeFromString(bundleNode.toJsonElement().toString()) as Bundle
    }

    /** Normalizes one or more template payloads into the engine's list contract. */
    private fun parseTemplates(templateJsons: List<String>): List<DynamicNode> =
        templateJsons.flatMap { templatePayload ->
            normalizeToCollection(templateJson.parseToJsonElement(templatePayload).toDynamicNode())
        }

    /**
     * Resolves templates from explicit payloads first, then questionnaire extensions, and finally
     * from the questionnaire's contained SDC extraction bundle when present.
     */
    private fun resolveTemplates(
        questionnaire: Questionnaire?,
        templateJsons: List<String>?,
    ): List<DynamicNode> =
        templateJsons?.takeUnless { it.isEmpty() }?.let(::parseTemplates)
            ?: questionnaire?.resolveEmbeddedTemplates()?.takeUnless { it.isEmpty() }
            ?: error(
                "No extraction templates were provided and none were found on the questionnaire."
            )

    /** Parses one resolved template object back into a strongly typed FHIR resource. */
    private fun parseResource(value: DynamicNode): Result<Resource> {
        if (value !is Map<*, *>) {
            return Result.failure(
                IllegalArgumentException(
                    "Template engine must return FHIR resource objects, but got ${value.describeDynamicType()}."
                )
            )
        }

        return runCatching {
            fhirJson.decodeFromString(value.toJsonElement().toString())
        }
    }

    /**
     * Accepts either a plain resource object or a pre-wrapped Bundle.entry object from the
     * template layer and normalizes it to Bundle.entry JSON.
     */
    private fun normalizeBundleEntry(value: DynamicNode): Result<DynamicObject> {
        if (value !is Map<*, *>) {
            return Result.failure(
                IllegalArgumentException(
                    "Template engine must return FHIR resources or Bundle.entry objects, but got ${value.describeDynamicType()}."
                )
            )
        }

        val objectValue = value as DynamicObject
        return when {
            objectValue["resourceType"] != null -> {
                parseResource(value).map { mapOf("resource" to value) }
            }

            objectValue["resource"] != null -> {
                parseBundleEntry(value).map { objectValue }
            }

            else -> {
                Result.failure(
                    IllegalArgumentException(
                        "Template engine must return FHIR resources or Bundle.entry objects, but got object without resourceType/resource."
                    )
                )
            }
        }
    }

    /** Validates an entry-shaped object by round-tripping it through a one-entry Bundle. */
    private fun parseBundleEntry(value: DynamicNode): Result<Bundle.Entry> {
        if (value !is Map<*, *>) {
            return Result.failure(
                IllegalArgumentException(
                    "Bundle.entry templates must be objects, but got ${value.describeDynamicType()}."
                )
            )
        }

        return runCatching {
            val bundleNode: DynamicNode =
                mapOf(
                    "resourceType" to "Bundle",
                    "type" to "transaction",
                    "entry" to listOf(value),
                )
            val bundle: Bundle =
                fhirJson.decodeFromString(bundleNode.toJsonElement().toString()) as Bundle
            bundle.entry.firstOrNull()
                ?: error("Bundle.entry template resolved to an empty bundle entry list.")
        }
    }

    /**
     * Registers per-extraction helpers that templates can rely on without custom engine setup.
     *
     * `uuid()` returns a fresh UUID string on every call.
     * `uuid('patient')` memoizes the UUID for the provided key so multiple resources can share it.
     */
    private fun extractionUserFunctions(): Map<String, UserFunctionDefinition> {
        val keyedUuids = mutableMapOf<String, String>()

        return mapOf(
            "uuid" to
                    UserFunctionDefinition(arity = setOf(0, 1)) { _, args ->
                        val uuid =
                            if (args.isEmpty()) {
                                randomUuidV4()
                            } else {
                                val key =
                                    args.firstOrNull()
                                        ?.toString()
                                        ?.takeIf { it.isNotBlank() }
                                        ?: error("uuid(name) requires a non-empty lookup key")
                                keyedUuids.getOrPut(key, ::randomUuidV4)
                            }
                        listOf(uuid)
                    }
        )
    }

    /** Reads every extraction-template payload declared on the questionnaire extension. */
    private fun Questionnaire.mappingTemplatePayloads(): List<String> =
        extension
            .filter { it.url == MAPPING_TEMPLATE_EXTENSION_URL }
            .mapNotNull { it.templatePayload() }

    /**
     * Resolves templates declared directly on questionnaire extensions or through an SDC
     * contained extraction bundle reference.
     */
    private fun Questionnaire.resolveEmbeddedTemplates(): List<DynamicNode> =
        mappingTemplatePayloads().takeUnless { it.isEmpty() }?.let(::parseTemplates)
            ?: containedTemplatePayloads()

    /**
     * Converts a contained SDC extraction bundle into the engine's regular JSON-template shape.
     */
    private fun Questionnaire.containedTemplatePayloads(): List<DynamicNode> {
        val questionnaireJson = toJsonObject()
        val containedBundleId =
            questionnaireJson
                .extensionValues(TEMPLATE_EXTRACT_BUNDLE_EXTENSION_URL)
                .firstNotNullOfOrNull { extension ->
                    extension.valueReferenceId()
                } ?: return emptyList()

        val containedBundle =
            questionnaireJson.containedResources().firstOrNull { resource ->
                resource.resourceType() == "Bundle" &&
                        resource.idValue() == containedBundleId
            } ?: return emptyList()

        return containedBundle.entryTemplateObjects().map { entry ->
            convertContainedTemplateNode(entry)
        }
    }

    /**
     * Rewrites the SDC extraction-bundle representation into the lighter template format already
     * supported by the engine.
     */
    private fun convertContainedTemplateNode(
        element: JsonElement,
        insideArray: Boolean = false,
    ): DynamicNode =
        when (element) {
            JsonNull -> null

            is JsonArray -> element.map { convertContainedTemplateNode(it, insideArray = true) }

            is JsonObject -> convertContainedTemplateObject(element, insideArray)

            is JsonPrimitive -> element.toDynamicNode()
        }

    private fun convertContainedTemplateObject(
        element: JsonObject,
        insideArray: Boolean,
    ): DynamicNode {
        val contextExpression =
            element
                .extensionValues(TEMPLATE_EXTRACT_CONTEXT_EXTENSION_URL)
                .firstNotNullOfOrNull { extension -> extension.valueStringLike() }
        val valueExpression =
            element
                .extensionValues(TEMPLATE_EXTRACT_VALUE_EXTENSION_URL)
                .firstNotNullOfOrNull { extension -> extension.valueStringLike() }

        val cleanedExtensions =
            element
                .extensionValues()
                .filterNot { extension ->
                    extension.urlValue() == TEMPLATE_EXTRACT_CONTEXT_EXTENSION_URL ||
                            extension.urlValue() == TEMPLATE_EXTRACT_VALUE_EXTENSION_URL
                }
                .takeIf { it.isNotEmpty() }

        val convertedChildren = linkedMapOf<String, DynamicNode>()
        element.forEach { (key, value) ->
            when {
                key == "extension" && cleanedExtensions != null -> {
                    convertedChildren[key] =
                        convertContainedTemplateNode(
                            JsonArray(cleanedExtensions),
                            insideArray = true
                        )
                }

                key != "extension" -> {
                    convertedChildren[key] = convertContainedTemplateNode(value)
                }
            }
        }

        if (valueExpression != null && convertedChildren.isEmpty()) {
            val templateNode = valueExpression.asTemplateValue(insideArray)
            return contextExpression?.wrapAsContextBlock(templateNode) ?: templateNode
        }

        val resolvedObject = linkedMapOf<String, DynamicNode>()
        convertedChildren.forEach { (key, value) ->
            if (key.startsWith("_")) {
                resolvedObject[key.removePrefix("_")] = value
            } else {
                resolvedObject[key] = value
            }
        }

        val convertedObject: DynamicNode = resolvedObject
        return contextExpression?.wrapAsContextBlock(convertedObject) ?: convertedObject
    }

    private fun Extension.templatePayload(): String? =
        value?.asString()?.value?.value
            ?: value?.asMarkdown()?.value?.value
            ?: value?.asUri()?.value?.value
            ?: value?.asCanonical()?.value?.value
            ?: value?.asCode()?.value?.value

    private fun JsonElement.toDynamicNode(): DynamicNode =
        when (this) {
            JsonNull -> null

            is JsonArray -> map { it.toDynamicNode() }

            is JsonObject -> entries.associate { (key, value) -> key to value.toDynamicNode() }

            is JsonPrimitive -> {
                when {
                    isString -> content
                    booleanOrNull != null -> booleanOrNull
                    longOrNull != null -> longOrNull
                    doubleOrNull != null -> doubleOrNull
                    else -> content
                }
            }
        }

    private fun DynamicNode.toJsonElement(): JsonElement =
        when (this) {
            null -> JsonNull

            is Map<*, *> ->
                JsonObject(
                    entries.associate { (key, value) ->
                        require(key is String) {
                            "Resolved FHIR resource objects must use string keys, but found key '$key'."
                        }
                        key to value.toJsonElement()
                    }
                )

            is List<*> -> JsonArray(map { it.toJsonElement() })

            is String -> JsonPrimitive(this)

            is Boolean -> JsonPrimitive(this)

            is Number -> templateJson.parseToJsonElement(toString())

            else -> error("Cannot convert ${describeDynamicType()} to JSON.")
        }

    private fun DynamicNode.describeDynamicType(): String =
        when (this) {
            null -> "null"
            is Map<*, *> -> "object"
            is List<*> -> "array"
            is String -> "string"
            is Boolean -> "boolean"
            is Number -> "number"
            else -> "unsupported value"
        }

    private fun Questionnaire.toJsonObject(): JsonObject =
        templateJson.parseToJsonElement(fhirJson.encodeToString(this)).jsonObject

    private fun JsonObject.containedResources(): List<JsonObject> =
        this["contained"]?.jsonArray?.mapNotNull { it as? JsonObject } ?: emptyList()

    private fun JsonObject.entryTemplateObjects(): List<JsonObject> =
        this["entry"]?.jsonArray?.mapNotNull { entryElement ->
            val entry = entryElement as? JsonObject ?: return@mapNotNull null
            val resource = entry["resource"] as? JsonObject ?: return@mapNotNull null
            JsonObject(entry + ("resource" to resource))
        } ?: emptyList()

    private fun JsonObject.extensionValues(url: String? = null): List<JsonObject> =
        this["extension"]?.jsonArray?.mapNotNull { it as? JsonObject }?.filter { extension ->
            url == null || extension.urlValue() == url
        } ?: emptyList()

    private fun JsonObject.resourceType(): String? = this["resourceType"]?.primitiveContent()

    private fun JsonObject.idValue(): String? = this["id"]?.primitiveContent()

    private fun JsonObject.urlValue(): String? = this["url"]?.primitiveContent()

    private fun JsonObject.valueStringLike(): String? =
        this["valueString"]?.primitiveContent()
            ?: this["valueMarkdown"]?.primitiveContent()
            ?: this["valueUri"]?.primitiveContent()
            ?: this["valueCanonical"]?.primitiveContent()
            ?: this["valueCode"]?.primitiveContent()

    private fun JsonObject.valueReferenceId(): String? =
        (this["valueReference"] as? JsonObject)
            ?.get("reference")
            ?.primitiveContent()
            ?.removePrefix("#")

    private fun JsonElement.primitiveContent(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private fun String.asTemplateValue(insideArray: Boolean): String =
        if (insideArray) {
            "{[ $this ]}"
        } else {
            "{{ $this }}"
        }

    private fun String.wrapAsContextBlock(node: DynamicNode): DynamicNode =
        mapOf("{{ $this }}" to node)

    private fun randomUuidV4(): String {
        val bytes = ByteArray(16) { Random.Default.nextInt(0, 256).toByte() }
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()

        val hex =
            bytes.joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

        return buildString(36) {
            append(hex, 0, 8)
            append('-')
            append(hex, 8, 12)
            append('-')
            append(hex, 12, 16)
            append('-')
            append(hex, 16, 20)
            append('-')
            append(hex, 20, 32)
        }
    }

    companion object {
        const val MAPPING_TEMPLATE_EXTENSION_URL: String =
            "http://dev.ohs.fhir/fhir-extensions/fhir-path-mapping-language"
        const val TEMPLATE_EXTRACT_BUNDLE_EXTENSION_URL: String =
            "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractBundle"
        const val TEMPLATE_EXTRACT_CONTEXT_EXTENSION_URL: String =
            "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractContext"
        const val TEMPLATE_EXTRACT_VALUE_EXTENSION_URL: String =
            "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue"
    }
}
