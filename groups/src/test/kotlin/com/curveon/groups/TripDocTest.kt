package com.curveon.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDocTest {
  private val a = TripStop("A", 59.1, 5.1)
  private val b = TripStop("B", 59.2, 5.2)
  private val c = TripStop("C", 59.3, 5.3)
  private val d = TripStop("Kafé", 59.9, 5.9)
  private val dest = TripStop("Mål", 60.0, 6.0)

  @Test fun theDocumentSurvivesJson() {
    val doc = TripDoc("Dagstur", dest, listOf(a, TripStop("Via", 59.15, 5.15, via = true)), """{"style":"CURVY","ferries":true}""")
    val back = TripDoc.fromJson(doc.toJson())!!
    assertEquals("Dagstur", back.name)
    assertEquals(dest, back.destination)
    assertEquals(doc.stops, back.stops)
    assertTrue(back.stops[1].via)
    assertTrue(back.options.contains("CURVY"))
    assertNull(back.line)
    assertEquals("59.1,5.1;59.2,5.2", TripDoc.fromJson(TripDoc("T", dest, emptyList(), line = "59.1,5.1;59.2,5.2").toJson())!!.line)
    assertNull(TripDoc.fromJson("junk"))
    assertNull(TripDoc.fromJson("""{"v":2,"destination":{"lat":1,"lon":2}}"""))
  }

  @Test fun theHigherRevisionWinsAndATieGoesToTheLargerRiderId() {
    assertTrue(TripDoc.isNewer(3, "x", 2, "z"))
    assertFalse(TripDoc.isNewer(2, "z", 3, "a"))
    assertTrue(TripDoc.isNewer(5, "kari", 5, "ola") == ("kari" > "ola"))
    // two devices see the same two revisions in either order and pick the same one
    val first = TripDoc.isNewer(5, "kari", 5, "ola")
    val second = TripDoc.isNewer(5, "ola", 5, "kari")
    assertTrue(first != second)
  }

  @Test fun whatIRideIsTheStopsIHaveNotPassed() {
    val doc = TripDoc("T", dest, listOf(a, b, c))
    assertEquals(listOf(a, b, c), doc.remaining(emptySet()))
    assertEquals(listOf(b, c), doc.remaining(setOf(a.key)))
    assertTrue(doc.matches(setOf(a.key), listOf(b, c), dest))
    assertFalse(doc.matches(setOf(a.key), listOf(b, c), TripStop("Annet", 61.0, 6.0)))
    // a route that is the document with a stop added is not the document
    assertFalse(doc.matches(emptySet(), listOf(a, b, c, d), dest))
  }

  @Test fun passingAStopMyselfIsNotAnEdit() {
    val doc = TripDoc("T", dest, listOf(a, b, c))
    // I have passed A: my route has B and C left, and that is still exactly the document
    assertTrue(doc.matches(setOf(a.key), listOf(b, c), dest))
  }

  @Test fun anEditKeepsTheStopsIHavePassedForTheOthers() {
    // Ola has passed A. Kari has not. Ola adds a coffee stop after B.
    val doc = TripDoc("T", dest, listOf(a, b, c))
    val olaPassed = setOf(a.key)
    val edited = doc.edited(olaPassed, listOf(b, d, c), dest)
    assertEquals(listOf(a, b, d, c), edited.stops)
    // Kari, who has passed nothing, rides A, B, the coffee stop and C: the same stops, the new one included
    assertEquals(listOf(a, b, d, c), edited.remaining(emptySet()))
    // and Ola still rides B, the coffee stop and C
    assertEquals(listOf(b, d, c), edited.remaining(olaPassed))
  }

  @Test fun theScenarioOfTheRiders() {
    // Ola shares a trip; Kari adds a stop while riding; Ola and Jens get it.
    val shared = TripDoc("Dagstur", dest, listOf(b))
    val kariEdit = shared.edited(emptySet(), listOf(b, d), dest)
    for (rider in listOf("ola", "jens")) assertEquals("$rider rides to the new stop too", listOf(b, d), kariEdit.remaining(emptySet()))
    // someone then takes the stop away again
    val removed = kariEdit.edited(emptySet(), listOf(b), dest)
    assertEquals(listOf(b), removed.remaining(emptySet()))
  }

  @Test fun theDestinationCanBeChangedToo() {
    val doc = TripDoc("T", dest, listOf(a))
    val other = TripStop("Nytt mål", 61.0, 7.0)
    val edited = doc.edited(emptySet(), listOf(a), other)
    assertEquals(other, edited.destination)
    assertTrue(edited.matches(emptySet(), listOf(a), other))
    assertFalse(doc.matches(emptySet(), listOf(a), other))
  }
}
