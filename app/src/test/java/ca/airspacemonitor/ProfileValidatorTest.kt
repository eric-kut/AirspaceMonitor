package ca.airspacemonitor

import ca.airspacemonitor.data.AltitudeUnit
import ca.airspacemonitor.domain.CeilingRef
import ca.airspacemonitor.domain.GeoPoint
import ca.airspacemonitor.domain.GeofenceMode
import ca.airspacemonitor.domain.WatchMode
import ca.airspacemonitor.ui.profiles.ProfileDraft
import ca.airspacemonitor.ui.profiles.SaveAttempt
import ca.airspacemonitor.ui.profiles.parseDecimal
import ca.airspacemonitor.ui.profiles.suggestedProfileName
import ca.airspacemonitor.ui.profiles.validateDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileValidatorTest {

    /** Valid warning-only draft (feet). */
    private fun draft(
        name: String = "My zone",
        ceilingText: String = "400",
        altitudeUnit: AltitudeUnit = AltitudeUnit.FT,
        ceilingRef: CeilingRef = CeilingRef.ASL,
        terrainText: String = "",
        watchEnabled: Boolean = false,
        watchMode: WatchMode = WatchMode.OFFSET,
        watchCeilingText: String = "1500",
        watchCeilingRef: CeilingRef = CeilingRef.ASL,
        watchCenterLatText: String = "45.4215",
        watchCenterLonText: String = "-75.6972",
        watchOffsetVText: String = "300",
    ) = ProfileDraft(
        name = name,
        geofenceMode = GeofenceMode.FIXED_CIRCLE,
        centerLatText = "45.4215",
        centerLonText = "-75.6972",
        radiusKm = 5.0,
        ceilingText = ceilingText,
        altitudeUnit = altitudeUnit,
        ceilingRef = ceilingRef,
        terrainText = terrainText,
        watchEnabled = watchEnabled,
        watchMode = watchMode,
        watchCeilingText = watchCeilingText,
        watchCeilingRef = watchCeilingRef,
        watchCenterLatText = watchCenterLatText,
        watchCenterLonText = watchCenterLonText,
        watchOffsetVText = watchOffsetVText,
    )

    // ---- parseDecimal ------------------------------------------------------

    @Test
    fun `parseDecimal accepts dot and comma decimals`() {
        assertEquals(45.4215, parseDecimal("45.4215")!!, 1e-9)
        assertEquals(45.4215, parseDecimal("45,4215")!!, 1e-9)
        assertEquals(45.4215, parseDecimal(" 45,4215 ")!!, 1e-9)
        assertEquals(null, parseDecimal("abc"))
        assertEquals(null, parseDecimal(""))
        assertEquals(null, parseDecimal("1,234.5"))
    }

    // ---- The stuck-save scenarios ------------------------------------------

    @Test
    fun `blank name fails with the name dialog`() {
        val failure = validateDraft(draft(name = "   "), emptySet()) as SaveAttempt.Failure
        assertTrue(failure.needsNameDialog)
        assertTrue(failure.suggestedName!!.startsWith("Profile"))
    }

    @Test
    fun `fixed-circle watch with invalid center text fails with a message`() {
        // A blank watch center falls back to the warning center (valid here);
        // only garbage watch-center text that cannot fall back hits this branch.
        val draft = draft(
            watchEnabled = true,
            watchMode = WatchMode.FIXED_CIRCLE,
            watchCenterLatText = "not-a-number",
            watchCenterLonText = "-75.6972",
        )
        val failure = validateDraft(draft, emptySet()) as SaveAttempt.Failure
        assertTrue(!failure.needsNameDialog)
        assertTrue(failure.message.contains("watch center"))
    }

    @Test
    fun `blank warning center fails before the watch layer is checked`() {
        val draft = draft(watchEnabled = true, watchMode = WatchMode.FIXED_CIRCLE)
            .copy(centerLatText = "", centerLonText = "")
        val failure = validateDraft(draft, emptySet()) as SaveAttempt.Failure
        assertTrue(!failure.needsNameDialog)
        // Validation order: the mandatory warning zone is checked first.
        assertTrue(failure.message.contains("warning zone center"))
    }

    @Test
    fun `cleared warning ceiling fails with a message`() {
        val failure = validateDraft(draft(ceilingText = ""), emptySet()) as SaveAttempt.Failure
        assertTrue(!failure.needsNameDialog)
        assertTrue(failure.message.contains("warning ceiling"))
    }

    @Test
    fun `AGL without terrain fails with a message`() {
        val failure = validateDraft(draft(ceilingRef = CeilingRef.AGL, terrainText = ""), emptySet()) as SaveAttempt.Failure
        assertTrue(!failure.needsNameDialog)
        assertTrue(failure.message.contains("terrain"))
    }

    @Test
    fun `watch AGL without terrain fails with a message`() {
        val failure = validateDraft(
            draft(
                ceilingRef = CeilingRef.ASL,
                watchEnabled = true,
                watchMode = WatchMode.FOLLOW_PHONE,
                watchCeilingRef = CeilingRef.AGL,
                terrainText = "",
            ),
            emptySet(),
        ) as SaveAttempt.Failure
        assertTrue(failure.message.contains("terrain"))
    }

    @Test
    fun `polygon with fewer than three vertices fails with a message`() {
        val draft = draft().copy(
            geofenceMode = GeofenceMode.POLYGON,
            polygon = listOf(GeoPoint(45.0, -75.0), GeoPoint(45.1, -75.1)),
        )
        val failure = validateDraft(draft, emptySet()) as SaveAttempt.Failure
        assertTrue(failure.message.contains("3 vertices"))
    }

    @Test
    fun `watch offset out of bounds fails with a message`() {
        val failure = validateDraft(draft(watchEnabled = true, watchOffsetVText = "-5"), emptySet()) as SaveAttempt.Failure
        assertTrue(failure.message.contains("vertical offset"))
    }

    @Test
    fun `metric input with comma separator converts to feet`() {
        val ok = validateDraft(
            draft(
                watchEnabled = true,
                watchMode = WatchMode.OFFSET,
                watchOffsetVText = "91,44",
                altitudeUnit = AltitudeUnit.M,
            ),
            emptySet(),
        )
        // 91.44 m = 300 ft: the meter draft converts to an ft WatchVolume.
        val success = ok as SaveAttempt.Success
        assertEquals(300.0, success.profile.watch!!.offsetV, 1e-6)
    }

    @Test
    fun `metric draft converts meters to feet on save`() {
        val success = validateDraft(
            draft(ceilingText = "122", altitudeUnit = AltitudeUnit.M),
            emptySet(),
        ) as SaveAttempt.Success
        assertEquals(400.26, success.profile.ceilingValue, 0.5)
    }

    @Test
    fun `valid warning-only draft builds a full profile`() {
        val success = validateDraft(draft(), emptySet()) as SaveAttempt.Success
        assertEquals("My zone", success.profile.name)
        assertEquals(400.0, success.profile.ceilingValue, 1e-9)
        assertTrue("watch disabled", success.profile.watch == null)
    }

    @Test
    fun `valid draft with offset watch builds watch volume in feet`() {
        val success = validateDraft(draft(watchEnabled = true), emptySet()) as SaveAttempt.Success
        val watch = success.profile.watch!!
        assertEquals(WatchMode.OFFSET, watch.mode)
        assertEquals(300.0, watch.offsetV, 1e-9)
    }

    // ---- Name suggestion --------------------------------------------------

    @Test
    fun `suggested name picks the first free Profile N`() {
        assertEquals("Profile 1", suggestedProfileName(emptySet()))
        assertEquals("Profile 2", suggestedProfileName(setOf("Profile 1")))
        assertEquals("Profile 3", suggestedProfileName(setOf("Profile 1", "Profile 2", "Other")))
        assertEquals("Profile 3", suggestedProfileName(setOf("Profile 1", "Profile 2", "Profile 4")))
    }
}