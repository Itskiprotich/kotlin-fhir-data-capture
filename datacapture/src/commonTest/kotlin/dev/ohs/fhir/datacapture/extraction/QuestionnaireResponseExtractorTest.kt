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

import dev.ohs.fhir.datacapture.extensions.EXTENSION_TEMPLATE_EXTRACT_CONTEXT_URL
import dev.ohs.fhir.datacapture.extensions.EXTENSION_TEMPLATE_EXTRACT_VALUE_URL
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.FhirR4Json
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Provenance
import dev.ohs.fhir.model.r4.Questionnaire
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.fhir.model.r4.RelatedPerson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin_fhir_data_capture.datacapture.generated.resources.Res
import kotlinx.coroutines.test.runTest

class QuestionnaireResponseExtractorTest {
  private val json = FhirR4Json()

  @Test
  fun extract_singleResourceTemplate_returnsTransactionBundleWithCleanPatient() = runTest {
    val bundle =
      extract(
        questionnaireJson =
          """
          {
            "resourceType": "Questionnaire",
            "status": "active",
            "contained": [
              {
                "resourceType": "Patient",
                "id": "patientTemplate",
                "name": [
                  {
                    "extension": [
                      {
                        "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractContext",
                        "valueString": "item.where(linkId='name')"
                      }
                    ],
                    "_family": {
                      "extension": [
                        {
                          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                          "valueString": "item.where(linkId='family').answer.value.first()"
                        }
                      ]
                    },
                    "_given": [
                      {
                        "extension": [
                          {
                            "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                            "valueString": "item.where(linkId='given').answer.value.first()"
                          }
                        ]
                      }
                    ]
                  }
                ],
                "_active": {
                  "extension": [
                    {
                      "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                      "valueString": "item.where(linkId='active').answer.value.first()"
                    }
                  ]
                }
              }
            ],
            "extension": [
              {
                "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtract",
                "extension": [
                  {
                    "url": "template",
                    "valueReference": {
                      "reference": "#patientTemplate"
                    }
                  }
                ]
              }
            ],
            "item": [
              {
                "linkId": "name",
                "type": "group",
                "item": [
                  {
                    "linkId": "given",
                    "type": "string"
                  },
                  {
                    "linkId": "family",
                    "type": "string"
                  }
                ]
              },
              {
                "linkId": "active",
                "type": "boolean"
              }
            ]
          }
          """
            .trimIndent(),
        questionnaireResponseJson =
          """
          {
            "resourceType": "QuestionnaireResponse",
            "status": "completed",
            "item": [
              {
                "linkId": "name",
                "item": [
                  {
                    "linkId": "given",
                    "answer": [
                      {
                        "valueString": "Jane"
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
                "linkId": "active",
                "answer": [
                  {
                    "valueBoolean": true
                  }
                ]
              }
            ]
          }
          """
            .trimIndent(),
      )

    assertEquals(Bundle.BundleType.Transaction, bundle.type.value)
    assertTrue(bundle.entry.any { it.resource is Provenance })

    val patient = bundle.resources().filterIsInstance<Patient>().single()
    assertNull(patient.id)
    assertEquals("Doe", patient.name.single().family?.value)
    assertEquals("Jane", patient.name.single().given.single().value)
    assertEquals(true, patient.active?.value)

    val serializedBundle = json.encodeToString(bundle)
    assertFalse(serializedBundle.contains(EXTENSION_TEMPLATE_EXTRACT_CONTEXT_URL))
    assertFalse(serializedBundle.contains(EXTENSION_TEMPLATE_EXTRACT_VALUE_URL))
  }

