/*
 * Copyright 2025-2026 Google LLC
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

package dev.ohs.fhir.datacapture.fhirpath

import co.touchlab.kermit.Logger
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import dev.ohs.fhir.fhirpath.FhirPathEngine
import dev.ohs.fhir.fhirpath.types.FhirPathDate
import dev.ohs.fhir.fhirpath.types.FhirPathDateTime
import dev.ohs.fhir.fhirpath.types.FhirPathQuantity
import dev.ohs.fhir.model.r4.Resource

/** Centralized service for FHIRPath evaluation and utility functions. */
internal object FhirPathService {
  private val r4FhirPathEngine = FhirPathEngine.forR4()

  /**
   * Evaluates the [expression] on the [resource] with optional [variables].
   *
   * @param expression The FHIRPath expression to evaluate.
   * @param input The input value to evaluate the expression against.
   * @param variables Optional map of variables to use during evaluation.
   * @return The list of evaluation results.
   */
  fun evaluate(
    expression: String,
    input: Any?,
    variables: Map<String, Any?> = emptyMap(),
  ): List<Any> =
    try {
      r4FhirPathEngine.evaluateExpression(expression, input, variables).toList()
    } catch (throwable: Throwable) {
      Logger.e("Error evaluating fhirPath expression $expression", throwable)
      emptyList()
    }

  /** Converts the FHIRPath evaluation [result] to a boolean. */
  fun convertToBoolean(result: List<Any>): Boolean {
    if (result.isEmpty()) return false
    if (result.size == 1) return result.first() as Boolean
    return result.isNotEmpty()
  }

  /** Converts the FHIRPath evaluation [results] to a string. */
  fun convertToString(results: List<Any>): String =
    when {
      results.isEmpty() -> ""
      results.size == 1 -> convertSingleResultToString(results.first())
      else -> results.joinToString(", ") { convertSingleResultToString(it) }
    }

  private fun convertSingleResultToString(value: Any): String =
    when (value) {
      is dev.ohs.fhir.model.r4.String -> value.value ?: ""
      is dev.ohs.fhir.model.r4.Integer -> value.value?.toString() ?: ""
      is dev.ohs.fhir.model.r4.Decimal -> value.value?.toString() ?: ""
      is dev.ohs.fhir.model.r4.Boolean -> value.value?.toString() ?: ""
      is dev.ohs.fhir.model.r4.Date -> value.value?.toString() ?: ""
      is dev.ohs.fhir.model.r4.DateTime -> value.value?.toString() ?: ""
      is dev.ohs.fhir.model.r4.Time -> value.value?.toString() ?: ""
      is dev.ohs.fhir.model.r4.Code -> value.value ?: ""
      is dev.ohs.fhir.model.r4.Uri -> value.value ?: ""
      is dev.ohs.fhir.model.r4.Coding -> value.display?.value ?: value.code?.value ?: ""
      is dev.ohs.fhir.model.r4.Quantity -> value.value?.value?.toString() ?: ""
      is Boolean -> value.toString()
      is Int -> value.toString()
      is Long -> value.toString()
      is Double -> value.toString()
      is Float -> value.toString()
      is BigDecimal -> value.toString()
      is FhirPathDate -> value.toString()
      is FhirPathDateTime -> convertFhirPathDateTimeToString(value)
      is FhirPathQuantity -> value.value?.toString() ?: ""
      else -> value.toString()
    }

  private fun convertFhirPathDateTimeToString(value: FhirPathDateTime): String {
    val year = value.year.toString().padStart(4, '0')
    val month = value.month?.toString()?.padStart(2, '0')
    val day = value.day?.toString()?.padStart(2, '0')
    val hour = value.hour?.toString()?.padStart(2, '0')
    val minute = value.minute?.toString()?.padStart(2, '0')
    val second =
      value.second?.let {
        val wholeSeconds = it.toInt()
        if (it == wholeSeconds.toDouble()) {
          wholeSeconds.toString().padStart(2, '0')
        } else {
          it.toString().padStart(2, '0')
        }
      }

    val datePortion =
      when (value.precision) {
        FhirPathDateTime.Precision.YEAR -> year
        FhirPathDateTime.Precision.MONTH -> "$year-$month"
        FhirPathDateTime.Precision.DAY -> "$year-$month-$day"
        FhirPathDateTime.Precision.HOUR -> "$year-$month-$day" + "T$hour"
        FhirPathDateTime.Precision.MINUTE -> "$year-$month-$day" + "T$hour:$minute"
        FhirPathDateTime.Precision.SECOND -> "$year-$month-$day" + "T$hour:$minute:$second"
      }

    return datePortion + (value.utcOffset?.toString() ?: "")
  }

  /** Extracts the resource type from the given FHIRPath. */
  fun extractResourceTypeFromPath(fhirPath: String): String? {
    val trimmedPath = fhirPath.trim()
    val firstToken = trimmedPath.split('.', '(', '[', ' ').firstOrNull() ?: return null
    return firstToken.takeIf { it.firstOrNull()?.isUpperCase() == true }
  }

  /** Evaluates the [expression] on the [resource] and returns the result as a string. */
  fun evaluateFhirPathToString(expression: String, resource: Resource?): String {
    if (resource == null) return ""
    return convertToString(evaluate(expression, resource))
  }

  /**
   * Evaluates the [expressions] over the [data] resource and joins them to a space-separated
   * string.
   */
  fun evaluateToDisplay(expressions: List<String>, data: Resource) =
    expressions.joinToString(" ") { evaluateFhirPathToString(it, data) }
}
