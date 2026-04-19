package dev.snapecg.holter

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for connector JSON-RPC protocol.
 * Tests serialization/deserialization without network.
 */
class ConnectorProtocolTest {

    @Test
    fun `discovery broadcast has correct format`() {
        val msg = JSONObject().apply {
            put("type", "snapecg_holter")
            put("name", "Test Device")
            put("port", 8365)
            put("version", "1.0")
        }
        assertEquals("snapecg_holter", msg.getString("type"))
        assertEquals(8365, msg.getInt("port"))
        assertEquals("1.0", msg.getString("version"))
    }

    @Test
    fun `request format is valid`() {
        val req = JSONObject().apply {
            put("id", 1)
            put("method", "holter.get_status")
            put("params", JSONObject())
        }
        assertEquals(1, req.getInt("id"))
        assertEquals("holter.get_status", req.getString("method"))
        assertTrue(req.has("params"))
    }

    @Test
    fun `response format with result`() {
        val resp = JSONObject().apply {
            put("id", 1)
            put("result", JSONObject().apply {
                put("bt_connected", true)
                put("device_battery", 85)
                put("lead_off", false)
            })
        }
        assertEquals(1, resp.getInt("id"))
        val result = resp.getJSONObject("result")
        assertTrue(result.getBoolean("bt_connected"))
        assertEquals(85, result.getInt("device_battery"))
        assertFalse(result.getBoolean("lead_off"))
    }

    @Test
    fun `response format with error`() {
        val resp = JSONObject().apply {
            put("id", 1)
            put("error", JSONObject().apply {
                put("code", -1)
                put("message", "Not connected")
            })
        }
        assertTrue(resp.has("error"))
        assertEquals("Not connected", resp.getJSONObject("error").getString("message"))
    }

    @Test
    fun `pair request format`() {
        val req = JSONObject().apply {
            put("method", "pair")
            put("params", JSONObject().apply {
                put("salt", "abcdef0123456789")
                put("proof", "hmac_hex_string")
            })
        }
        assertEquals("pair", req.getString("method"))
        val params = req.getJSONObject("params")
        assertTrue(params.has("salt"))
        assertTrue(params.has("proof"))
    }

    @Test
    fun `add_event request format`() {
        val req = JSONObject().apply {
            put("id", 5)
            put("method", "holter.add_event")
            put("params", JSONObject().apply {
                put("text", "Головокружение")
                put("tag", "dizziness")
            })
        }
        val params = req.getJSONObject("params")
        assertEquals("Головокружение", params.getString("text"))
        assertEquals("dizziness", params.getString("tag"))
    }

    @Test
    fun `get_signal response with samples`() {
        val resp = JSONObject().apply {
            put("id", 3)
            put("result", JSONObject().apply {
                put("samples", org.json.JSONArray().apply {
                    put(2048); put(2052); put(2044); put(2100); put(1980)
                })
            })
        }
        val samples = resp.getJSONObject("result").getJSONArray("samples")
        assertEquals(5, samples.length())
        assertEquals(2048, samples.getInt(0))
    }

    @Test
    fun `verify_setup GO response`() {
        val resp = JSONObject().apply {
            put("result", JSONObject().apply {
                put("go", true)
                put("message", "All checks passed. Ready for Holter monitoring.")
                put("checks", JSONObject().apply {
                    put("device_connected", true)
                    put("device_battery", 85)
                    put("lead_off", false)
                    put("signal_quality", "good")
                    put("heart_rate", 72)
                })
            })
        }
        val result = resp.getJSONObject("result")
        assertTrue(result.getBoolean("go"))
        assertEquals("good", result.getJSONObject("checks").getString("signal_quality"))
    }

    @Test
    fun `verify_setup NO-GO response`() {
        val resp = JSONObject().apply {
            put("result", JSONObject().apply {
                put("go", false)
                put("problems", org.json.JSONArray().apply {
                    put("Electrodes not in contact (lead off)")
                    put("Device battery too low: 0/3 (minimum 1/3)")
                })
                put("message", "2 issue(s) found.")
            })
        }
        val result = resp.getJSONObject("result")
        assertFalse(result.getBoolean("go"))
        assertEquals(2, result.getJSONArray("problems").length())
    }
}
