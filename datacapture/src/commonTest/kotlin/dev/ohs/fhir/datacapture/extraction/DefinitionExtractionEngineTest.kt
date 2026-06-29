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
package dev.ohs.fhir.datacapture.extraction

import dev.ohs.fhir.datacapture.extraction.definition.DefinitionElement
import dev.ohs.fhir.datacapture.extraction.definition.DefinitionResolver
import dev.ohs.fhir.datacapture.extraction.definition.DefinitionStructure
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Questionnaire
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DefinitionExtractionEngineTest {
  private val json = Json {
    explicitNulls = false
    encodeDefaults = false
  }

  // Tests only need a small StructureDefinition subset, but extraction itself rehydrates through
  // the generated FHIR Resource polymorphic serializer, which supports the full R4 model.
  private val testDefinitionResolver =
    DefinitionResolver.of(
      structure(
        canonical = "http://hl7.org/fhir/StructureDefinition/Patient",
        resourceType = "Patient",
        element("Patient"),
        element("Patient.id", type = "id"),
        element("Patient.birthDate", type = "date"),
        element("Patient.gender", type = "code"),
        element("Patient.name", max = "*", type = "HumanName"),
        element("Patient.name.text", type = "string"),
        element("Patient.name.family", type = "string"),
        element("Patient.name.given", max = "*", type = "string"),
        element("Patient.identifier", max = "*", type = "Identifier"),
        element("Patient.identifier.type", type = "CodeableConcept"),
        element("Patient.identifier.system", type = "uri"),
        element("Patient.identifier.value", type = "string"),
        element("Patient.telecom", max = "*", type = "ContactPoint"),
        element("Patient.telecom.system", type = "code"),
        element("Patient.telecom.use", type = "code"),
        element("Patient.telecom.value", type = "string"),
      ),
      structure(
        canonical = "http://hl7.org/fhir/StructureDefinition/RelatedPerson",
        resourceType = "RelatedPerson",
        element("RelatedPerson"),
        element("RelatedPerson.id", type = "id"),
        element("RelatedPerson.patient", type = "Reference"),
        element("RelatedPerson.patient.reference", type = "string"),
        element("RelatedPerson.name", max = "*", type = "HumanName"),
        element("RelatedPerson.name.text", type = "string"),
        element("RelatedPerson.name.family", type = "string"),
        element("RelatedPerson.name.given", max = "*", type = "string"),
        element("RelatedPerson.relationship", max = "*", type = "CodeableConcept"),
        element("RelatedPerson.relationship.coding", max = "*", type = "Coding"),
        element("RelatedPerson.telecom", max = "*", type = "ContactPoint"),
        element("RelatedPerson.telecom.system", type = "code"),
        element("RelatedPerson.telecom.use", type = "code"),
        element("RelatedPerson.telecom.value", type = "string"),
      ),
      structure(
        canonical = "http://hl7.org/fhir/StructureDefinition/Observation",
        resourceType = "Observation",
        element("Observation"),
        element("Observation.status", type = "code"),
        element("Observation.category", max = "*", type = "CodeableConcept"),
        element("Observation.category.coding", max = "*", type = "Coding"),
        element("Observation.code", type = "CodeableConcept"),
        element("Observation.code.coding", max = "*", type = "Coding"),
        element("Observation.subject", type = "Reference"),
        element("Observation.subject.reference", type = "string"),
        element(
          "Observation.effective[x]",
          types = listOf("dateTime", "Period", "Timing", "instant"),
        ),
        element("Observation.issued", type = "instant"),
        element("Observation.performer", max = "*", type = "Reference"),
        element("Observation.derivedFrom", max = "*", type = "Reference"),
        element("Observation.derivedFrom.reference", type = "string"),
        element(
          "Observation.value[x]",
          types = listOf("Quantity", "boolean", "string", "CodeableConcept"),
        ),
        element("Observation.value[x]:valueQuantity", type = "Quantity"),
        element("Observation.value[x]:valueQuantity.value", type = "decimal"),
        element("Observation.value[x]:valueQuantity.unit", type = "string"),
      ),
      structure(
        canonical = "http://hl7.org/fhir/StructureDefinition/Encounter",
        resourceType = "Encounter",
        element("Encounter"),
        element("Encounter.status", type = "code"),
        element("Encounter.class", type = "Coding"),
        element("Encounter.subject", type = "Reference"),
        element("Encounter.subject.reference", type = "string"),
      ),
    )

  @Test
  fun extractsDemographicsUsingTheExactSpecQuestionnaire() = runTest {
    val questionnaire = questionnaire(DefinitionExtractionFixtures.demographicsQuestionnaireJson)
    val questionnaireResponse =
      questionnaireResponse(
        """
        {
          "resourceType": "QuestionnaireResponse",
          "questionnaire": "http://hl7.org/fhir/uv/sdc/Questionnaire/demographics",
          "status": "completed",
          "item": [
            {
              "linkId": "patient.id",
              "answer": [
                {
                  "valueString": "patient-123"
                }
              ]
            },
            {
              "linkId": "patient.birthDate",
              "answer": [
                {
                  "valueDate": "1980-01-02"
                }
              ]
            },
            {
              "linkId": "patient.name",
              "item": [
                {
                  "linkId": "patient.name.family",
                  "answer": [
                    {
                      "valueString": "Doe"
                    }
                  ]
                },
                {
                  "linkId": "patient.name.given",
                  "answer": [
                    {
                      "valueString": "Jane"
                    },
                    {
                      "valueString": "Alex"
                    }
                  ]
                }
              ]
            },
            {
              "linkId": "patient.name",
              "item": [
                {
                  "linkId": "patient.name.family",
                  "answer": [
                    {
                      "valueString": "Smith"
                    }
                  ]
                },
                {
                  "linkId": "patient.name.given",
                  "answer": [
                    {
                      "valueString": "Sam"
                    }
                  ]
                }
              ]
            }
          ]
        }
        """
      )

    assertTrue(DefinitionExtractionEngine.canExtract(questionnaire))

    val result =
      DefinitionExtractionEngine.extract(
        questionnaire = questionnaire,
        questionnaireResponse = questionnaireResponse,
        definitionResolver = testDefinitionResolver,
      )

    assertEquals(Bundle.BundleType.Transaction, result.type.value)
    assertEquals(1, result.entry.size)

    val entryObjects = bundleEntryObjects(result)
    val patientEntry = entryObjects.single { entry -> resourceType(entry) == "Patient" }

    assertTrue(patientEntry.getValue("fullUrl").jsonPrimitive.content.startsWith("urn:uuid:"))
    assertEquals(
      "PUT",
      requestOf(patientEntry).getValue("method").jsonPrimitive.content.uppercase(),
    )
    assertEquals(
      "Patient/patient-123",
      requestOf(patientEntry).getValue("url").jsonPrimitive.content,
    )

    val patientResource = resourceOf(patientEntry)
    assertEquals("1980-01-02", patientResource.getValue("birthDate").jsonPrimitive.content)
    val patientNames = patientResource.getValue("name").jsonArray.map { name -> name.jsonObject }
    assertEquals(2, patientNames.size)
    assertEquals("Doe", patientNames[0].getValue("family").jsonPrimitive.content)
    assertEquals(
      listOf("Jane", "Alex"),
      patientNames[0].getValue("given").jsonArray.map { value -> value.jsonPrimitive.content },
    )
    assertEquals("Smith", patientNames[1].getValue("family").jsonPrimitive.content)
    assertEquals(
      listOf("Sam"),
      patientNames[1].getValue("given").jsonArray.map { value -> value.jsonPrimitive.content },
    )
  }

  @Test
  fun extractsComplexDefinitionExampleUsingTheExactSpecQuestionnaire() = runTest {
    val questionnaire = questionnaire(DefinitionExtractionFixtures.complexQuestionnaireJson)
    val authoredAt = "2026-06-26T10:15:30+03:00"
    val questionnaireResponse =
      questionnaireResponse(
        """
        {
          "resourceType": "QuestionnaireResponse",
          "id": "qr-complex-1",
          "questionnaire": "http://hl7.org/fhir/uv/sdc/Questionnaire/extract-complex-defn3",
          "status": "completed",
          "authored": "$authoredAt",
          "author": {
            "reference": "Practitioner/practitioner-1"
          },
          "item": [
            {
              "linkId": "patient",
              "item": [
                {
                  "linkId": "name",
                  "item": [
                    {
                      "linkId": "given",
                      "answer": [
                        {
                          "valueString": "Jane"
                        },
                        {
                          "valueString": "Alex"
                        }
                      ]
                    },
                    {
                      "linkId": "family",
                      "answer": [
                        {
                          "valueString": "Doe"
                        }
                      ]
                    }
                  ]
                },
                {
                  "linkId": "gender",
                  "answer": [
                    {
                      "valueCoding": {
                        "system": "http://hl7.org/fhir/administrative-gender",
                        "code": "female",
                        "display": "Female"
                      }
                    }
                  ]
                },
                {
                  "linkId": "dob",
                  "answer": [
                    {
                      "valueDate": "1980-01-02"
                    }
                  ]
                },
                {
                  "linkId": "ihi",
                  "answer": [
                    {
                      "valueString": "8003601234567890"
                    }
                  ]
                }
              ]
            },
            {
              "linkId": "contacts",
              "item": [
                {
                  "linkId": "contact-name",
                  "answer": [
                    {
                      "valueString": "John Doe"
                    }
                  ]
                },
                {
                  "linkId": "relationship",
                  "answer": [
                    {
                      "valueCoding": {
                        "system": "http://terminology.hl7.org/CodeSystem/v2-0131",
                        "code": "C",
                        "display": "Emergency Contact"
                      }
                    }
                  ]
                }
              ]
            },
            {
              "linkId": "contacts",
              "item": [
                {
                  "linkId": "contact-name",
                  "answer": [
                    {
                      "valueString": "Mary Doe"
                    }
                  ]
                },
                {
                  "linkId": "relationship",
                  "answer": [
                    {
                      "valueCoding": {
                        "system": "http://terminology.hl7.org/CodeSystem/v2-0131",
                        "code": "N",
                        "display": "Next-of-kin"
                      }
                    }
                  ]
                }
              ]
            },
            {
              "linkId": "obs",
              "item": [
                {
                  "linkId": "height",
                  "answer": [
                    {
                      "valueDecimal": 1.75
                    }
                  ]
                },
                {
                  "linkId": "weight",
                  "answer": [
                    {
                      "valueDecimal": 68.5
                    }
                  ]
                },
                {
                  "linkId": "complication",
                  "answer": [
                    {
                      "valueBoolean": true
                    }
                  ]
                }
              ]
            }
          ]
        }
        """
      )

    assertTrue(DefinitionExtractionEngine.canExtract(questionnaire))

    val result =
      DefinitionExtractionEngine.extract(
        questionnaire = questionnaire,
        questionnaireResponse = questionnaireResponse,
        definitionResolver = testDefinitionResolver,
      )

    assertEquals(Bundle.BundleType.Transaction, result.type.value)
    assertEquals(5, result.entry.size)

    val entryObjects = bundleEntryObjects(result)
    val patientEntry = entryObjects.single { entry -> resourceType(entry) == "Patient" }
    val relatedEntries = entryObjects.filter { entry -> resourceType(entry) == "RelatedPerson" }
    val observationEntries = entryObjects.filter { entry -> resourceType(entry) == "Observation" }

    val patientFullUrl = patientEntry.getValue("fullUrl").jsonPrimitive.content
    assertTrue(patientFullUrl.startsWith("urn:uuid:"))
    assertEquals(
      "POST",
      requestOf(patientEntry).getValue("method").jsonPrimitive.content.uppercase(),
    )
    assertEquals("Patient", requestOf(patientEntry).getValue("url").jsonPrimitive.content)

    val patientResource = resourceOf(patientEntry)
    val patientName = patientResource.getValue("name").jsonArray.single().jsonObject
    assertEquals("Jane Alex Doe", patientName.getValue("text").jsonPrimitive.content)
    assertEquals("Doe", patientName.getValue("family").jsonPrimitive.content)
    assertEquals(
      listOf("Jane", "Alex"),
      patientName.getValue("given").jsonArray.map { value -> value.jsonPrimitive.content },
    )
    assertEquals("female", patientResource.getValue("gender").jsonPrimitive.content)
    assertEquals("1980-01-02", patientResource.getValue("birthDate").jsonPrimitive.content)

    val identifier = patientResource.getValue("identifier").jsonArray.single().jsonObject
    assertEquals("http://example.org/nhio", identifier.getValue("system").jsonPrimitive.content)
    assertEquals("8003601234567890", identifier.getValue("value").jsonPrimitive.content)
    assertEquals(
      "National Identifier (IHI)",
      identifier.getValue("type").jsonObject.getValue("text").jsonPrimitive.content,
    )

    assertEquals(2, relatedEntries.size)
    val relationshipsByName =
      relatedEntries.associate { entry ->
        val resource = resourceOf(entry)
        val nameText =
          resource
            .getValue("name")
            .jsonArray
            .single()
            .jsonObject
            .getValue("text")
            .jsonPrimitive
            .content
        val relationshipCode =
          resource
            .getValue("relationship")
            .jsonArray
            .single()
            .jsonObject
            .getValue("coding")
            .jsonArray
            .single()
            .jsonObject
            .getValue("code")
            .jsonPrimitive
            .content

        assertEquals("POST", requestOf(entry).getValue("method").jsonPrimitive.content.uppercase())
        assertEquals("RelatedPerson", requestOf(entry).getValue("url").jsonPrimitive.content)
        assertEquals(
          patientFullUrl,
          resource.getValue("patient").jsonObject.getValue("reference").jsonPrimitive.content,
        )

        nameText to relationshipCode
      }
    assertEquals(mapOf("John Doe" to "C", "Mary Doe" to "N"), relationshipsByName)

    assertEquals(2, observationEntries.size)
    val observationsByCode =
      observationEntries.associateBy { entry ->
        resourceOf(entry)
          .getValue("code")
          .jsonObject
          .getValue("coding")
          .jsonArray
          .single()
          .jsonObject
          .getValue("code")
          .jsonPrimitive
          .content
      }

    val heightObservation = resourceOf(observationsByCode.getValue("8302-2"))
    assertEquals("final", heightObservation.getValue("status").jsonPrimitive.content)
    assertEquals(
      "vital-signs",
      heightObservation
        .getValue("category")
        .jsonArray
        .single()
        .jsonObject
        .getValue("coding")
        .jsonArray
        .single()
        .jsonObject
        .getValue("code")
        .jsonPrimitive
        .content,
    )
    assertEquals(
      patientFullUrl,
      heightObservation.getValue("subject").jsonObject.getValue("reference").jsonPrimitive.content,
    )
    assertEquals(authoredAt, heightObservation.getValue("effectiveDateTime").jsonPrimitive.content)
    assertEquals(authoredAt, heightObservation.getValue("issued").jsonPrimitive.content)
    assertEquals(
      "Practitioner/practitioner-1",
      heightObservation
        .getValue("performer")
        .jsonArray
        .single()
        .jsonObject
        .getValue("reference")
        .jsonPrimitive
        .content,
    )
    assertEquals(
      "QuestionnaireResponse/qr-complex-1",
      heightObservation
        .getValue("derivedFrom")
        .jsonArray
        .single()
        .jsonObject
        .getValue("reference")
        .jsonPrimitive
        .content,
    )
    assertEquals(
      "1.75",
      heightObservation.getValue("valueQuantity").jsonObject.getValue("value").jsonPrimitive.content,
    )
    assertEquals(
      "m",
      heightObservation.getValue("valueQuantity").jsonObject.getValue("unit").jsonPrimitive.content,
    )

    val weightObservation = resourceOf(observationsByCode.getValue("29463-7"))
    assertEquals(
      "68.5",
      weightObservation.getValue("valueQuantity").jsonObject.getValue("value").jsonPrimitive.content,
    )
    assertEquals(
      "kg",
      weightObservation.getValue("valueQuantity").jsonObject.getValue("unit").jsonPrimitive.content,
    )
    assertEquals(
      patientFullUrl,
      weightObservation.getValue("subject").jsonObject.getValue("reference").jsonPrimitive.content,
    )
    assertTrue(observationsByCode["sigmoidoscopy-complication"] == null)
  }

  @Test
  fun extractsEncounterToShowSupportIsNotLimitedToDemoResourceTypes() = runTest {
    val questionnaire =
      questionnaire(
        """
        {
          "resourceType": "Questionnaire",
          "url": "http://example.org/Questionnaire/encounter-extract",
          "status": "draft",
          "extension": [
            {
              "extension": [
                {
                  "url": "definition",
                  "valueCanonical": "http://hl7.org/fhir/StructureDefinition/Encounter"
                }
              ],
              "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-definitionExtract"
            },
            {
              "extension": [
                {
                  "url": "definition",
                  "valueUri": "http://hl7.org/fhir/StructureDefinition/Encounter#Encounter.status"
                },
                {
                  "url": "fixed-value",
                  "valueCode": "finished"
                }
              ],
              "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-definitionExtractValue"
            },
            {
              "extension": [
                {
                  "url": "definition",
                  "valueUri": "http://hl7.org/fhir/StructureDefinition/Encounter#Encounter.class"
                },
                {
                  "url": "fixed-value",
                  "valueCoding": {
                    "system": "http://terminology.hl7.org/CodeSystem/v3-ActCode",
                    "code": "AMB",
                    "display": "ambulatory"
                  }
                }
              ],
              "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-definitionExtractValue"
            }
          ],
          "item": [
            {
              "linkId": "subject",
              "definition": "http://hl7.org/fhir/StructureDefinition/Encounter#Encounter.subject.reference",
              "text": "Encounter subject",
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
          "questionnaire": "http://example.org/Questionnaire/encounter-extract",
          "status": "completed",
          "item": [
            {
              "linkId": "subject",
              "answer": [
                {
                  "valueString": "Patient/patient-enc-1"
                }
              ]
            }
          ]
        }
        """
      )

    val result =
      DefinitionExtractionEngine.extract(
        questionnaire = questionnaire,
        questionnaireResponse = questionnaireResponse,
        definitionResolver = testDefinitionResolver,
      )

    assertEquals(1, result.entry.size)
    val encounterEntry = bundleEntryObjects(result).single()
    assertEquals("Encounter", resourceType(encounterEntry))
    assertEquals("finished", resourceOf(encounterEntry).getValue("status").jsonPrimitive.content)
    assertEquals(
      "AMB",
      resourceOf(encounterEntry).getValue("class").jsonObject.getValue("code").jsonPrimitive.content,
    )
    assertEquals(
      "Patient/patient-enc-1",
      resourceOf(encounterEntry)
        .getValue("subject")
        .jsonObject
        .getValue("reference")
        .jsonPrimitive
        .content,
    )
  }

  private fun questionnaire(jsonString: String): Questionnaire =
    json.decodeFromString<Questionnaire>(jsonString.trimIndent())

  private fun questionnaireResponse(jsonString: String): QuestionnaireResponse =
    json.decodeFromString<QuestionnaireResponse>(jsonString.trimIndent())

  private fun bundleEntryObjects(result: Bundle): List<JsonObject> =
    json
      .parseToJsonElement(json.encodeToString(result))
      .jsonObject
      .getValue("entry")
      .jsonArray
      .map { entry -> entry.jsonObject }

  private fun resourceType(entry: JsonObject): String =
    resourceOf(entry).getValue("resourceType").jsonPrimitive.content

  private fun resourceOf(entry: JsonObject): JsonObject = entry.getValue("resource").jsonObject

  private fun requestOf(entry: JsonObject): JsonObject = entry.getValue("request").jsonObject

  private fun structure(
    canonical: String,
    resourceType: String,
    vararg elements: DefinitionElement,
  ): DefinitionStructure =
    DefinitionStructure(
      canonical = canonical,
      resourceType = resourceType,
      elementsById = elements.associateBy(DefinitionElement::id),
    )

  private fun element(
    id: String,
    max: String = "1",
    type: String? = null,
    types: List<String> = type?.let(::listOf).orEmpty(),
  ): DefinitionElement = DefinitionElement(id = id, max = max, typeCodes = types)
}
