package com.curveon.webplanner

import com.curveon.mapstyle.MapStyle
import java.io.File

/**
 * Writes the web planner's map styles, built the way the app builds its own (MapStyle) in normal contrast:
 * `light.json` and `dark.json` from the app's styles in [args] 0 (app/src/main/assets/map/styles) into [args] 1.
 * The Norway map's url, glyphs and sprite are set by the page (it knows where it is served from); the online map lies
 * under it, shown where the Norway map does not cover the view. Run by tools/webplanner_assemble.sh.
 */
fun main(args: Array<String>) {
  val from = File(args[0])
  val to = File(args[1]).apply { mkdirs() }
  for ((flavor, road, path) in listOf(
    Triple("light", MapStyle.DAY_ROAD, MapStyle.DAY_PATH),
    Triple("dark", MapStyle.NIGHT_ROAD, MapStyle.NIGHT_PATH),
  )) {
    val style = MapStyle.build(flavor, File(from, "$flavor.json").readText(), File(from, "online-$flavor.json").readText(),
      listOf("pmtiles://norway.pmtiles"), road, path)
    File(to, "$flavor.json").writeText(style)
  }
}
