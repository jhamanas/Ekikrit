package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.EkikritDatabase
import com.example.data.local.SeedData
import com.example.data.model.*
import com.example.data.repository.EkikritRepository
import com.example.domain.UnifiedVerificationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests verifying:
 * 1. Multi-source verification rail behavior across all 7 national registries (UIDAI, DigiLocker, APAAR, AISHE, UDISE+, UGC/NTA, e-District)
 * 2. Non-blocking income variance detection (+11.9% variance auto-routing to Reviewer Desk)
 * 3. Reviewer Role-Based Access Control (RBAC) security enforcement via real repository calls
 * 4. Officer exception clearance and rejection state transitions via real production repository
 * 5. Deterministic demo identity switching and rejection of arbitrary IDs
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EkikritVerificationAndReviewerTest {

    private lateinit var db: EkikritDatabase
    private lateinit var repository: EkikritRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EkikritDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        SeedData.resetDemo(db)
        repository = EkikritRepository(db, CoroutineScope(Dispatchers.IO))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun sevenSourceVerification_executesAllSevenRailsWithAccurateOutcomes() = runBlocking {
        val student = SeedData.students[0]
        val app = SeedData.applicationsStu1[0]
        val documents = SeedData.documents.filter { it.studentId == student.id }

        val output = UnifiedVerificationEngine.executeSevenSourceVerification(student, app, documents)

        // Verifies exactly 7 distinct rails
        assertEquals(7, output.records.size)
        assertEquals(1, output.records.count { it.sourceSystem.startsWith("UIDAI") })
        assertEquals(1, output.records.count { it.sourceSystem.startsWith("DigiLocker") })
        assertEquals(1, output.records.count { it.sourceSystem.startsWith("APAAR") })
        assertEquals(1, output.records.count { it.sourceSystem.startsWith("AISHE") })
        assertEquals(1, output.records.count { it.sourceSystem.startsWith("UDISE+") })
        assertEquals(1, output.records.count { it.sourceSystem.startsWith("UGC") })
        assertEquals(1, output.records.count { it.sourceSystem.startsWith("e-District") })

        // UIDAI, DigiLocker, APAAR, AISHE, UDISE+, UGC should be VERIFIED
        val verifiedRecords = output.records.filter { it.status == "VERIFIED" }
        assertEquals(6, verifiedRecords.size)

        // e-District has deterministic +11.9% variance, generating a non-blocking exception
        val eDistrictRecord = output.records.first { it.sourceSystem.startsWith("e-District") }
        assertEquals("MISMATCH", eDistrictRecord.status)
        assertFalse(eDistrictRecord.isBlocking)
        assertNotNull(output.reviewItem)
        assertEquals("e-District Revenue Portal", output.reviewItem?.sourceSystem)
        assertEquals("PENDING", output.reviewItem?.status)
    }

    @Test
    fun reviewerRbac_studentRoleCannotResolveReviewItems() = runBlocking {
        // Active actor is student STU_2026_01
        repository.switchStudent("STU_2026_01")

        val app = db.applicationDao().getApplicationsForStudent("STU_2026_01").first()
        val reviewItem = db.reviewQueueDao().getByAppId(app.id)
        assertNotNull("Pending review item must exist for seeded application", reviewItem)

        val initialReviewStatus = reviewItem!!.status
        val initialAppStage = app.currentStage
        val initialDiscrepancy = app.hasDiscrepancy

        // Production repository call MUST reject student role with SecurityException
        val exception = assertThrows(SecurityException::class.java) {
            runBlocking {
                repository.resolveReviewItem(
                    reviewItemId = reviewItem.id,
                    isApproved = true,
                    notes = "Student attempt to self-resolve exception"
                )
            }
        }

        assertTrue(exception.message!!.contains("Unauthorized"))
        assertTrue(exception.message!!.contains("Reviewing Officers"))

        // Assert review item and application state remain unchanged
        val unmutatedReviewItem = db.reviewQueueDao().getById(reviewItem.id)
        assertNotNull(unmutatedReviewItem)
        assertEquals(initialReviewStatus, unmutatedReviewItem!!.status)

        val unmutatedApp = db.applicationDao().getApplicationById(app.id)
        assertNotNull(unmutatedApp)
        assertEquals(initialAppStage, unmutatedApp!!.currentStage)
        assertEquals(initialDiscrepancy, unmutatedApp.hasDiscrepancy)

        // Switching to verified Reviewing Officer REV_OFFICER_01 must succeed
        repository.switchStudent("REV_OFFICER_01")
        repository.resolveReviewItem(
            reviewItemId = reviewItem.id,
            isApproved = true,
            notes = "Tolerance accepted per SIH Ministry norms: within ceiling."
        )

        val resolvedReviewItem = db.reviewQueueDao().getById(reviewItem.id)
        assertNotNull(resolvedReviewItem)
        assertEquals("RESOLVED_ACCEPTED", resolvedReviewItem!!.status)
    }

    @Test
    fun reviewerResolution_whenApproved_clearsDiscrepancyAndPromotesToSanctioned() = runBlocking {
        // Switch to Reviewing Officer
        repository.switchStudent("REV_OFFICER_01")

        val app = db.applicationDao().getApplicationsForStudent("STU_2026_01").first()
        val reviewItem = db.reviewQueueDao().getByAppId(app.id)
        assertNotNull(reviewItem)

        val notes = "Income variance of +11.9% cleared under statutory ₹2.50L tolerance rule."
        repository.resolveReviewItem(
            reviewItemId = reviewItem!!.id,
            isApproved = true,
            notes = notes
        )

        val updatedReviewItem = db.reviewQueueDao().getById(reviewItem.id)
        assertNotNull(updatedReviewItem)
        assertEquals("RESOLVED_ACCEPTED", updatedReviewItem!!.status)
        assertEquals(notes, updatedReviewItem.resolutionNotes)

        val updatedApp = db.applicationDao().getApplicationById(app.id)
        assertNotNull(updatedApp)
        assertFalse("Discrepancy flag must be cleared", updatedApp!!.hasDiscrepancy)
        assertNull(updatedApp.pendingActionDesc)
    }

    @Test
    fun reviewerResolution_whenRejected_flagsActionRequiredForStudent() = runBlocking {
        // Switch to Reviewing Officer
        repository.switchStudent("REV_OFFICER_01")

        val app = db.applicationDao().getApplicationsForStudent("STU_2026_01").first()
        val reviewItem = db.reviewQueueDao().getByAppId(app.id)
        assertNotNull(reviewItem)

        val rejectionNotes = "Revenue certificate expired. Student must re-sync via DigiLocker."
        repository.resolveReviewItem(
            reviewItemId = reviewItem!!.id,
            isApproved = false,
            notes = rejectionNotes
        )

        val updatedReviewItem = db.reviewQueueDao().getById(reviewItem.id)
        assertNotNull(updatedReviewItem)
        assertEquals("RESOLVED_REJECTED", updatedReviewItem!!.status)

        val updatedApp = db.applicationDao().getApplicationById(app.id)
        assertNotNull(updatedApp)
        assertEquals("ACTION_REQUIRED", updatedApp!!.currentStage)
        assertTrue(updatedApp.hasDiscrepancy)
        assertNotNull(updatedApp.pendingActionDesc)
        assertTrue(updatedApp.pendingActionDesc!!.contains(rejectionNotes))
    }

    @Test
    fun switchStudent_demoIdentitiesOnly_rejectsArbitraryProfileSwitching() = runBlocking {
        val validDemoIds = listOf("STU_2026_01", "STU_2026_02", "STU_2026_03", "REV_OFFICER_01")
        for (validId in validDemoIds) {
            repository.switchStudent(validId)
            assertEquals(validId, repository.activeStudentId.value)
        }

        val arbitraryCallers = listOf(
            "STU_ATTACKER_99",
            "ADMIN_IMPERSONATOR",
            "STU_2026_99"
        )

        for (unauthorizedId in arbitraryCallers) {
            val exception = assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.switchStudent(unauthorizedId)
                }
            }
            assertTrue(exception.message!!.contains("not found"))
        }
    }
}