  @Test
  fun extract_officialComplexTemplate_extractsExpectedResources() = runTest {
    val bundle =
      extract(
        questionnaireJson = loadFixture("questionnaire_extract_complex_template.json"),
        questionnaireResponseJson =
          officialComplexTemplateResponseJson(
            questionnaireResponseId = "qr-complex-template-official",
            includeSecondContact = true,
          ),
      )

    assertEquals(Bundle.BundleType.Transaction, bundle.type.value)
    assertTrue(bundle.entry.any { it.resource is Provenance })
    assertTrue(bundle.resources().all { it.id == null })

    val patientEntry = bundle.entry.first { it.resource is Patient }
    val patient = patientEntry.resource as Patient
    val relatedPeople =
      bundle.resources().filterIsInstance<RelatedPerson>().sortedBy {
        it.name.single().text?.value.orEmpty()
      }
    val observationsByCode =
      bundle.resources().filterIsInstance<Observation>().associateBy {
        it.code.coding.single().code?.value
      }

    assertTrue(patientEntry.fullUrl?.value?.startsWith("urn:uuid:") == true)
    assertEquals("8003601234567890", patient.identifier.single().value?.value)
    assertEquals(listOf("Alex", "Jordan"), patient.name.single().given.map { it.value })
    assertEquals("Example", patient.name.single().family?.value)
    assertEquals("+254700000001", patient.telecom.single().value?.value)

    assertEquals(listOf("Alice Example", "Bob Example"), relatedPeople.map { it.name.single().text?.value })
    assertTrue(relatedPeople.all { it.patient.reference?.value == patientEntry.fullUrl?.value })

    val height = observationsByCode.getValue("8302-2")
    val weight = observationsByCode.getValue("29463-7")
    val complication = observationsByCode.getValue("sigmoidoscopy-complication")

    assertEquals(patientEntry.fullUrl?.value, height.subject?.reference?.value)
    assertEquals("cm", height.value?.asQuantity()?.value?.unit?.value)
    assertTrue(json.encodeToString(height).contains("172"))
    assertEquals("Practitioner/prac-1", height.performer.single().reference?.value)
    assertEquals("kg", weight.value?.asQuantity()?.value?.unit?.value)
    assertTrue(json.encodeToString(weight).contains("68.4"))
    assertEquals(false, complication.value?.asBoolean()?.value?.value)

    val serializedBundle = json.encodeToString(bundle)
    assertFalse(serializedBundle.contains(EXTENSION_TEMPLATE_EXTRACT_CONTEXT_URL))
    assertFalse(serializedBundle.contains(EXTENSION_TEMPLATE_EXTRACT_VALUE_URL))
  }

  @Test
  fun extract_officialComplexTemplateBundle_preservesTemplateBundleRequestMetadata() = runTest {
    val bundle =
      extract(
        questionnaireJson = loadFixture("questionnaire_extract_complex_template_bundle.json"),
        questionnaireResponseJson =
          officialComplexTemplateResponseJson(
            questionnaireResponseId = "qr-complex-template-bundle",
            includeSecondContact = false,
          ),
      )

    assertEquals(Bundle.BundleType.Transaction, bundle.type.value)
    assertTrue(bundle.entry.any { it.resource is Provenance })
    assertTrue(bundle.resources().all { it.id == null })

    val patientEntry = bundle.entry.first { it.resource is Patient }
    val patient = patientEntry.resource as Patient
    val relatedPersonEntry = bundle.entry.first { it.resource is RelatedPerson }
    val relatedPerson = relatedPersonEntry.resource as RelatedPerson
    val observationEntries = bundle.entry.filter { it.resource is Observation }
    val observationsByCode =
      observationEntries.map { it.resource as Observation }.associateBy {
        it.code.coding.single().code?.value
      }

    assertEquals("urn:uuid:6f6177d2-13ee-4d27-b0e8-3eaf663dd031", patientEntry.fullUrl?.value)
    assertEquals(Bundle.HTTPVerb.Post, patientEntry.request?.method?.value)
    assertEquals("Patient", patientEntry.request?.url?.value)
    assertEquals(
      "Patient?_name=urn:uuid:6f6177d2-13ee-4d27-b0e8-3eaf663dd031",
      patientEntry.request?.ifMatch?.value,
    )
    assertEquals("8003601234567890", patient.identifier.single().value?.value)

    assertEquals("Alice Example", relatedPerson.name.single().text?.value)
    assertEquals(patientEntry.fullUrl?.value, relatedPerson.patient.reference?.value)
    assertEquals(Bundle.HTTPVerb.Post, relatedPersonEntry.request?.method?.value)
    assertEquals("RelatedPerson", relatedPersonEntry.request?.url?.value)
    assertTrue(observationEntries.all { it.request?.method?.value == Bundle.HTTPVerb.Post })
    assertTrue(observationEntries.all { it.request?.url?.value == "Observation" })

    assertTrue(json.encodeToString(observationsByCode.getValue("8302-2")).contains("172"))
    assertTrue(json.encodeToString(observationsByCode.getValue("29463-7")).contains("68.4"))
    assertEquals(false, observationsByCode.getValue("sigmoidoscopy-complication").value?.asBoolean()?.value?.value)
  }

