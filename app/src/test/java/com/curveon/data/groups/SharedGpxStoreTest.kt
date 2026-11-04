package com.curveon.data.groups

import com.curveon.groups.GpxShare
import com.curveon.groups.SharedGpx
import com.curveon.groups.SharedGpxTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random

@RunWith(RobolectricTestRunner::class)
class SharedGpxStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun track(name: String, count: Int = 2, seed: Long = 1): SharedGpxTrack {
        val r = Random(seed)
        var lat = 59.0; var lon = 5.0
        val points = (0 until count).joinToString(";") {
            lat += r.nextGaussian() * 0.0003; lon += r.nextGaussian() * 0.0005
            String.format(java.util.Locale.ROOT, "%.6f,%.6f,%d", lat, lon, it)
        }
        return SharedGpxTrack(name, "", 10f, 0, 0, count, points, "", false, "TET", true, 0, true, false)
    }

    private val small = SharedGpx("TET Sør", true, listOf(track("S-01"), track("S-02")))
    private val big = SharedGpx("Big", true, (1..6).map { track("T$it", 30_000, it.toLong()) })

    private fun docs(s: SharedGpx, id: String = "share1") = GpxShare.pack(s, id)!!.map { it.doc }

    @Test fun aWholeShareIsOfferedFromWhoAndWhere() {
        val store = SharedGpxStore(folder.root)
        store.received(docs(small).single(), "Kari", "Tur i helga")
        val o = store.offers.value.single()
        assertEquals("TET Sør", o.name)
        assertTrue(o.isGroup)
        assertEquals(2, o.tracks)
        assertEquals("Kari", o.fromNickname)
        assertEquals("Tur i helga", o.groupName)
        assertEquals(small, store.read(o.shareId))
    }

    @Test fun nothingIsOfferedUntilAllPartsAreHere() {
        val store = SharedGpxStore(folder.root)
        val parts = docs(big)
        assertTrue(parts.size > 1)
        parts.drop(1).forEach { store.received(it, "Kari", null) }
        assertTrue(store.offers.value.isEmpty())
        store.received(parts[0], "Kari", null)
        assertEquals(1, store.offers.value.size)
        assertEquals(big, store.read("share1"))
    }

    @Test fun partsThatComeAgainMakeOneOffer() {
        val store = SharedGpxStore(folder.root)
        repeat(3) { store.received(docs(small).single(), "Kari", null) }
        assertEquals(1, store.offers.value.size)
    }

    @Test fun whatIsAnsweredIsNotOfferedAgainEvenAfterARestart() {
        val store = SharedGpxStore(folder.root)
        store.received(docs(small).single(), "Kari", null)
        store.answered("share1")
        assertTrue(store.offers.value.isEmpty())
        store.received(docs(small).single(), "Kari", null) // the relay hands it over again
        assertTrue(store.offers.value.isEmpty())
        val later = SharedGpxStore(folder.root)
        later.received(docs(small).single(), "Kari", null)
        assertTrue(later.offers.value.isEmpty())
        assertNull(later.read("share1"))
    }

    @Test fun anUnansweredOfferIsStillThereAfterARestart() {
        SharedGpxStore(folder.root).received(docs(small).single(), "Kari", "Tur")
        val later = SharedGpxStore(folder.root)
        assertEquals("Kari", later.offers.value.single().fromNickname)
        assertEquals(small, later.read("share1"))
    }

    @Test fun partsOfAShareThatNeverGotWholeGoInTheEnd() {
        var time = 1_000L
        val parts = docs(big)
        SharedGpxStore(folder.root) { time }.received(parts[0], "Kari", null)
        val shareDir = java.io.File(folder.root, "share1")
        assertTrue(shareDir.exists())
        shareDir.setLastModified(1_000L)
        time = 1_000L + 32L * 24 * 3600_000
        SharedGpxStore(folder.root) { time }
        assertFalse(shareDir.exists())
    }

    @Test fun whatIsNotAShareIsIgnored() {
        val store = SharedGpxStore(folder.root)
        store.received("{\"name\":\"a trip\"}", "Kari", null)
        assertTrue(store.offers.value.isEmpty())
        assertEquals(0, folder.root.listFiles { f -> f.isDirectory }!!.size)
    }

    @Test fun aNameIsTakenWhateverTheCapitals() {
        assertTrue(ShareNames.taken(" tet sør ", listOf("TET Sør")))
        assertFalse(ShareNames.taken("TET Nord", listOf("TET Sør")))
    }

    @Test fun aFreeNameIsTheFirstNotInUse() {
        assertEquals("TET", ShareNames.free("TET", listOf("Annet")))
        assertEquals("TET (2)", ShareNames.free("TET", listOf("tet")))
        assertEquals("TET (4)", ShareNames.free("TET", listOf("TET", "TET (2)", "tet (3)")))
    }

    @Test fun aSharedThingNeedsAFreeNameToBeSaved() {
        assertFalse(ShareNames.usable("  ", emptyList()))
        assertFalse(ShareNames.usable("TET", listOf("tet")))
        assertTrue(ShareNames.usable("TET (2)", listOf("tet")))
    }
}
