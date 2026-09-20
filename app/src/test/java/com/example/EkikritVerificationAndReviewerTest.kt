package com.example

import com.example.data.model.ApplicationEntity
import com.example.data.model.StudentEntity
import com.example.data.model.VerificationStatus
import com.example.data.verification.LocalDemoVerificationDataSource
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
 * Unit tests for the demo multi-source verification rail ([LocalDemoVerificationDataSource]):
 * 1. UIDAI, DigiLocker and e-District checks
 * 2. Non-blocking income variance (+11.9%) that is auto-routed to the Reviewer Desk
 * 3. The six-source fan-out used by verifyAllSources
 *
 * Reviewer resolution (approve / request clarification) touches the database and is covered by
 * EkikritFinalValidationTest, which runs it against a real in-memory Room database.
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

    private fun student(
        annualIncome: Double = 160000.0,
        aadhaarMasked: String = "XXXX-XXXX-1234",
        category: String = "ST (Scheduled Tribe)"
    ) = StudentEntity(
        id = "STU_2026_01",
        name = "Birsa Munda Tirkey",
        dob = "18-05-2004",
        institutionId = "AISHE-U-0355",
        institutionName = "National Institute of Technology, Rourkela",
        course = "B.Tech Computer Science & Engineering",
        category = category,
        pvtgCommunity = null,
        aadhaarMasked = aadhaarMasked,
        annualIncome = annualIncome,
        apaarId = "APAAR-9876-5432-1098"
    )

    @Test
    fun uidaiVerification_withValidDemographics_returnsVerifiedStatus() = runBlocking {
        val result = verificationDataSource.verifyUidaiDemographics(student())

        assertEquals("UIDAI (Aadhaar Rail)", result.sourceSystem)
        assertEquals(VerificationStatus.VERIFIED, result.status)
        assertTrue(result.retrievedValue.contains("99.1%"))
        assertTrue(result.declaredValue.contains("Birsa Munda Tirkey"))
    }

    @Test
    fun uidaiVerification_withMissingAadhaar_returnsMismatchStatus() = runBlocking {
        val result = verificationDataSource.verifyUidaiDemographics(student(aadhaarMasked = ""))

        assertEquals(VerificationStatus.MISMATCH, result.status)
        assertTrue(result.notes.contains("Aadhaar number or name is missing"))
    }

    @Test
    fun digiLockerCredentials_withStCategory_verifiesCasteCertificate() = runBlocking {
        val result = verificationDataSource.verifyDigiLockerCredentials(student())

        assertEquals("DigiLocker Wallet", result.sourceSystem)
        assertEquals(VerificationStatus.VERIFIED, result.status)
        assertTrue(result.retrievedValue.contains("Digitally Signed"))
    }

    @Test
    fun digiLockerCredentials_withoutStCategory_returnsMismatch() = runBlocking {
        val result = verificationDataSource.verifyDigiLockerCredentials(student(category = "General"))

        assertEquals(VerificationStatus.MISMATCH, result.status)
        assertTrue(result.retrievedValue.contains("No ST Certificate"))
    }

    @Test
    fun districtIncome_with11PercentVariance_flagsNonBlockingMismatchForReviewer() = runBlocking {
        // SIH26238 scenario: declared Rs 1,60,000 vs e-District registry Rs 1,79,040 (+11.9%),
        // still below the Rs 2.5L statutory cap, so it is routed to the Reviewer Desk without blocking.
        val result = verificationDataSource.verifyDistrictIncome(student(annualIncome = 160000.0))

        assertEquals("e-District Revenue Portal", result.sourceSystem)
        assertEquals(VerificationStatus.MISMATCH, result.status)
        assertTrue(result.declaredValue.contains("160000"))
        assertTrue(result.retrievedValue.contains("179040"))
        assertTrue(result.notes.contains("auto-routed to Reviewer Desk", ignoreCase = true))
    }

    @Test
    fun districtIncome_outsideDemoVarianceBand_isVerifiedWithoutReview() = runBlocking {
        val result = verificationDataSource.verifyDistrictIncome(student(annualIncome = 300000.0))

        assertEquals(VerificationStatus.VERIFIED, result.status)
        assertTrue(result.retrievedValue.contains("300000"))
    }

    @Test
    fun verifyAllSources_executesSixVerificationRailsScopedToApplication() = runBlocking {
        val app = ApplicationEntity(
            id = "APP_2026_01",
            studentId = "STU_2026_01",
            schemeId = "SCH_PMS",
            schemeCode = "POST-MATRIC-ST",
            schemeName = "Post-Matric Scholarship for ST Students",
            academicYear = "2025-26",
            currentStage = "INSTITUTE_VERIFICATION",
            statusText = "Under review",
            appliedDate = "15 Aug 2026",
            lastUpdated = "15 Aug 2026",
            hasDiscrepancy = true
        )

        val records = verificationDataSource.verifyAllSources(student(), app, "2026-09-19 10:00:00")

        assertEquals(6, records.size)
        assertEquals(1, records.count { it.sourceSystem.startsWith("UIDAI") })
        assertEquals(1, records.count { it.sourceSystem.startsWith("DigiLocker") })
        assertEquals(1, records.count { it.sourceSystem.startsWith("AISHE") })
        assertEquals(1, records.count { it.sourceSystem.startsWith("APAAR") })
        assertEquals(1, records.count { it.sourceSystem.startsWith("UGC") })
        assertEquals(1, records.count { it.sourceSystem.startsWith("e-District") })

        assertTrue(records.all { it.applicationId == "APP_2026_01" })
        assertTrue(records.all { it.schemeId == "SCH_PMS" })
        assertEquals(records.size, records.map { it.id }.toSet().size)

        // Only the income check should be flagged for the reviewer.
        assertEquals(1, records.count { it.status == VerificationStatus.MISMATCH.name })
        assertEquals(5, records.count { it.status == VerificationStatus.VERIFIED.name })
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