  @Test
  fun extract_templateBundle_preservesSharedAllocatedReference() = runTest {
    val bundle =
      extract(
        questionnaireJson =
          """
          {
            "resourceType": "Questionnaire",
            "status": "active",
            "contained": [
              {
                "resourceType": "Bundle",
                "id": "transactionTemplate",
                "type": "transaction",
                "entry": [
                  {
                    "_fullUrl": {
                      "extension": [
                        {
                          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                          "valueString": "%patientRef"
                        }
                      ]
                    },
                    "resource": {
                      "resourceType": "Patient",
                      "id": "patientTemplate",
                      "_active": {
                        "extension": [
                          {
                            "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                            "valueString": "item.where(linkId='patient-active').answer.value.first()"
                          }
                        ]
                      }
                    },
                    "request": {
                      "method": "POST",
                      "url": "Patient"
                    }
                  },
                  {
                    "resource": {
                      "resourceType": "Observation",
                      "id": "observationTemplate",
                      "status": "final",
                      "code": {
                        "text": "Smoking status"
                      },
                      "subject": {
                        "_reference": {
                          "extension": [
                            {
                              "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                              "valueString": "%patientRef"
                            }
                          ]
                        }
                      },
                      "_valueBoolean": {
                        "extension": [
                          {
                            "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                            "valueString": "item.where(linkId='smoker').answer.value.first()"
                          }
                        ]
                      }
                    },
                    "request": {
                      "method": "POST",
                      "url": "Observation"
                    }
                  }
                ]
              }
            ],
            "extension": [
              {
                "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-extractAllocateId",
                "valueString": "patientRef"
              },
              {
                "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractBundle",
                "valueReference": {
                  "reference": "#transactionTemplate"
                }
              }
            ],
            "item": [
              {
                "linkId": "patient-active",
                "type": "boolean"
              },
              {
                "linkId": "smoker",
                "type": "boolean"
              }
            ]
          }
          """
            .trimIndent(),
        questionnaireResponseJson =
          """
          {
            "resourceType": "QuestionnaireResponse",
            "status": "completed",
            "item": [
              {
                "linkId": "patient-active",
                "answer": [
                  {
                    "valueBoolean": true
                  }
                ]
              },
              {
                "linkId": "smoker",
                "answer": [
                  {
                    "valueBoolean": false
                  }
                ]
              }
            ]
          }
          """
            .trimIndent(),
      )

    val patientEntry = bundle.entry.first { it.resource is Patient }
    val observation = bundle.resources().filterIsInstance<Observation>().single()

    assertEquals(patientEntry.fullUrl?.value, observation.subject?.reference?.value)
    assertEquals(false, observation.value?.asBoolean()?.value?.value)
  }

