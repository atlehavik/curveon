package com.curveon.mapstyle

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MapStyleTest {
  private val styles = File(System.getProperty("curveon.root") ?: "..", "app/src/main/assets/map/styles")

  private fun build(flavor: String, maps: List<String>, road: String = MapStyle.DAY_ROAD, path: String = MapStyle.DAY_PATH) =
    JSONObject(MapStyle.build(flavor, File(styles, "$flavor.json").readText(), File(styles, "online-$flavor.json").readText(), maps, road, path))

  private fun ids(style: JSONObject) = (0 until style.getJSONArray("layers").length()).map { style.getJSONArray("layers").getJSONObject(it).getString("id") }

  @Test fun `online map under the downloaded ones, place names on top`() {
    val s = build("light", listOf("pmtiles://a", "pmtiles://b"))
    val ids = ids(s)
    assertEquals("background", ids.first())
    assertTrue(ids[1].startsWith("online-"))
    assertTrue(s.getJSONObject("sources").has("protomaps") && s.getJSONObject("sources").has("protomaps1") && s.getJSONObject("sources").has("online"))
    // The second map's copies keep the order and get their own ids.
    assertTrue(ids.contains("water~1"))
    // Every line comes before every place name (names stay on top).
    val lastLine = ids.indexOfLast { id -> s.getJSONArray("layers").getJSONObject(ids.indexOf(id)).optString("type") == "line" }
    val firstName = ids.indexOfFirst { it.startsWith("places_") }
    assertTrue("$lastLine < $firstName", lastLine < firstName)
  }

  @Test fun `with no downloaded map the online map shows, and roads take the asked grey`() {
    val s = build("light", emptyList(), road = "#123456")
    val layers = s.getJSONArray("layers")
    val online = (0 until layers.length()).map { layers.getJSONObject(it) }.filter { it.getString("id").startsWith("online-") }
    assertTrue(online.isNotEmpty() && online.none { it.optJSONObject("layout")?.optString("visibility") == "none" })
    assertEquals("#123456", online.first { it.getString("id") == "online-roads_minor" }.getJSONObject("paint").getString("line-color"))
  }
}
