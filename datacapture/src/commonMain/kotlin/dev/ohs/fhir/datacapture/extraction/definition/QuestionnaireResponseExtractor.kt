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
package dev.ohs.fhir.datacapture.extraction.definition

import dev.ohs.fhir.datacapture.extraction.template.TemplateExtractionEngine
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Questionnaire
import dev.ohs.fhir.model.r4.QuestionnaireResponse

/**
 * Public entry point for QuestionnaireResponse extraction.
 *
 * Template-based extraction takes precedence when both mechanisms are present, matching the
 * behavior that existed before the definition extraction rewrite.
 */
object QuestionnaireResponseExtractor {
  fun canExtractTemplate(questionnaire: Questionnaire): Boolean =
    TemplateExtractionEngine.canExtract(questionnaire)

  fun canExtractDefinition(questionnaire: Questionnaire): Boolean =
    DefinitionExtractionEngine.canExtract(questionnaire)

  fun canExtract(questionnaire: Questionnaire): Boolean =
    canExtractTemplate(questionnaire) || canExtractDefinition(questionnaire)

  fun extractTemplate(
    questionnaire: Questionnaire,
    questionnaireResponse: QuestionnaireResponse,
  ): Bundle = TemplateExtractionEngine.extract(questionnaire, questionnaireResponse)

  fun extractDefinition(
    questionnaire: Questionnaire,
    questionnaireResponse: QuestionnaireResponse,
  ): Bundle = DefinitionExtractionEngine.extractByDefinition(questionnaire, questionnaireResponse)

  fun extract(questionnaire: Questionnaire, questionnaireResponse: QuestionnaireResponse): Bundle =
    when {
      canExtractTemplate(questionnaire) -> extractTemplate(questionnaire, questionnaireResponse)
      canExtractDefinition(questionnaire) -> extractDefinition(questionnaire, questionnaireResponse)
      else -> error("No extraction instructions were found in the questionnaire.")
    }
}