  @Test
  fun extract_repeatingGroup_createsOneResourcePerIterationAndSharesRootUuid() = runTest {
    val bundle =
      extract(
        questionnaireJson =
          """
          {
            "resourceType": "Questionnaire",
            "status": "active",
            "contained": [
              {
                "resourceType": "Patient",
                "id": "patientTemplate",
                "name": [
                  {
                    "_text": {
                      "extension": [
                        {
                          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                          "valueString": "item.where(linkId='patient-name').answer.value.first()"
                        }
                      ]
                    }
                  }
                ]
              },
              {
                "resourceType": "RelatedPerson",
                "id": "contactTemplate",
                "patient": {
                  "_reference": {
                    "extension": [
                      {
                        "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                        "valueString": "%patientRef"
                      }
                    ]
                  }
                },
                "name": [
                  {
                    "_text": {
                      "extension": [
                        {
                          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                          "valueString": "item.where(linkId='contact-name').answer.value.first()"
                        }
                      ]
                    }
                  }
                ]
              }
            ],
            "extension": [
              {
                "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-extractAllocateId",
                "valueString": "patientRef"
              },
              {
                "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtract",
                "extension": [
                  {
                    "url": "template",
                    "valueReference": {
                      "reference": "#patientTemplate"
                    }
                  },
                  {
                    "url": "fullUrl",
                    "valueString": "%patientRef"
                  }
                ]
              }
            ],
            "item": [
              {
                "linkId": "patient-name",
                "type": "string"
              },
              {
                "linkId": "contacts",
                "type": "group",
                "repeats": true,
                "extension": [
                  {
                    "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtract",
                    "extension": [
                      {
                        "url": "template",
                        "valueReference": {
                          "reference": "#contactTemplate"
                        }
                      }
                    ]
                  }
                ],
                "item": [
                  {
                    "linkId": "contact-name",
                    "type": "string"
                  }
                ]
              }
            ]
          }
          """
            .trimIndent(),
        questionnaireResponseJson =
          """
          {
            "resourceType": "QuestionnaireResponse",
            "status": "completed",
            "item": [
              {
                "linkId": "patient-name",
                "answer": [
                  {
                    "valueString": "Primary Patient"
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
                        "valueString": "Alice"
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
                        "valueString": "Bob"
                      }
                    ]
                  }
                ]
              }
            ]
          }
          """
            .trimIndent(),
      )

    val patientEntry = bundle.entry.first { it.resource is Patient }
    val relatedPeople = bundle.resources().filterIsInstance<RelatedPerson>()

    assertEquals(2, relatedPeople.size)
    assertTrue(relatedPeople.all { it.patient.reference?.value == patientEntry.fullUrl?.value })
    assertEquals(listOf("Alice", "Bob"), relatedPeople.map { it.name.single().text?.value })
  }

  @Test
  fun extract_collectionCondition_removesOnlyTheMissingCollectionElement() = runTest {
    val bundle =
      extract(
        questionnaireJson =
          """
          {
            "resourceType": "Questionnaire",
            "status": "active",
            "contained": [
              {
                "resourceType": "Patient",
                "id": "patientTemplate",
                "telecom": [
                  {
                    "extension": [
                      {
                        "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractContext",
                        "valueString": "item.where(linkId='phone').answer.value.first()"
                      }
                    ],
                    "system": "phone",
                    "_value": {
                      "extension": [
                        {
                          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                          "valueString": "%context"
                        }
                      ]
                    }
                  },
                  {
                    "extension": [
                      {
                        "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractContext",
                        "valueString": "item.where(linkId='email').answer.value.first()"
                      }
                    ],
                    "system": "email",
                    "_value": {
                      "extension": [
                        {
                          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                          "valueString": "%context"
                        }
                      ]
                    }
                  }
                ]
              }
            ],
            "extension": [
              {
                "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtract",
                "extension": [
                  {
                    "url": "template",
                    "valueReference": {
                      "reference": "#patientTemplate"
                    }
                  }
                ]
              }
            ],
            "item": [
              {
                "linkId": "phone",
                "type": "string"
              },
              {
                "linkId": "email",
                "type": "string"
              }
            ]
          }
          """
            .trimIndent(),
        questionnaireResponseJson =
          """
          {
            "resourceType": "QuestionnaireResponse",
            "status": "completed",
            "item": [
              {
                "linkId": "phone",
                "answer": [
                  {
                    "valueString": "+254700000000"
                  }
                ]
              }
            ]
          }
          """
            .trimIndent(),
      )

    val patient = bundle.resources().filterIsInstance<Patient>().single()
    assertEquals(1, patient.telecom.size)
    assertEquals("phone", patient.telecom.single().system?.value?.toString())
    assertEquals("+254700000000", patient.telecom.single().value?.value)
  }

