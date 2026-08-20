/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

object AiToolArgumentsValidator {
    fun validate(
        schema: JsonObject,
        arguments: JsonObject,
    ): String? {
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required =
            (schema["required"] as? JsonArray)
                .orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull }
                .toSet()
        required.firstOrNull { it !in arguments }?.let { return "$it is required." }

        if (schema["additionalProperties"]?.jsonPrimitive?.booleanOrNull == false) {
            arguments.keys.firstOrNull { it !in properties }?.let {
                return "$it is not an accepted argument."
            }
        }

        arguments.forEach { (name, value) ->
            val propertySchema = properties[name] as? JsonObject ?: return@forEach
            validateValue(propertySchema, value, name)?.let { return it }
        }
        return null
    }

    private fun validateValue(
        schema: JsonObject,
        value: JsonElement,
        path: String,
    ): String? =
        when (schema["type"]?.jsonPrimitive?.contentOrNull) {
            "string" -> validateString(schema, value, path)
            "integer" -> validateInteger(schema, value, path)
            "boolean" ->
                if ((value as? JsonPrimitive)?.booleanOrNull == null) "$path must be a boolean." else null
            "array" -> validateArray(schema, value, path)
            "object" ->
                (value as? JsonObject)?.let { validate(schema, it) }
                    ?: "$path must be an object."
            else -> null
        }

    private fun validateString(
        schema: JsonObject,
        value: JsonElement,
        path: String,
    ): String? {
        val primitive = value as? JsonPrimitive
            ?: return "$path must be a string."
        if (!primitive.isString) return "$path must be a string."
        val content = primitive.content
        val minimum = schema["minLength"]?.jsonPrimitive?.intOrNull
        val maximum = schema["maxLength"]?.jsonPrimitive?.intOrNull
        if (minimum != null && content.length < minimum) return "$path is too short."
        if (maximum != null && content.length > maximum) return "$path is too long."
        val accepted = (schema["enum"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
        if (accepted != null && content !in accepted) return "$path has an unsupported value."
        return null
    }

    private fun validateInteger(
        schema: JsonObject,
        value: JsonElement,
        path: String,
    ): String? {
        val number = (value as? JsonPrimitive)?.intOrNull
            ?: return "$path must be an integer."
        val minimum = schema["minimum"]?.jsonPrimitive?.intOrNull
        val maximum = schema["maximum"]?.jsonPrimitive?.intOrNull
        if (minimum != null && number < minimum) return "$path is below the minimum."
        if (maximum != null && number > maximum) return "$path is above the maximum."
        return null
    }

    private fun validateArray(
        schema: JsonObject,
        value: JsonElement,
        path: String,
    ): String? {
        val array = value as? JsonArray ?: return "$path must be an array."
        val minimum = schema["minItems"]?.jsonPrimitive?.intOrNull
        val maximum = schema["maxItems"]?.jsonPrimitive?.intOrNull
        if (minimum != null && array.size < minimum) return "$path has too few items."
        if (maximum != null && array.size > maximum) return "$path has too many items."
        if (schema["uniqueItems"]?.jsonPrimitive?.booleanOrNull == true && array.distinct().size != array.size) {
            return "$path must contain unique items."
        }
        val itemSchema = schema["items"] as? JsonObject ?: return null
        array.forEachIndexed { index, item ->
            validateValue(itemSchema, item, "$path[$index]")?.let { return it }
        }
        return null
    }
}
