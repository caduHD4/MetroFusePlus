package com.metrolist.music.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiToolArgumentsValidatorTest {
    private val schema =
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "songIds",
                        buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string") })
                            put("minItems", 1)
                            put("maxItems", 2)
                            put("uniqueItems", true)
                        },
                    )
                    put(
                        "position",
                        buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(listOf(JsonPrimitive("next"), JsonPrimitive("end"))))
                        },
                    )
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("songIds"))))
            put("additionalProperties", false)
        }

    @Test
    fun `accepts valid typed arguments`() {
        val arguments =
            buildJsonObject {
                put("songIds", buildJsonArray { add(JsonPrimitive("real-id")) })
                put("position", "next")
            }

        assertNull(AiToolArgumentsValidator.validate(schema, arguments))
    }

    @Test
    fun `rejects unknown fields invalid enums and oversized arrays`() {
        val unknown =
            buildJsonObject {
                put("songIds", buildJsonArray { add(JsonPrimitive("id")) })
                put("x", true)
            }
        val invalidEnum =
            buildJsonObject {
                put("songIds", buildJsonArray { add(JsonPrimitive("id")) })
                put("position", "somewhere")
            }
        val oversized =
            buildJsonObject {
                put(
                    "songIds",
                    buildJsonArray {
                        add(JsonPrimitive("a"))
                        add(JsonPrimitive("b"))
                        add(JsonPrimitive("c"))
                    },
                )
            }

        assertTrue(AiToolArgumentsValidator.validate(schema, unknown)!!.contains("not an accepted"))
        assertTrue(AiToolArgumentsValidator.validate(schema, invalidEnum)!!.contains("unsupported"))
        assertTrue(AiToolArgumentsValidator.validate(schema, oversized)!!.contains("too many"))
    }
}