  @Test
  fun extract_expressionFailure_skipsBadPropertyAndContinuesExtraction() = runTest {
    val bundle =
      extract(
        questionnaireJson =
          """
          {
            "resourceType": "Questionnaire",
            "status": "active",
            "contained": [
              {
                "resourceType": "Patient",
                "id": "patientTemplate",
                "_active": {
                  "extension": [
                    {
                      "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                      "valueString": "item.where(linkId='active').answer.value.first("
                    }
                  ]
                },
                "name": [
                  {
                    "_text": {
                      "extension": [
                        {
                          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                          "valueString": "item.where(linkId='name').answer.value.first()"
                        }
                      ]
                    }
                  }
                ]
              }
            ],
            "extension": [
              {
                "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtract",
                "extension": [
                  {
                    "url": "template",
                    "valueReference": {
                      "reference": "#patientTemplate"
                    }
                  }
                ]
              }
            ],
            "item": [
              {
                "linkId": "active",
                "type": "boolean"
              },
              {
                "linkId": "name",
                "type": "string"
              }
            ]
          }
          """
            .trimIndent(),
        questionnaireResponseJson =
          """
          {
            "resourceType": "QuestionnaireResponse",
            "status": "completed",
            "item": [
              {
                "linkId": "active",
                "answer": [
                  {
                    "valueBoolean": true
                  }
                ]
              },
              {
                "linkId": "name",
                "answer": [
                  {
                    "valueString": "Recovered Patient"
                  }
                ]
              }
            ]
          }
          """
            .trimIndent(),
      )

    val patient = bundle.resources().filterIsInstance<Patient>().single()
    assertNull(patient.active)
    assertEquals("Recovered Patient", patient.name.single().text?.value)
  }

  private suspend fun extract(
    questionnaireJson: String,
    questionnaireResponseJson: String,
  ): Bundle {
    val questionnaire = json.decodeFromString(questionnaireJson) as Questionnaire
    val questionnaireResponse = json.decodeFromString(questionnaireResponseJson) as QuestionnaireResponse
    return QuestionnaireResponseExtractor.extract(questionnaire, questionnaireResponse)
  }

  private suspend fun loadFixture(fileName: String): String =
    Res.readBytes("files/$fileName").decodeToString()

  private fun officialComplexTemplateResponseJson(
    questionnaireResponseId: String,
    includeSecondContact: Boolean,
  ): String {
    val secondContact =
      if (!includeSecondContact) {
        ""
      } else {
        """
        ,
        {
          "linkId": "contacts",
          "item": [
            {
              "linkId": "contact-name",
              "answer": [
                {
                  "valueString": "Bob Example"
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
            },
            {
              "linkId": "phone",
              "answer": [
                {
                  "valueString": "+254700000003"
                }
              ]
            }
          ]
        }
        """
          .trimIndent()
      }

    return """
      {
        "resourceType": "QuestionnaireResponse",
        "id": "$questionnaireResponseId",
        "status": "completed",
        "authored": "2026-04-30T12:34:56Z",
        "author": {
          "reference": "Practitioner/prac-1"
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
                        "valueString": "Alex"
                      },
                      {
                        "valueString": "Jordan"
                      }
                    ]
                  },
                  {
                    "linkId": "family",
                    "answer": [
                      {
                        "valueString": "Example"
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
                    "valueDate": "1990-04-12"
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
              },
              {
                "linkId": "mobile-phone",
                "answer": [
                  {
                    "valueString": "+254700000001"
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
                    "valueString": "Alice Example"
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
                      "display": "Emergency contact"
                    }
                  }
                ]
              },
              {
                "linkId": "phone",
                "answer": [
                  {
                    "valueString": "+254700000002"
                  }
                ]
              }
            ]
          }$secondContact,
          {
            "linkId": "obs",
            "item": [
              {
                "linkId": "height",
                "answer": [
                  {
                    "valueDecimal": 1.72
                  }
                ]
              },
              {
                "linkId": "weight",
                "answer": [
                  {
                    "valueDecimal": 68.4
                  }
                ]
              },
              {
                "linkId": "complication",
                "answer": [
                  {
                    "valueBoolean": false
                  }
                ]
              }
            ]
          }
        ]
      }
      """
      .trimIndent()
  }

  private fun Bundle.resources() = entry.mapNotNull { it.resource }.filterNot { it is Provenance }
}
