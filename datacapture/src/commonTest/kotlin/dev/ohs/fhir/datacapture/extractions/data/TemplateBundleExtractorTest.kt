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

package dev.ohs.fhir.datacapture.extractions.data

import dev.ohs.fhir.datacapture.extensions.FhirR4String
import dev.ohs.fhir.datacapture.extraction.TemplateBundleExtractor
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Extension
import dev.ohs.fhir.model.r4.FhirR4Json
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Questionnaire
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.fhir.model.r4.terminologies.AdministrativeGender
import dev.ohs.fhir.model.r4.terminologies.PublicationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.double
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TemplateBundleExtractorTest {
    private val fhirJson = FhirR4Json()
    private val json = Json { ignoreUnknownKeys = true }
    private val extractor = TemplateBundleExtractor()
    private val uuidRegex =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    @Test
    fun extract_withExplicitTemplateJsons_buildsBundleFromResolvedResources() {
        val bundle =
            extractor.extract(
                questionnaireResponse = questionnaireResponse(),
                templateJsons = listOf(resourceTemplateJson),
            )
        val patient = assertIs<Patient>(
            bundle.entry
                .mapNotNull { it.resource }
                .first { it is Patient }
        )
        assertEquals(Bundle.BundleType.Transaction, bundle.type.value)
        //        assertEquals(true, patient.active?.value)
        assertEquals(AdministrativeGender.Male, patient.gender?.value)
    }

    @Test
    fun extract_withExplicitTemplateJsons_reusesGeneratedPatientUuidAcrossReferences() {
        val bundle =
            extractor.extract(
                questionnaireResponse = questionnaireResponse(),
                templateJsons = listOf(resourceTemplateJson),
            )

        val bundleJson = json.parseToJsonElement(fhirJson.encodeToString(bundle)).jsonObject
        val entryJsons = bundleJson["entry"]!!.jsonArray.map { it.jsonObject }

        val patientEntry =
            entryJsons.first { entry ->
                entry["resource"]!!.jsonObject["resourceType"]!!.jsonPrimitive.content == "Patient"
            }
        val patientFullUrl = patientEntry["fullUrl"]!!.jsonPrimitive.content
        val patientUuid = patientFullUrl.removePrefix("urn:uuid:")

        assertNotNull(patientUuid)
        assertTrue(uuidRegex.matches(patientUuid))

        val subjectResourceTypes = setOf("Observation", "Condition", "Specimen")
        val subjectEntries =
            entryJsons.filter { entry ->
                entry["resource"]!!.jsonObject["resourceType"]!!.jsonPrimitive.content in subjectResourceTypes
            }

        assertTrue(subjectEntries.isNotEmpty())
        subjectEntries.forEach { entry ->
            assertEquals(
                patientFullUrl,
                entry["resource"]!!.jsonObject["subject"]!!.jsonObject["reference"]!!.jsonPrimitive.content,
            )
        }

        val relatedPersonEntry =
            entryJsons.first { entry ->
                entry["resource"]!!.jsonObject["resourceType"]!!.jsonPrimitive.content == "RelatedPerson"
            }
        assertEquals(
            patientFullUrl,
            relatedPersonEntry["resource"]!!.jsonObject["patient"]!!.jsonObject["reference"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun extract_withoutExplicitTemplates_readsQuestionnaireExtensionPayloads() {
        val questionnaire =
            Questionnaire.Builder(status = Enumeration(value = PublicationStatus.Active))
                .apply {
                    extension =
                        mutableListOf(
                            Extension.Builder(TemplateBundleExtractor.MAPPING_TEMPLATE_EXTENSION_URL)
                                .apply {
                                    value =
                                        Extension.Value.String(FhirR4String(value = resourceTemplateJson))
                                }
                        )
                }
                .build()

        val bundle =
            extractor.extract(
                questionnaireResponse = questionnaireResponse(),
                questionnaire = questionnaire,
            )

        val patient = assertIs<Patient>(
            bundle.entry
                .mapNotNull { it.resource }
                .first { it is Patient }
        )
        assertEquals(Bundle.BundleType.Transaction, bundle.type.value)
        //        assertEquals(true, patient.active?.value)
        assertEquals(AdministrativeGender.Male, patient.gender?.value)
    }

    @Test
    fun extract_withoutExplicitTemplates_readsContainedQuestionnaireTemplateBundle() {
        val bundle =
            extractor.extract(
                questionnaireResponse = containedTemplateQuestionnaireResponse(),
                questionnaire = containedTemplateQuestionnaire(),
            )

        val patient = assertIs<Patient>(bundle.entry.mapNotNull { it.resource }.first { it is Patient })
        val observation =
            assertIs<Observation>(bundle.entry.mapNotNull { it.resource }.first { it is Observation })

        val bundleJson = json.parseToJsonElement(fhirJson.encodeToString(bundle)).jsonObject
        val entryJsons = bundleJson["entry"]!!.jsonArray.map { it.jsonObject }
        val patientJson = json.parseToJsonElement(fhirJson.encodeToString(patient)).jsonObject
        val observationJson = json.parseToJsonElement(fhirJson.encodeToString(observation)).jsonObject

        val patientEntry =
            entryJsons.first { entry ->
                entry["resource"]!!.jsonObject["resourceType"]!!.jsonPrimitive.content == "Patient"
            }
        val observationEntry =
            entryJsons.first { entry ->
                entry["resource"]!!.jsonObject["resourceType"]!!.jsonPrimitive.content == "Observation"
            }
        val patientFullUrl = patientEntry["fullUrl"]!!.jsonPrimitive.content

        assertEquals(Bundle.BundleType.Transaction, bundle.type.value)
        assertEquals(2, bundle.entry.size)
        assertEquals(AdministrativeGender.Male, patient.gender?.value)
        assertEquals("Mutua", patientJson["name"]!!.jsonArray[0].jsonObject["given"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("Nzioka", patientJson["name"]!!.jsonArray[0].jsonObject["family"]!!.jsonPrimitive.content)
        assertEquals(
            "+254723456789",
            patientJson["telecom"]!!.jsonArray[0].jsonObject["value"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "2026-04-27T17:09:36.402756Z",
            observationJson["effectiveDateTime"]!!.jsonPrimitive.content,
        )
        assertEquals(
            173.0,
            observationJson["valueQuantity"]!!.jsonObject["value"]!!.jsonPrimitive.double,
        )
        assertEquals(patientFullUrl, observationJson["subject"]!!.jsonObject["reference"]!!.jsonPrimitive.content)
        assertEquals(
            "urn:uuid:11111111-1111-4111-8111-111111111111",
            patientEntry["fullUrl"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "urn:uuid:22222222-2222-4222-8222-222222222222",
            observationEntry["fullUrl"]!!.jsonPrimitive.content,
        )
    }

    private fun containedTemplateQuestionnaire(): Questionnaire =
        fhirJson.decodeFromString(
            """
                {
                  "resourceType": "Questionnaire",
                  "status": "active",
                  "contained": [
                    {
                      "resourceType": "Bundle",
                      "id": "template-bundle",
                      "type": "transaction",
                      "entry": [
                        {
                          "fullUrl": "urn:uuid:11111111-1111-4111-8111-111111111111",
                          "extension": [
                            {
                              "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractContext",
                              "valueString": "item.where(linkId = 'patient')"
                            }
                          ],
                          "resource": {
                            "resourceType": "Patient",
                            "name": [
                              {
                                "extension": [
                                  {
                                    "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractContext",
                                    "valueString": "item.where(linkId = 'name')"
                                  }
                                ],
                                "_given": [
                                  {
                                    "extension": [
                                      {
                                        "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                                        "valueString": "item.where(linkId = 'given').answer.value"
                                      }
                                    ]
                                  }
                                ],
                                "_family": {
                                  "extension": [
                                    {
                                      "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                                      "valueString": "item.where(linkId = 'family').answer.value.first()"
                                    }
                                  ]
                                }
                              }
                            ],
                            "gender": "unknown",
                            "_gender": {
                              "extension": [
                                {
                                  "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                                  "valueString": "item.where(linkId = 'gender').answer.value.first().code"
                                }
                              ]
                            },
                            "telecom": [
                              {
                                "extension": [
                                  {
                                    "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractContext",
                                    "valueString": "item.where(linkId = 'telecom').item.where(linkId = 'phone').answer.value"
                                  }
                                ],
                                "system": "phone",
                                "_value": {
                                  "extension": [
                                    {
                                      "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                                      "valueString": "first()"
                                    }
                                  ]
                                }
                              }
                            ]
                          },
                          "request": {
                            "method": "POST",
                            "url": "Patient"
                          }
                        },
                        {
                          "fullUrl": "urn:uuid:22222222-2222-4222-8222-222222222222",
                          "extension": [
                            {
                              "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractContext",
                              "valueString": "item.where(linkId = 'observations').item.where(linkId = 'height')"
                            }
                          ],
                          "resource": {
                            "resourceType": "Observation",
                            "status": "final",
                            "code": {
                              "text": "Height"
                            },
                            "subject": {
                              "reference": "urn:uuid:11111111-1111-4111-8111-111111111111"
                            },
                            "effectiveDateTime": "1900-01-01",
                            "_effectiveDateTime": {
                              "extension": [
                                {
                                  "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                                  "valueString": "%resource.authored"
                                }
                              ]
                            },
                            "valueQuantity": {
                              "_value": {
                                "extension": [
                                  {
                                    "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                                    "valueString": "answer.value * 100"
                                  }
                                ]
                              },
                              "unit": "cm"
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
                      "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractBundle",
                      "valueReference": {
                        "reference": "#template-bundle"
                      }
                    }
                  ]
                }
            """.trimIndent(),
        ) as Questionnaire

    private fun containedTemplateQuestionnaireResponse(): QuestionnaireResponse =
        fhirJson.decodeFromString(
            """
                {
                  "resourceType": "QuestionnaireResponse",
                  "id": "qr-contained-template",
                  "status": "completed",
                  "authored": "2026-04-27T17:09:36.402756Z",
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
                                  "valueString": "Mutua"
                                }
                              ]
                            },
                            {
                              "linkId": "family",
                              "answer": [
                                {
                                  "valueString": "Nzioka"
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
                                "code": "male",
                                "display": "Male"
                              }
                            }
                          ]
                        },
                        {
                          "linkId": "telecom",
                          "item": [
                            {
                              "linkId": "phone",
                              "answer": [
                                {
                                  "valueString": "+254723456789"
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    },
                    {
                      "linkId": "observations",
                      "item": [
                        {
                          "linkId": "height",
                          "answer": [
                            {
                              "valueDecimal": 1.73
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent(),
        ) as QuestionnaireResponse

    private fun questionnaireResponse(): QuestionnaireResponse =
        fhirJson.decodeFromString(
            """
                {
                    "resourceType": "QuestionnaireResponse",
                    "extension": [
                        {
                            "url": "http://github.com/google-android/questionnaire-lastLaunched-timestamp",
                            "valueDateTime": "2026-04-27T17:02:20.906525Z"
                        }
                    ],
                    "questionnaire": "http://example.org/fhir/Questionnaire/general-patient-intake",
                    "status": "in-progress",
                    "authored": "2026-04-27T17:09:36.402756Z",
                    "item": [
                        {
                            "linkId": "patient",
                            "text": "Patient",
                            "item": [
                                {
                                    "linkId": "name",
                                    "text": "Name",
                                    "item": [
                                        {
                                            "linkId": "name.given",
                                            "text": "Given Name",
                                            "answer": [
                                                {
                                                    "valueString": "Mutua"
                                                }
                                            ]
                                        },
                                        {
                                            "linkId": "name.family",
                                            "text": "Family Name",
                                            "answer": [
                                                {
                                                    "valueString": "Nzioka"
                                                }
                                            ]
                                        }
                                    ]
                                },
                                {
                                    "linkId": "birthDate",
                                    "text": "Date of Birth",
                                    "answer": [
                                        {
                                            "valueDate": "1992-04-08"
                                        }
                                    ]
                                },
                                {
                                    "linkId": "gender",
                                    "text": "Administrative Gender",
                                    "answer": [
                                        {
                                            "valueCoding": {
                                                "code": "male",
                                                "display": "Male"
                                            }
                                        }
                                    ]
                                },
                                {
                                    "linkId": "telecom",
                                    "text": "Contact",
                                    "item": [
                                        {
                                            "linkId": "telecom.phone",
                                            "text": "Phone Number",
                                            "answer": [
                                                {
                                                    "valueString": "+254723456789"
                                                }
                                            ]
                                        },
                                        {
                                            "linkId": "telecom.email",
                                            "text": "Email Address",
                                            "answer": [
                                                {
                                                    "valueString": "Mutua.nzioka@test.local"
                                                }
                                            ]
                                        }
                                    ]
                                },
                                {
                                    "linkId": "address",
                                    "text": "Address",
                                    "item": [
                                        {
                                            "linkId": "address.line",
                                            "text": "Street Address",
                                            "answer": [
                                                {
                                                    "valueString": "Kaloleni Estate"
                                                }
                                            ]
                                        },
                                        {
                                            "linkId": "address.city",
                                            "text": "City",
                                            "answer": [
                                                {
                                                    "valueString": "Machakos"
                                                }
                                            ]
                                        },
                                        {
                                            "linkId": "address.postalCode",
                                            "text": "Postal Code",
                                            "answer": [
                                                {
                                                    "valueString": "90100"
                                                }
                                            ]
                                        },
                                        {
                                            "linkId": "address.country",
                                            "text": "Country",
                                            "answer": [
                                                {
                                                    "valueString": "Kenya"
                                                }
                                            ]
                                        }
                                    ]
                                },
                                {
                                    "linkId": "identifier",
                                    "text": "National ID",
                                    "answer": [
                                        {
                                            "valueString": "28763465"
                                        }
                                    ]
                                }
                            ]
                        },
                        {
                            "linkId": "observations",
                            "text": "Observations",
                            "item": [
                                {
                                    "linkId": "obs.vitals",
                                    "text": "Vital Signs",
                                    "item": [
                                        {
                                            "linkId": "obs.bp.systolic",
                                            "text": "Systolic Blood Pressure (mmHg)",
                                            "answer": [
                                                {
                                                    "valueInteger": 135
                                                }
                                            ]
                                        },
                                        {
                                            "linkId": "obs.bp.diastolic",
                                            "text": "Diastolic Blood Pressure (mmHg)",
                                            "answer": [
                                                {
                                                    "valueInteger": 88
                                                }
                                            ]
                                        },
                                        {
                                            "linkId": "obs.heartRate",
                                            "text": "Heart Rate (bpm)",
                                            "answer": [
                                                {
                                                    "valueInteger": 76
                                                }
                                            ]
                                        },
                                        {
                                            "linkId": "obs.temp",
                                            "text": "Body Temperature (°C)",
                                            "answer": [
                                                {
                                                    "valueDecimal": 37.4
                                                }
                                            ]
                                        },
                                        {
                                            "linkId": "obs.spo2",
                                            "text": "Oxygen Saturation SpO2 (%)",
                                            "answer": [
                                                {
                                                    "valueDecimal": 96.0
                                                }
                                            ]
                                        }
                                    ]
                                },
                                {
                                    "linkId": "obs.anthropometrics",
                                    "text": "Anthropometrics",
                                    "item": [
                                        {
                                            "linkId": "obs.weight",
                                            "text": "Body Weight (kg)",
                                            "answer": [
                                                {
                                                    "valueDecimal": 68.4
                                                }
                                            ]
                                        },
                                        {
                                            "linkId": "obs.height",
                                            "text": "Body Height (cm)",
                                            "answer": [
                                                {
                                                    "valueDecimal": 173.0
                                                }
                                            ]
                                        }
                                    ]
                                },
                                {
                                    "linkId": "obs.effectiveDate",
                                    "text": "Observation Date",
                                    "answer": [
                                        {
                                            "valueDate": "2026-04-27"
                                        }
                                    ]
                                }
                            ]
                        },
                        {
                            "linkId": "condition",
                            "text": "Condition",
                            "item": [
                                {
                                    "linkId": "condition.code.display",
                                    "text": "Condition Name",
                                    "answer": [
                                        {
                                            "valueString": "Hypertension"
                                        }
                                    ]
                                },
                                {
                                    "linkId": "condition.code.code",
                                    "text": "ICD-10 Code",
                                    "answer": [
                                        {
                                            "valueString": "i10"
                                        }
                                    ]
                                },
                                {
                                    "linkId": "condition.clinicalStatus",
                                    "text": "Clinical Status",
                                    "answer": [
                                        {
                                            "valueCoding": {
                                                "system": "http://terminology.hl7.org/CodeSystem/condition-clinical",
                                                "code": "active",
                                                "display": "Active"
                                            }
                                        }
                                    ]
                                },
                                {
                                    "linkId": "condition.verificationStatus",
                                    "text": "Verification Status",
                                    "answer": [
                                        {
                                            "valueCoding": {
                                                "system": "http://terminology.hl7.org/CodeSystem/condition-ver-status",
                                                "code": "confirmed",
                                                "display": "Confirmed"
                                            }
                                        }
                                    ]
                                },
                                {
                                    "linkId": "condition.severity",
                                    "text": "Severity",
                                    "answer": [
                                        {
                                            "valueCoding": {
                                                "system": "http://snomed.info/sct",
                                                "code": "6736007",
                                                "display": "Moderate"
                                            }
                                        }
                                    ]
                                },
                                {
                                    "linkId": "condition.onsetDate",
                                    "text": "Onset Date",
                                    "answer": [
                                        {
                                            "valueDate": "2026-02-17"
                                        }
                                    ]
                                },
                                {
                                    "linkId": "condition.note",
                                    "text": "Clinical Notes",
                                    "answer": [
                                        {
                                            "valueString": "Patient reports frequent dizziness and occasional chest discomfort. Monitoring blood pressure regulary"
                                        }
                                    ]
                                }
                            ]
                        },
                        {
                            "linkId": "specimen",
                            "text": "Specimen",
                            "item": [
                                {
                                    "linkId": "specimen.type",
                                    "text": "Specimen Type",
                                    "answer": [
                                        {
                                            "valueCoding": {
                                                "code": "blood",
                                                "display": "Whole blood"
                                            }
                                        }
                                    ]
                                },
                                {
                                    "linkId": "specimen.method",
                                    "text": "Collection Method",
                                    "answer": [
                                        {
                                            "valueCoding": {
                                                "code": "venipuncture",
                                                "display": "Venipuncture"
                                            }
                                        }
                                    ]
                                },
                                {
                                    "linkId": "specimen.collectedDate",
                                    "text": "Collection Date/Time",
                                    "answer": [
                                        {
                                            "valueDateTime": "2026-04-27T09:40:00+03:00"
                                        }
                                    ]
                                },
                                {
                                    "linkId": "specimen.accession",
                                    "text": "Accession Number",
                                    "answer": [
                                        {
                                            "valueString": "MKS-LAB-2026-17"
                                        }
                                    ]
                                },
                                {
                                    "linkId": "specimen.quantity",
                                    "text": "Volume (mL)",
                                    "answer": [
                                        {
                                            "valueDecimal": 4.5
                                        }
                                    ]
                                },
                                {
                                    "linkId": "specimen.container",
                                    "text": "Container Type",
                                    "answer": [
                                        {
                                            "valueString": "EDTA tube"
                                        }
                                    ]
                                },
                                {
                                    "linkId": "specimen.bodySite",
                                    "text": "Body Site",
                                    "answer": [
                                        {
                                            "valueString": "Right arm vein"
                                        }
                                    ]
                                }
                            ]
                        },
                        {
                            "linkId": "relatedPerson",
                            "text": "Related Person",
                            "item": [
                                {
                                    "linkId": "related.name",
                                    "text": "Name",
                                    "item": [
                                        {
                                            "linkId": "related.name.given",
                                            "text": "Given Name",
                                            "answer": [
                                                {
                                                    "valueString": "Mwikali"
                                                }
                                            ]
                                        },
                                        {
                                            "linkId": "related.name.family",
                                            "text": "Family Name",
                                            "answer": [
                                                {
                                                    "valueString": "Mutiso"
                                                }
                                            ]
                                        }
                                    ]
                                },
                                {
                                    "linkId": "related.relationship",
                                    "text": "Relationship to Patient",
                                    "answer": [
                                        {
                                            "valueCoding": {
                                                "system": "http://terminology.hl7.org/CodeSystem/v3-RoleCode",
                                                "code": "SPS",
                                                "display": "Spouse"
                                            }
                                        }
                                    ]
                                },
                                {
                                    "linkId": "related.gender",
                                    "text": "Gender",
                                    "answer": [
                                        {
                                            "valueCoding": {
                                                "code": "female",
                                                "display": "Female"
                                            }
                                        }
                                    ]
                                },
                                {
                                    "linkId": "related.birthDate",
                                    "text": "Date of Birth",
                                    "answer": [
                                        {
                                            "valueDate": "1994-04-17"
                                        }
                                    ]
                                },
                                {
                                    "linkId": "related.phone",
                                    "text": "Phone Number",
                                    "answer": [
                                        {
                                            "valueString": "+254798123876"
                                        }
                                    ]
                                },
                                {
                                    "linkId": "related.email",
                                    "text": "Email Address",
                                    "answer": [
                                        {
                                            "valueString": "Mwikali.mutiso@example.com"
                                        }
                                    ]
                                },
                                {
                                    "linkId": "related.active",
                                    "text": "Active",
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
                .trimIndent()
        ) as QuestionnaireResponse

    private companion object {
        val resourceTemplateJson: String =
            """
      [
        {
          "fullUrl": "urn:uuid:{{ uuid('patient') }}",
          "resource": {
            "resourceType": "Patient",
            "name": [
              {
                "given": [
                  "{{ QuestionnaireResponse.item.where(linkId='patient').item.where(linkId='name').item.where(linkId='name.given').answer.valueString }}"
                ],
                "family": "{{ QuestionnaireResponse.item.where(linkId='patient').item.where(linkId='name').item.where(linkId='name.family').answer.valueString }}"
              }
            ],
            "birthDate": "{{ QuestionnaireResponse.item.where(linkId='patient').item.where(linkId='birthDate').answer.valueDate }}",
            "gender": "{{ QuestionnaireResponse.item.where(linkId='patient').item.where(linkId='gender').answer.valueCoding.code }}",
            "telecom": [
              {
                "{% if QuestionnaireResponse.item.where(linkId='patient').item.where(linkId='telecom').item.where(linkId='telecom.phone').answer.valueString.exists() %}": {
                  "system": "phone",
                  "value": "{{ QuestionnaireResponse.item.where(linkId='patient').item.where(linkId='telecom').item.where(linkId='telecom.phone').answer.valueString }}"
                }
              },
              {
                "{% if QuestionnaireResponse.item.where(linkId='patient').item.where(linkId='telecom').item.where(linkId='telecom.email').answer.valueString.exists() %}": {
                  "system": "email",
                  "value": "{{ QuestionnaireResponse.item.where(linkId='patient').item.where(linkId='telecom').item.where(linkId='telecom.email').answer.valueString }}"
                }
              }
            ],
            "address": [
              {
                "{% if QuestionnaireResponse.item.where(linkId='patient').item.where(linkId='address').item.answer.valueString.exists() %}": {
                  "line": [
                    "{[ QuestionnaireResponse.item.where(linkId='patient').item.where(linkId='address').item.where(linkId='address.line').answer.valueString ]}"
                  ],
                  "city": "{{ QuestionnaireResponse.item.where(linkId='patient').item.where(linkId='address').item.where(linkId='address.city').answer.valueString }}",
                  "postalCode": "{{ QuestionnaireResponse.item.where(linkId='patient').item.where(linkId='address').item.where(linkId='address.postalCode').answer.valueString }}",
                  "country": "{{ QuestionnaireResponse.item.where(linkId='patient').item.where(linkId='address').item.where(linkId='address.country').answer.valueString }}"
                }
              }
            ],
            "identifier": [
              {
                "{% if QuestionnaireResponse.item.where(linkId='patient').item.where(linkId='identifier').answer.valueString.exists() %}": {
                  "value": "{{ QuestionnaireResponse.item.where(linkId='patient').item.where(linkId='identifier').answer.valueString }}"
                }
              }
            ]
          },
          "request": {
            "method": "POST",
            "url": "Patient"
          }
        },
        {
          "{% if QuestionnaireResponse.item.where(linkId='observations').item.where(linkId='obs.vitals').item.where(linkId='obs.bp.systolic').answer.valueInteger.exists() or QuestionnaireResponse.item.where(linkId='observations').item.where(linkId='obs.vitals').item.where(linkId='obs.bp.diastolic').answer.valueInteger.exists() %}": {
            "fullUrl": "urn:uuid:{{ uuid('observation-blood-pressure') }}",
            "resource": {
              "resourceType": "Observation",
              "status": "final",
              "code": {
                "coding": [
                  {
                    "system": "http://loinc.org",
                    "code": "85354-9",
                    "display": "Blood pressure panel with all children optional"
                  }
                ]
              },
              "subject": {
                "reference": "urn:uuid:{{ uuid('patient') }}"
              },
              "effectiveDateTime": "{{ QuestionnaireResponse.item.where(linkId='observations').item.where(linkId='obs.effectiveDate').answer.valueDate }}",
              "component": [
                {
                  "{% if QuestionnaireResponse.item.where(linkId='observations').item.where(linkId='obs.vitals').item.where(linkId='obs.bp.systolic').answer.valueInteger.exists() %}": {
                    "code": {
                      "coding": [
                        {
                          "system": "http://loinc.org",
                          "code": "8480-6",
                          "display": "Systolic blood pressure"
                        }
                      ]
                    },
                    "valueQuantity": {
                      "value": "{{ QuestionnaireResponse.item.where(linkId='observations').item.where(linkId='obs.vitals').item.where(linkId='obs.bp.systolic').answer.valueInteger }}",
                      "unit": "mmHg",
                      "system": "http://unitsofmeasure.org",
                      "code": "mm[Hg]"
                    }
                  }
                },
                {
                  "{% if QuestionnaireResponse.item.where(linkId='observations').item.where(linkId='obs.vitals').item.where(linkId='obs.bp.diastolic').answer.valueInteger.exists() %}": {
                    "code": {
                      "coding": [
                        {
                          "system": "http://loinc.org",
                          "code": "8462-4",
                          "display": "Diastolic blood pressure"
                        }
                      ]
                    },
                    "valueQuantity": {
                      "value": "{{ QuestionnaireResponse.item.where(linkId='observations').item.where(linkId='obs.vitals').item.where(linkId='obs.bp.diastolic').answer.valueInteger }}",
                      "unit": "mmHg",
                      "system": "http://unitsofmeasure.org",
                      "code": "mm[Hg]"
                    }
                  }
                }
              ]
            },
            "request": {
              "method": "POST",
              "url": "Observation"
            }
          }
        },
        {
          "{% if QuestionnaireResponse.item.where(linkId='observations').item.where(linkId='obs.vitals').item.where(linkId='obs.heartRate').answer.valueInteger.exists() %}": {
            "fullUrl": "urn:uuid:{{ uuid('observation-heart-rate') }}",
            "resource": {
              "resourceType": "Observation",
              "status": "final",
              "code": {
                "coding": [
                  {
                    "system": "http://loinc.org",
                    "code": "8867-4",
                    "display": "Heart rate"
                  }
                ]
              },
              "subject": {
                "reference": "urn:uuid:{{ uuid('patient') }}"
              },
              "effectiveDateTime": "{{ QuestionnaireResponse.item.where(linkId='observations').item.where(linkId='obs.effectiveDate').answer.valueDate }}",
              "valueQuantity": {
                "value": "{{ QuestionnaireResponse.item.where(linkId='observations').item.where(linkId='obs.vitals').item.where(linkId='obs.heartRate').answer.valueInteger }}",
                "unit": "beats/minute",
                "system": "http://unitsofmeasure.org",
                "code": "/min"
              }
            },
            "request": {
              "method": "POST",
              "url": "Observation"
            }
          }
        },
        {
          "{% if QuestionnaireResponse.item.where(linkId='observations').item.where(linkId='obs.vitals').item.where(linkId='obs.temp').answer.valueDecimal.exists() %}": {
            "fullUrl": "urn:uuid:{{ uuid('observation-temperature') }}",
            "resource": {
              "resourceType": "Observation",
              "status": "final",
              "code": {
                "coding": [
                  {
                    "system": "http://loinc.org",
                    "code": "8310-5",
                    "display": "Body temperature"
                  }
                ]
              },
              "subject": {
                "reference": "urn:uuid:{{ uuid('patient') }}"
              },
              "effectiveDateTime": "{{ QuestionnaireResponse.item.where(linkId='observations').item.where(linkId='obs.effectiveDate').answer.valueDate }}",
              "valueQuantity": {
                "value": "{{ QuestionnaireResponse.item.where(linkId='observations').item.where(linkId='obs.vitals').item.where(linkId='obs.temp').answer.valueDecimal }}",
                "unit": "degrees C",
                "system": "http://unitsofmeasure.org",
                "code": "Cel"
              }
            },
            "request": {
              "method": "POST",
              "url": "Observation"
            }
          }
        },
        {
          "{% if QuestionnaireResponse.item.where(linkId='observations').item.where(linkId='obs.vitals').item.where(linkId='obs.spo2').answer.valueDecimal.exists() %}": {
            "fullUrl": "urn:uuid:{{ uuid('observation-spo2') }}",
            "resource": {
              "resourceType": "Observation",
              "status": "final",
              "code": {
                "coding": [
                  {
                    "system": "http://loinc.org",
                    "code": "2708-6",
                    "display": "Oxygen saturation in Arterial blood"
                  }
                ]
              },
              "subject": {
                "reference": "urn:uuid:{{ uuid('patient') }}"
              },
              "effectiveDateTime": "{{ QuestionnaireResponse.item.where(linkId='observations').item.where(linkId='obs.effectiveDate').answer.valueDate }}",
              "valueQuantity": {
                "value": "{{ QuestionnaireResponse.item.where(linkId='observations').item.where(linkId='obs.vitals').item.where(linkId='obs.spo2').answer.valueDecimal }}",
                "unit": "%",
                "system": "http://unitsofmeasure.org",
                "code": "%"
              }
            },
            "request": {
              "method": "POST",
              "url": "Observation"
            }
          }
        },
        {
          "{% if QuestionnaireResponse.item.where(linkId='observations').item.where(linkId='obs.anthropometrics').item.where(linkId='obs.weight').answer.valueDecimal.exists() %}": {
            "fullUrl": "urn:uuid:{{ uuid('observation-weight') }}",
            "resource": {
              "resourceType": "Observation",
              "status": "final",
              "code": {
                "coding": [
                  {
                    "system": "http://loinc.org",
                    "code": "29463-7",
                    "display": "Body weight"
                  }
                ]
              },
              "subject": {
                "reference": "urn:uuid:{{ uuid('patient') }}"
              },
              "valueQuantity": {
                "value": "{{ QuestionnaireResponse.item.where(linkId='observations').item.where(linkId='obs.anthropometrics').item.where(linkId='obs.weight').answer.valueDecimal }}",
                "unit": "kg",
                "system": "http://unitsofmeasure.org",
                "code": "kg"
              }
            },
            "request": {
              "method": "POST",
              "url": "Observation"
            }
          }
        },
        {
          "{% if QuestionnaireResponse.item.where(linkId='observations').item.where(linkId='obs.anthropometrics').item.where(linkId='obs.height').answer.valueDecimal.exists() %}": {
            "fullUrl": "urn:uuid:{{ uuid('observation-height') }}",
            "resource": {
              "resourceType": "Observation",
              "status": "final",
              "code": {
                "coding": [
                  {
                    "system": "http://loinc.org",
                    "code": "8302-2",
                    "display": "Body height"
                  }
                ]
              },
              "subject": {
                "reference": "urn:uuid:{{ uuid('patient') }}"
              },
              "valueQuantity": {
                "value": "{{ QuestionnaireResponse.item.where(linkId='observations').item.where(linkId='obs.anthropometrics').item.where(linkId='obs.height').answer.valueDecimal }}",
                "unit": "cm",
                "system": "http://unitsofmeasure.org",
                "code": "cm"
              }
            },
            "request": {
              "method": "POST",
              "url": "Observation"
            }
          }
        },
        {
          "{% if QuestionnaireResponse.item.where(linkId='condition').item.where(linkId='condition.code.display').answer.valueString.exists() %}": {
            "fullUrl": "urn:uuid:{{ uuid('condition') }}",
            "resource": {
              "resourceType": "Condition",
              "clinicalStatus": {
                "coding": [
                  {
                    "system": "http://terminology.hl7.org/CodeSystem/condition-clinical",
                    "code": "{{ QuestionnaireResponse.item.where(linkId='condition').item.where(linkId='condition.clinicalStatus').answer.valueCoding.code }}",
                    "display": "{{ QuestionnaireResponse.item.where(linkId='condition').item.where(linkId='condition.clinicalStatus').answer.valueCoding.display }}"
                  }
                ]
              },
              "verificationStatus": {
                "coding": [
                  {
                    "system": "http://terminology.hl7.org/CodeSystem/condition-ver-status",
                    "code": "{{ QuestionnaireResponse.item.where(linkId='condition').item.where(linkId='condition.verificationStatus').answer.valueCoding.code }}",
                    "display": "{{ QuestionnaireResponse.item.where(linkId='condition').item.where(linkId='condition.verificationStatus').answer.valueCoding.display }}"
                  }
                ]
              },
              "severity": {
                "{% if QuestionnaireResponse.item.where(linkId='condition').item.where(linkId='condition.severity').answer.valueCoding.code.exists() %}": {
                  "coding": [
                    {
                      "system": "http://snomed.info/sct",
                      "code": "{{ QuestionnaireResponse.item.where(linkId='condition').item.where(linkId='condition.severity').answer.valueCoding.code }}",
                      "display": "{{ QuestionnaireResponse.item.where(linkId='condition').item.where(linkId='condition.severity').answer.valueCoding.display }}"
                    }
                  ]
                }
              },
              "code": {
                "coding": [
                  {
                    "system": "http://hl7.org/fhir/sid/icd-10",
                    "code": "{{ QuestionnaireResponse.item.where(linkId='condition').item.where(linkId='condition.code.code').answer.valueString }}",
                    "display": "{{ QuestionnaireResponse.item.where(linkId='condition').item.where(linkId='condition.code.display').answer.valueString }}"
                  }
                ],
                "text": "{{ QuestionnaireResponse.item.where(linkId='condition').item.where(linkId='condition.code.display').answer.valueString }}"
              },
              "onsetDateTime": {
                "{% if QuestionnaireResponse.item.where(linkId='condition').item.where(linkId='condition.onsetDate').answer.valueDate.exists() %}": "{{ QuestionnaireResponse.item.where(linkId='condition').item.where(linkId='condition.onsetDate').answer.valueDate }}"
              },
              "note": [
                {
                  "{% if QuestionnaireResponse.item.where(linkId='condition').item.where(linkId='condition.note').answer.valueString.exists() %}": {
                    "text": "{{ QuestionnaireResponse.item.where(linkId='condition').item.where(linkId='condition.note').answer.valueString }}"
                  }
                }
              ],
              "subject": {
                "reference": "urn:uuid:{{ uuid('patient') }}"
              }
            },
            "request": {
              "method": "POST",
              "url": "Condition"
            }
          }
        },
        {
          "{% if QuestionnaireResponse.item.where(linkId='specimen').item.where(linkId='specimen.type').answer.valueCoding.code.exists() %}": {
            "fullUrl": "urn:uuid:{{ uuid('specimen') }}",
            "resource": {
              "resourceType": "Specimen",
              "accessionIdentifier": {
                "{% if QuestionnaireResponse.item.where(linkId='specimen').item.where(linkId='specimen.accession').answer.valueString.exists() %}": {
                  "value": "{{ QuestionnaireResponse.item.where(linkId='specimen').item.where(linkId='specimen.accession').answer.valueString }}"
                }
              },
              "type": {
                "coding": [
                  {
                    "code": "{{ QuestionnaireResponse.item.where(linkId='specimen').item.where(linkId='specimen.type').answer.valueCoding.code }}",
                    "display": "{{ QuestionnaireResponse.item.where(linkId='specimen').item.where(linkId='specimen.type').answer.valueCoding.display }}"
                  }
                ],
                "text": "{{ QuestionnaireResponse.item.where(linkId='specimen').item.where(linkId='specimen.type').answer.valueCoding.display }}"
              },
              "collection": {
                "collectedDateTime": {
                  "{% if QuestionnaireResponse.item.where(linkId='specimen').item.where(linkId='specimen.collectedDate').answer.valueDateTime.exists() %}": "{{ QuestionnaireResponse.item.where(linkId='specimen').item.where(linkId='specimen.collectedDate').answer.valueDateTime }}"
                },
                "method": {
                  "{% if QuestionnaireResponse.item.where(linkId='specimen').item.where(linkId='specimen.method').answer.valueCoding.code.exists() %}": {
                    "coding": [
                      {
                        "code": "{{ QuestionnaireResponse.item.where(linkId='specimen').item.where(linkId='specimen.method').answer.valueCoding.code }}",
                        "display": "{{ QuestionnaireResponse.item.where(linkId='specimen').item.where(linkId='specimen.method').answer.valueCoding.display }}"
                      }
                    ]
                  }
                },
                "bodySite": {
                  "{% if QuestionnaireResponse.item.where(linkId='specimen').item.where(linkId='specimen.bodySite').answer.valueString.exists() %}": {
                    "coding": [
                      {
                        "display": "{{ QuestionnaireResponse.item.where(linkId='specimen').item.where(linkId='specimen.bodySite').answer.valueString }}"
                      }
                    ]
                  }
                },
                "quantity": {
                  "{% if QuestionnaireResponse.item.where(linkId='specimen').item.where(linkId='specimen.quantity').answer.valueDecimal.exists() %}": {
                    "value": "{{ QuestionnaireResponse.item.where(linkId='specimen').item.where(linkId='specimen.quantity').answer.valueDecimal }}",
                    "unit": "mL",
                    "system": "http://unitsofmeasure.org",
                    "code": "mL"
                  }
                }
              },
              "container": [
                {
                  "{% if QuestionnaireResponse.item.where(linkId='specimen').item.where(linkId='specimen.container').answer.valueString.exists() %}": {
                    "description": "{{ QuestionnaireResponse.item.where(linkId='specimen').item.where(linkId='specimen.container').answer.valueString }}"
                  }
                }
              ],
              "subject": {
                "reference": "urn:uuid:{{ uuid('patient') }}"
              }
            },
            "request": {
              "method": "POST",
              "url": "Specimen"
            }
          }
        },
        {
          "{% if QuestionnaireResponse.item.where(linkId='relatedPerson').item.where(linkId='related.active').answer.valueBoolean.exists() or QuestionnaireResponse.item.where(linkId='relatedPerson').item.where(linkId='related.relationship').answer.valueCoding.code.exists() or QuestionnaireResponse.item.where(linkId='relatedPerson').item.where(linkId='related.name').item.where(linkId='related.name.given').answer.valueString.exists() or QuestionnaireResponse.item.where(linkId='relatedPerson').item.where(linkId='related.name').item.where(linkId='related.name.family').answer.valueString.exists() or QuestionnaireResponse.item.where(linkId='relatedPerson').item.where(linkId='related.gender').answer.valueCoding.code.exists() or QuestionnaireResponse.item.where(linkId='relatedPerson').item.where(linkId='related.birthDate').answer.valueDate.exists() or QuestionnaireResponse.item.where(linkId='relatedPerson').item.where(linkId='related.phone').answer.valueString.exists() or QuestionnaireResponse.item.where(linkId='relatedPerson').item.where(linkId='related.email').answer.valueString.exists() %}": {
            "fullUrl": "urn:uuid:{{ uuid('related-person') }}",
            "resource": {
              "resourceType": "RelatedPerson",
              "active": "{{ QuestionnaireResponse.item.where(linkId='relatedPerson').item.where(linkId='related.active').answer.valueBoolean }}",
              "relationship": [
                {
                  "{% if QuestionnaireResponse.item.where(linkId='relatedPerson').item.where(linkId='related.relationship').answer.valueCoding.code.exists() %}": {
                    "coding": [
                      {
                        "system": "http://terminology.hl7.org/CodeSystem/v3-RoleCode",
                        "code": "{{ QuestionnaireResponse.item.where(linkId='relatedPerson').item.where(linkId='related.relationship').answer.valueCoding.code }}",
                        "display": "{{ QuestionnaireResponse.item.where(linkId='relatedPerson').item.where(linkId='related.relationship').answer.valueCoding.display }}"
                      }
                    ]
                  }
                }
              ],
              "name": [
                {
                  "given": [
                    "{{ QuestionnaireResponse.item.where(linkId='relatedPerson').item.where(linkId='related.name').item.where(linkId='related.name.given').answer.valueString }}"
                  ],
                  "family": "{{ QuestionnaireResponse.item.where(linkId='relatedPerson').item.where(linkId='related.name').item.where(linkId='related.name.family').answer.valueString }}"
                }
              ],
              "gender": "{{ QuestionnaireResponse.item.where(linkId='relatedPerson').item.where(linkId='related.gender').answer.valueCoding.code }}",
              "birthDate": {
                "{% if QuestionnaireResponse.item.where(linkId='relatedPerson').item.where(linkId='related.birthDate').answer.valueDate.exists() %}": "{{ QuestionnaireResponse.item.where(linkId='relatedPerson').item.where(linkId='related.birthDate').answer.valueDate }}"
              },
              "telecom": [
                {
                  "{% if QuestionnaireResponse.item.where(linkId='relatedPerson').item.where(linkId='related.phone').answer.valueString.exists() %}": {
                    "system": "phone",
                    "value": "{{ QuestionnaireResponse.item.where(linkId='relatedPerson').item.where(linkId='related.phone').answer.valueString }}"
                  }
                },
                {
                  "{% if QuestionnaireResponse.item.where(linkId='relatedPerson').item.where(linkId='related.email').answer.valueString.exists() %}": {
                    "system": "email",
                    "value": "{{ QuestionnaireResponse.item.where(linkId='relatedPerson').item.where(linkId='related.email').answer.valueString }}"
                  }
                }
              ],
              "patient": {
                "reference": "urn:uuid:{{ uuid('patient') }}"
              }
            },
            "request": {
              "method": "POST",
              "url": "RelatedPerson"
            }
          }
        }
      ]
      """
                .trimIndent()
    }
}
