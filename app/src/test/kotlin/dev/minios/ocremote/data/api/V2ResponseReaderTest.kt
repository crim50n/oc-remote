package dev.minios.ocremote.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class V2ResponseReaderTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun read(raw: String, endpoint: String, limits: V2ResponseLimits = V2ResponseLimits()) =
        readV2Response(json, raw.reader(), endpoint, limits)

    @Test
    fun largeIgnoredCatalogFieldsDoNotConsumeRetainedTreeBudget() {
        val ignoredMetadata = buildString {
            append('[')
            repeat(12_000) { index ->
                if (index > 0) append(',')
                append("{\"documentation\":\"")
                append("x".repeat(200))
                append("\",\"credential\":\"PLACEHOLDER_ONLY\"}")
            }
            append(']')
        }
        assertTrue(ignoredMetadata.length > 2 * 1024 * 1024)
        val raw = """{"data":[{"id":"model_1","name":"Model","providerID":"provider_1","enabled":true,
            "capabilities":{"tools":true,"input":["text"]},"metadata":""" + ignoredMetadata +
            """}],"credentials":""" + ignoredMetadata + "}"
        val result = read(raw, "/api/model", V2ResponseLimits(maxNodes = 20, maxRetainedChars = 200))
        val model = result.jsonObject["data"]!!.jsonArray.single().jsonObject
        assertEquals("model_1", model["id"]!!.jsonPrimitive.content)
        assertFalse(model.containsKey("metadata"))
        assertFalse(result.jsonObject.containsKey("credentials"))
    }

    @Test
    fun providerAndModelProjectionPreservesNormalizedCatalogAndFirstPrice() {
        val providers = """{"data":[{"id":"p","name":"Provider","package":"sdk","activation":"enabled",
            "integrationID":"integration","settings":{"baseURL":"https://example.invalid","apiKey":"PLACEHOLDER_ONLY"},
            "credentials":[{"key":"PLACEHOLDER_ONLY"}]}]}"""
        val models = """{"data":[{"id":"m","providerID":"p","name":"Model","enabled":true,
            "family":"family","status":"active",
            "capabilities":{"tools":true,"input":["text","image"],"output":["text"],"ignored":{"large":[]}},
            "limit":{"context":100000,"input":90000,"output":10000},
            "cost":[{"input":0.25,"output":1.5,"cache":{"read":0.01,"write":0.02},"ignored":{}},
                    {"input":99,"output":99,"ignored":{"large":[]}}],
            "variants":[{"id":"high","options":{"reasoningBudget":30000}},{"id":"fast"}]}]}"""
        val expected = V2Protocol.providers(json, json.parseToJsonElement(providers).jsonObject["data"]!!.jsonArray,
            json.parseToJsonElement(models).jsonObject["data"]!!.jsonArray)
        val projectedProviders = read(providers, "/api/provider").jsonObject["data"]!!.jsonArray
        val projectedModels = read(models, "/api/model").jsonObject["data"]!!.jsonArray
        val actual = V2Protocol.providers(json, projectedProviders, projectedModels)
        val expectedModel = expected.all.single().models.getValue("m")
        val actualModel = actual.all.single().models.getValue("m")
        assertEquals(expected.connected, actual.connected)
        assertEquals(expected.all.single().source, actual.all.single().source)
        assertEquals(expectedModel.copy(variants = actualModel.variants), actualModel)
        assertEquals(setOf("high", "fast"), actualModel.variants!!.keys)
        assertTrue(actualModel.variants!!.values.all { it.jsonObject.keys == setOf("id") })
        assertEquals(1, projectedModels.single().jsonObject["cost"]!!.jsonArray.size)
        assertEquals(0.25, projectedModels.single().jsonObject["cost"]!!.jsonArray.single().jsonObject["input"]!!.jsonPrimitive.double, 0.0)
        assertEquals(setOf("baseURL"), projectedProviders.single().jsonObject["settings"]!!.jsonObject.keys)
        assertFalse(projectedProviders.single().jsonObject.containsKey("credentials"))
    }

    @Test
    fun sessionPageKeepsPaginationAndNormalizedSessionIncludingLegacyAliases() {
        val raw = """{"data":[{"sessionID":"s","parentID":"parent","projectID":"project","title":"Title",
            "slug":"chat","version":"2.0.22","created":9,"location":{"directory":"/work","unused":{}},
            "time":{"created":10,"updated":20,"idle":21,"viewed":22,"archived":30},
            "permissions":[{"action":"read","resource":"*","effect":"ask","unused":{}}],
            "revert":{"messageID":"m","partID":"part","snapshot":"snapshot","files":[{"text":"unused"}]},
            "metadata":{"ignored":[]}}],"cursor":{"next":"page2","previous":"page0"},
            "location":{"directory":"/work"},"debug":{"unused":[]}}"""
        val original = json.parseToJsonElement(raw).jsonObject
        val projected = read(raw, "/api/session?limit=100").jsonObject
        assertEquals(V2Protocol.session(json, original["data"]!!.jsonArray.single().jsonObject),
            V2Protocol.session(json, projected["data"]!!.jsonArray.single().jsonObject))
        assertEquals(original["cursor"], projected["cursor"])
        assertEquals(original["location"], projected["location"])
        assertFalse(projected["data"]!!.jsonArray.single().jsonObject.containsKey("metadata"))
        val detail = read("""{"data":""" + original["data"]!!.jsonArray.single() + "}", "/api/session/s").jsonObject
        assertEquals(projected["data"]!!.jsonArray.single(), detail["data"])
    }

    @Test
    fun configKeepsScalarAndStructuredModelReferencesWithoutProviderSecrets() {
        val raw = """[{"source":{"path":"ignored"},"info":{"model":"p/m","default_agent":"build",
            "provider":{"p":{"options":{"apiKey":"PLACEHOLDER_ONLY"}}}}},
            {"info":{"model":{"providerID":"p","model":"m2","variant":"high","unused":{}},
             "default_agent":"plan","credentials":["PLACEHOLDER_ONLY"]}}]"""
        val entries = read(raw, "/api/config").jsonArray
        assertEquals(JsonPrimitive("p/m"), entries[0].jsonObject["info"]!!.jsonObject["model"])
        assertEquals(setOf("model", "default_agent"), entries[0].jsonObject["info"]!!.jsonObject.keys)
        assertEquals(setOf("providerID", "model", "variant"), entries[1].jsonObject["info"]!!.jsonObject["model"]!!.jsonObject.keys)
        assertNull(entries[1].jsonObject["info"]!!.jsonObject["credentials"])
    }

    @Test
    fun projectAndAgentProjectionPreservesUiModels() {
        val projects = """[{"id":"p","directory":"/work","vcs":"git","name":"Project",
            "sandboxes":["/sandbox"],"metadata":{"unused":[]}}]"""
        val agents = """{"data":[{"id":"build","name":"Build","description":"Builder","mode":"primary",
            "hidden":false,"color":"blue","prompt":"unused","options":{"unused":[]}}]}"""
        assertEquals(V2Protocol.project(json, json.parseToJsonElement(projects).jsonArray.single().jsonObject),
            V2Protocol.project(json, read(projects, "/api/project").jsonArray.single().jsonObject))
        assertEquals(V2Protocol.agent(json, json.parseToJsonElement(agents).jsonObject["data"]!!.jsonArray.single().jsonObject),
            V2Protocol.agent(json, read(agents, "/api/agent").jsonObject["data"]!!.jsonArray.single().jsonObject))
    }

    @Test
    fun genericEndpointsPreserveNumbersNullsArraysAndEscapedText() {
        val raw = """{"data":{"text":"braces {} [] and quote \" and slash \\","number":1.234e-7,
            "negative":-12,"boolean":true,"null":null,"nested":[1,{"x":"y"}]}}"""
        assertEquals(json.parseToJsonElement(raw), read(raw, "/api/session/s/message"))
        val statuses = """{"data":{"s":{"type":"running"}}}"""
        assertEquals(json.parseToJsonElement(statuses), read(statuses, "/api/session/active"))
        assertTrue(isProjectedV2Endpoint("/api/model/default"))
        assertFalse(isProjectedV2Endpoint("/api/session/active"))
        assertTrue(isProjectedV2Endpoint("/api/provider/p"))
    }

    @Test
    fun defaultModelKeepsSelectionReferenceAndAcceptsNull() {
        val value = read("""{"data":{"id":"m","providerID":"p","unused":{"large":[]}}}""", "/api/model/default")
        assertEquals(json.parseToJsonElement("""{"data":{"id":"m","providerID":"p"}}"""), value)
        assertEquals(json.parseToJsonElement("""{"data":null}"""), read("""{"data":null}""", "/api/model/default"))
    }

    @Test
    fun retainedNodesAndTextAreBounded() {
        expectLimit { read("[1,2,3]", "/api/command", V2ResponseLimits(maxNodes = 3)) }
        expectLimit { read("""{"text":"123456"}""", "/api/command", V2ResponseLimits(maxRetainedChars = 9)) }
    }

    @Test
    fun depthAndLiteralLimitsAlsoApplyToIgnoredBranchesBeforeAllocation() {
        expectLimit { read("""{"data":[],"ignored":[[[[0]]]]}""", "/api/model", V2ResponseLimits(maxDepth = 4)) }
        val literal = "\"" + "x".repeat(128) + "\""
        expectLimit { read("""{"data":[],"ignored":""" + literal + "}", "/api/model", V2ResponseLimits(maxLiteralChars = 64)) }
        expectLimit { read("""{"data":[{"id":""" + literal + "}]}", "/api/model", V2ResponseLimits(maxLiteralChars = 64)) }
    }

    @Test
    fun malformedResponsesAreRejectedWithoutIncludingServerText() {
        try {
            read("""{"PLACEHOLDER_ONLY": unquoted}""", "/api/command")
            fail("Expected malformed JSON rejection")
        } catch (error: IOException) {
            assertEquals("Invalid OpenCode v2 JSON response", error.message)
            assertNull(error.cause)
        }
        try {
            read("{} {}", "/api/command")
            fail("Expected trailing document rejection")
        } catch (error: IOException) {
            assertEquals("Invalid OpenCode v2 JSON response", error.message)
        }
    }

    private fun expectLimit(action: () -> Unit) {
        try {
            action()
            fail("Expected bounded response rejection")
        } catch (error: V2ResponseLimitException) {
            assertEquals("OpenCode v2 response exceeds safe parsing limits", error.message)
            assertNull(error.cause)
        }
    }
}
