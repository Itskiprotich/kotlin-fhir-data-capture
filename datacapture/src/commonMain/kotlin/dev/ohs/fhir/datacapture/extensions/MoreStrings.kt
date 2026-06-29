/*
 * Copyright 2025-2026 Open Health Stack Foundation
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

import androidx.compose.ui.text.AnnotatedString
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.toBigDecimal
import dev.ohs.fhir.model.r4.QuestionnaireResponse

private const val SDC_RESOURCE_ROOT_VARIABLE: String = "sdcResourceRoot"
private val sdcResourceVariablePattern = Regex("""%resource\b""")

internal fun String.toAnnotatedString(): AnnotatedString = AnnotatedString(this)

internal fun String.toBigDecimalOrNull(): BigDecimal? =
  try {
    this.toBigDecimal()
  } catch (_: NumberFormatException) {
    null
  } catch (_: ArithmeticException) {
    null
  }

/** FHIRPath variables are referenced with `%`, while lookup maps store the bare identifier. */
internal fun String.normalizedVariableName(): String = removePrefix("%")

/**
 * The shared FHIRPath engine binds `%resource` to the evaluation base value. SDC extraction instead
 * defines `%resource` as the root QuestionnaireResponse while the current item/group stays as the
 * normal evaluation context. We rewrite `%resource` to an internal variable so extraction
 * expressions can follow the SDC supplement without changing the upstream engine.
 */
internal fun String.normalizeSdcExtractionExpression(): String =
  sdcResourceVariablePattern.replace(this, "%$SDC_RESOURCE_ROOT_VARIABLE")

internal fun Map<String, Any?>.withSdcResourceRoot(
  questionnaireResponse: QuestionnaireResponse
): Map<String, Any?> = this + (SDC_RESOURCE_ROOT_VARIABLE to questionnaireResponse)
