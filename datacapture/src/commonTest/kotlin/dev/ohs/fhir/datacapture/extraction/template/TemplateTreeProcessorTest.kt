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

import dev.ohs.fhir.model.r4.Questionnaire
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TemplateTreeProcessorTest {
  private val json = Json {
    explicitNulls = false
    encodeDefaults = false
  }

  private val treeProcessor = TemplateTreeProcessor()

  @Test
  fun processResource_allowsTemplateIdToBeOverriddenDuringJsonProcessing() {
    val template =
      json
        .parseToJsonElement(
          """
          {
            "resourceType": "Observation",
            "id": "observation-template",
            "_id": {
              "extension": [
                {
                  "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                  "valueString": "%context.answer.value.first()"
                }
              ]
            },
            "status": "final",
            "code": {
              "text": "phone"
            }
          }
          """
        )
        .jsonObject

    val questionnaire =
      questionnaire(
        """
        {
          "resourceType": "Questionnaire",
          "url": "http://example.org/Questionnaire/template-id-override",
          "status": "active",
          "item": [
            {
              "linkId": "phone",
              "text": "Phone",
              "type": "string"
            }
          ]
        }
        """
      )

    val questionnaireResponse =
      questionnaireResponse(
        """
        {
          "resourceType": "QuestionnaireResponse",
          "questionnaire": "http://example.org/Questionnaire/template-id-override",
          "status": "completed",
          "item": [
            {
              "linkId": "phone",
              "answer": [
                {
                  "valueString": "phone-1"
                }
              ]
            }
          ]
        }
        """
      )

    val processedResources =
      treeProcessor.processResource(
        template = template,
        scope =
          TemplateEvaluationScope(
            questionnaire = questionnaire,
            questionnaireResponse = questionnaireResponse,
            questionnaireItem = questionnaire.item.single(),
            context = questionnaireResponse.item.single(),
            variables = emptyMap(),
          ),
      )

    assertEquals(1, processedResources.size)
    val processedResource = processedResources.single()
    assertEquals("phone-1", processedResource.getValue("id").jsonPrimitive.content)
    assertFalse(processedResource.containsKey("_id"))
  }

  private fun questionnaire(jsonString: String): Questionnaire =
    json.decodeFromString<Questionnaire>(jsonString.trimIndent())

  private fun questionnaireResponse(jsonString: String): QuestionnaireResponse =
    json.decodeFromString<QuestionnaireResponse>(jsonString.trimIndent())
}
