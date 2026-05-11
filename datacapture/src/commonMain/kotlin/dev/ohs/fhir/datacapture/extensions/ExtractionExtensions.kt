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

package dev.ohs.fhir.datacapture.extensions

import dev.ohs.fhir.model.r4.Extension
import dev.ohs.fhir.model.r4.Questionnaire
import dev.ohs.fhir.model.r4.Resource

internal const val EXTENSION_TEMPLATE_EXTRACT_URL =
  "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtract"

internal const val EXTENSION_TEMPLATE_EXTRACT_BUNDLE_URL =
  "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractBundle"

internal const val EXTENSION_TEMPLATE_EXTRACT_CONTEXT_URL =
  "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractContext"

internal const val EXTENSION_TEMPLATE_EXTRACT_VALUE_URL =
  "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue"

internal const val EXTENSION_EXTRACT_ALLOCATE_ID_URL =
  "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-extractAllocateId"

internal data class TemplateExtractDefinition(
  val templateReference: String,
  val fullUrlExpression: String? = null,
  val resourceIdExpression: String? = null,
  val ifNoneMatchExpression: String? = null,
  val ifModifiedSinceExpression: String? = null,
  val ifMatchExpression: String? = null,
  val ifNoneExistExpression: String? = null,
)

internal val Questionnaire.templateExtractExtensions: List<TemplateExtractDefinition>
  get() =
    extension
      .filter { it.url == EXTENSION_TEMPLATE_EXTRACT_URL }
      .mapNotNull { it.asTemplateExtractDefinition() }

internal val Questionnaire.Item.templateExtractExtensions: List<TemplateExtractDefinition>
  get() =
    extension
      .filter { it.url == EXTENSION_TEMPLATE_EXTRACT_URL }
      .mapNotNull { it.asTemplateExtractDefinition() }

internal val Questionnaire.templateExtractBundleReference: String?
  get() =
    extension
      .firstOrNull { it.url == EXTENSION_TEMPLATE_EXTRACT_BUNDLE_URL }
      ?.value
      ?.asReference()
      ?.value
      ?.reference
      ?.value

internal val Questionnaire.allocateIdVariableNames: List<String>
  get() =
    extension
      .filter { it.url == EXTENSION_EXTRACT_ALLOCATE_ID_URL }
      .mapNotNull { it.stringValue()?.normalizedVariableName() }

internal val Questionnaire.Item.allocateIdVariableNames: List<String>
  get() =
    extension
      .filter { it.url == EXTENSION_EXTRACT_ALLOCATE_ID_URL }
      .mapNotNull { it.stringValue()?.normalizedVariableName() }

internal fun Questionnaire.findContainedResource(reference: String): Resource? {
  val containedReference = if (reference.startsWith("#")) reference else "#$reference"
  return contained.firstOrNull { resource -> resource.id?.let { "#$it" } == containedReference }
}

private fun Extension.asTemplateExtractDefinition(): TemplateExtractDefinition? {
  val templateReference =
    extension.firstOrNull { it.url == "template" }?.value?.asReference()?.value?.reference?.value
      ?: return null

  return TemplateExtractDefinition(
    templateReference = templateReference,
    fullUrlExpression = extension.firstOrNull { it.url == "fullUrl" }?.stringValue(),
    resourceIdExpression = extension.firstOrNull { it.url == "resourceId" }?.stringValue(),
    ifNoneMatchExpression = extension.firstOrNull { it.url == "ifNoneMatch" }?.stringValue(),
    ifModifiedSinceExpression =
      extension.firstOrNull { it.url == "ifModifiedSince" }?.stringValue(),
    ifMatchExpression = extension.firstOrNull { it.url == "ifMatch" }?.stringValue(),
    ifNoneExistExpression = extension.firstOrNull { it.url == "ifNoneExist" }?.stringValue(),
  )
}

private fun Extension.stringValue(): String? =
  value?.asString()?.value?.value
    ?: value?.asUri()?.value?.value
    ?: value?.asCanonical()?.value?.value
    ?: value?.asCode()?.value?.value
    ?: value?.asMarkdown()?.value?.value

private fun String.normalizedVariableName(): String = removePrefix("%")
