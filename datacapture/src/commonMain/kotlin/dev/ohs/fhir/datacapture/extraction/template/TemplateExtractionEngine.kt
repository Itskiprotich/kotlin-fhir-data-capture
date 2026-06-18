/*
 * Copyright 2026 Open Health Stack Foundation
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
package dev.ohs.fhir.datacapture.extraction.template

import dev.ohs.fhir.datacapture.extensions.allocateIdVariableNames
import dev.ohs.fhir.datacapture.extensions.findContainedResource
import dev.ohs.fhir.datacapture.extensions.isRepeatedGroup
import dev.ohs.fhir.datacapture.extensions.templateExtractExtensions
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Instant
import dev.ohs.fhir.model.r4.OperationOutcome
import dev.ohs.fhir.model.r4.Questionnaire
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Stateful worker for a single template extraction run. */
internal class TemplateExtractionEngine(
  private val questionnaire: Questionnaire,
  private val questionnaireResponse: QuestionnaireResponse,
) {
  private val evaluator = TemplateFhirPathEvaluator()
  private val valueConverter = TemplateValueConverter()
  private val treeProcessor = TemplateTreeProcessor(evaluator, valueConverter)

  /**
   * Runs extraction in the same order SDC expects consumers to reason about it: questionnaire-level
   * templates first, then item-level templates against each logical item occurrence, while carrying
   * forward any allocated `%variable` values that later templates may reference.
   */
  fun extract(): TemplateExtractionResult {
    val entries = mutableListOf<Bundle.Entry>()
    val issues = mutableListOf<TemplateExtractionIssue>()
    val onIssue: (TemplateExtractionIssue) -> Unit = issues::add
    val rootVariables = allocateIdVariables(questionnaire.allocateIdVariableNames)
    val rootScope =
      TemplateEvaluationScope(
        questionnaire = questionnaire,
        questionnaireResponse = questionnaireResponse,
        questionnaireItem = null,
        context = questionnaireResponse,
        variables = rootVariables,
      )

    questionnaire.templateExtractExtensions.forEach { definition ->
      extractTemplate(definition, rootScope, "Questionnaire", onIssue).let(entries::addIfPresent)
    }

    traverseQuestionnaireItems(
      questionnaireItems = questionnaire.item,
      responseItemsByLinkId = questionnaireResponse.item.groupBy { it.linkId.value },
      inheritedVariables = rootVariables,
      outputEntries = entries,
      onIssue = onIssue,
    )

    return TemplateExtractionResult(
      bundle = Bundle(type = Enumeration(value = Bundle.BundleType.Transaction), entry = entries),
      operationOutcome = issues.takeIf { it.isNotEmpty() }?.toOperationOutcome(),
    )
  }

  /**
   * Walks the Questionnaire item tree in lockstep with the matching QuestionnaireResponse items.
   *
   * For the current sibling level, [responseItemsByLinkId] provides an indexed view of the response
   * items so each Questionnaire item can retrieve its matching response items in constant time
   * instead of scanning the full list repeatedly. This lookup is intentionally scoped to the
   * current branch of the response tree: when recursion moves into child items, a new map is built
   * from that branch's child response items so nested extraction only sees the responses that
   * belong to the current parent context.
   *
   * Each matched item is normalized into one or more extraction contexts to support repeated groups
   * and repeating questions, item-level templates are evaluated for each logical occurrence, and
   * any allocated `%variables` are carried forward to descendants. If multiple response items are
   * found for a non-repeating Questionnaire item, extraction continues with a warning and uses the
   * first logical occurrence for direct extraction.
   */
  private fun traverseQuestionnaireItems(
    questionnaireItems: List<Questionnaire.Item>,
    responseItemsByLinkId: Map<String?, List<QuestionnaireResponse.Item>>,
    inheritedVariables: Map<String, Any?>,
    outputEntries: MutableList<Bundle.Entry>,
    onIssue: (TemplateExtractionIssue) -> Unit,
  ) {
    questionnaireItems.forEach { questionnaireItem ->
      val matchingResponseItems = responseItemsByLinkId[questionnaireItem.linkId.value].orEmpty()
      if (matchingResponseItems.isEmpty()) return@forEach

      if (
        !questionnaireItem.isRepeatedGroup &&
          questionnaireItem.repeats?.value != true &&
          matchingResponseItems.size > 1
      ) {
        onIssue(
          TemplateExtractionIssue(
            severity = OperationOutcome.IssueSeverity.Warning,
            code = OperationOutcome.IssueType.Invalid,
            diagnostics =
              "Multiple QuestionnaireResponse items were found for non-repeating item '${questionnaireItem.linkId.value}'. Only the first occurrence will be used for direct extraction.",
            expressionPath = questionnaireItem.linkId.value,
          )
        )
      }

      questionnaireItem.toExtractionContexts(matchingResponseItems).forEach { extractionContext ->
        val currentVariables =
          inheritedVariables + allocateIdVariables(questionnaireItem.allocateIdVariableNames)
        val scope =
          TemplateEvaluationScope(
            questionnaire = questionnaire,
            questionnaireResponse = questionnaireResponse,
            questionnaireItem = questionnaireItem,
            context = extractionContext.baseContext,
            variables = currentVariables,
          )

        questionnaireItem.templateExtractExtensions.forEach { definition ->
          extractTemplate(
              definition = definition,
              scope = scope,
              path = questionnaireItem.linkId.value ?: "Questionnaire.item",
              onIssue = onIssue,
            )
            .let(outputEntries::addIfPresent)
        }

        if (questionnaireItem.item.isNotEmpty()) {
          traverseQuestionnaireItems(
            questionnaireItems = questionnaireItem.item,
            responseItemsByLinkId =
              extractionContext.childResponseItems.groupBy { it.linkId.value },
            inheritedVariables = currentVariables,
            outputEntries = outputEntries,
            onIssue = onIssue,
          )
        }
      }
    }
  }

  private fun extractTemplate(
    definition: TemplateExtractDefinition,
    scope: TemplateEvaluationScope,
    path: String,
    onIssue: (TemplateExtractionIssue) -> Unit,
  ): Bundle.Entry? {
    val templateResource = questionnaire.findContainedResource(definition.templateReference)
    if (templateResource == null) {
      onIssue(
        TemplateExtractionIssue(
          severity = OperationOutcome.IssueSeverity.Error,
          code = OperationOutcome.IssueType.Required,
          diagnostics =
            "Contained template '${definition.templateReference}' was not found in the questionnaire.",
          expressionPath = path,
        )
      )
      return null
    }

    val templateJson = valueConverter.resourceToJson(templateResource).withoutTemplateId()
    val resourceType =
      templateJson["resourceType"]?.jsonPrimitive?.contentOrNull
        ?: run {
          onIssue(
            TemplateExtractionIssue(
              severity = OperationOutcome.IssueSeverity.Error,
              code = OperationOutcome.IssueType.Invalid,
              diagnostics =
                "Contained template '${definition.templateReference}' is missing resourceType.",
              expressionPath = path,
            )
          )
          return null
        }

    // The JSON tree pass applies templateExtractContext/templateExtractValue recursively before we
    // materialize the resource back into typed Kotlin FHIR models.
    val processedResources = treeProcessor.processResource(templateJson, scope, onIssue)
    if (processedResources.isEmpty()) return null
    if (processedResources.size > 1) {
      onIssue(
        TemplateExtractionIssue(
          severity = OperationOutcome.IssueSeverity.Warning,
          code = OperationOutcome.IssueType.Invalid,
          diagnostics =
            "Template '${definition.templateReference}' expanded to multiple resources in a singular extraction context. Only the first resource will be emitted.",
          expressionPath = path,
        )
      )
    }

    val resourceJson =
      processedResources.first().withEvaluatedResourceId(definition, scope, path, onIssue)
    val extractedResource =
      recoverTemplateFailure(onIssue, { null }) {
        valueConverter.jsonToResource(resourceJson, path)
      } ?: return null
    return createBundleEntry(definition, scope, path, resourceType, extractedResource, onIssue)
  }

  private fun JsonObject.withEvaluatedResourceId(
    definition: TemplateExtractDefinition,
    scope: TemplateEvaluationScope,
    path: String,
    onIssue: (TemplateExtractionIssue) -> Unit,
  ): JsonObject {
    val mutable = toMutableMap()
    val evaluatedId =
      definition.resourceIdExpression
        ?.let { evaluateExpression(it, scope, "$path.resourceId", onIssue) }
        ?.let { values -> toStringValue(values, "$path.resourceId", onIssue) }
        ?.takeIf { it.isNotBlank() }

    if (evaluatedId == null) {
      mutable.remove("id")
      mutable.remove("_id")
    } else {
      mutable["id"] = JsonPrimitive(evaluatedId)
      mutable.remove("_id")
    }
    return JsonObject(mutable)
  }

  private fun createBundleEntry(
    definition: TemplateExtractDefinition,
    scope: TemplateEvaluationScope,
    path: String,
    resourceType: String,
    extractedResource: Resource,
    onIssue: (TemplateExtractionIssue) -> Unit,
  ): Bundle.Entry {
    val resourceId = extractedResource.id
    val fullUrl =
      definition.fullUrlExpression
        ?.let { evaluateExpression(it, scope, "$path.fullUrl", onIssue) }
        ?.let { values -> toStringValue(values, "$path.fullUrl", onIssue) }
        ?.takeIf { it.isNotBlank() } ?: generateAllocatedFullUrl()

    val requestUrl =
      if (resourceId.isNullOrBlank()) {
        resourceType
      } else {
        "$resourceType/$resourceId"
      }

    val requestBuilder =
      Bundle.Entry.Request.Builder(
        method =
          Enumeration(
            value =
              if (resourceId.isNullOrBlank()) {
                Bundle.HTTPVerb.Post
              } else {
                Bundle.HTTPVerb.Put
              }
          ),
        url = Uri.Builder().apply { value = requestUrl },
      )

    definition.ifNoneMatchExpression
      ?.let { evaluateExpression(it, scope, "$path.ifNoneMatch", onIssue) }
      ?.let { values -> toStringValue(values, "$path.ifNoneMatch", onIssue) }
      ?.takeIf { it.isNotBlank() }
      ?.let { requestBuilder.ifNoneMatch = FhirString.Builder().apply { value = it } }

    definition.ifMatchExpression
      ?.let { evaluateExpression(it, scope, "$path.ifMatch", onIssue) }
      ?.let { values -> toStringValue(values, "$path.ifMatch", onIssue) }
      ?.takeIf { it.isNotBlank() }
      ?.let { requestBuilder.ifMatch = FhirString.Builder().apply { value = it } }

    definition.ifNoneExistExpression
      ?.let { evaluateExpression(it, scope, "$path.ifNoneExist", onIssue) }
      ?.let { values -> toStringValue(values, "$path.ifNoneExist", onIssue) }
      ?.takeIf { it.isNotBlank() }
      ?.let { requestBuilder.ifNoneExist = FhirString.Builder().apply { value = it } }

    definition.ifModifiedSinceExpression
      ?.let { evaluateExpression(it, scope, "$path.ifModifiedSince", onIssue) }
      ?.let { values -> toInstantValue(values, "$path.ifModifiedSince", onIssue) }
      ?.let { instant -> requestBuilder.ifModifiedSince = instant.toBuilder() }

    return Bundle.Entry(
      fullUrl = Uri(value = fullUrl),
      resource = extractedResource,
      request = requestBuilder.build(),
    )
  }

  private fun evaluateExpression(
    expression: String,
    scope: TemplateEvaluationScope,
    path: String,
    onIssue: (TemplateExtractionIssue) -> Unit,
  ): List<Any> =
    recoverTemplateFailure(onIssue, { emptyList() }) {
      evaluator.evaluate(TemplateExtractExpression(expression), scope, path)
    }

  private fun toStringValue(
    values: List<Any>,
    path: String,
    onIssue: (TemplateExtractionIssue) -> Unit,
  ): String? =
    recoverTemplateFailure(onIssue, { null }) {
      valueConverter.toStringValue(values, path, onIssue)
    }

  private fun toInstantValue(
    values: List<Any>,
    path: String,
    onIssue: (TemplateExtractionIssue) -> Unit,
  ): Instant? =
    recoverTemplateFailure(onIssue, { null }) {
      valueConverter.toInstantValue(values, path, onIssue)
    }

  @OptIn(ExperimentalUuidApi::class)
  private fun allocateIdVariables(variableNames: List<String>): Map<String, Any?> =
    variableNames.associateWith { generateAllocatedFullUrl() }

  @OptIn(ExperimentalUuidApi::class)
  private fun generateAllocatedFullUrl(): String = "urn:uuid:${Uuid.random()}"
}

private fun MutableList<Bundle.Entry>.addIfPresent(entry: Bundle.Entry?) {
  if (entry != null) {
    add(entry)
  }
}

private fun JsonObject.withoutTemplateId(): JsonObject =
  JsonObject(
    toMutableMap().apply {
      remove("id")
      remove("_id")
    }
  )
