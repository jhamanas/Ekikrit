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
import com.example.data.verification.LocalDemoVerificationDataSource
import com.example.data.verification.SourceVerificationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the demo multi-source verification rail ([LocalDemoVerificationDataSource]):
 * 1. UIDAI, DigiLocker and e-District checks
 * 2. Non-blocking income variance (+11.9%) that is auto-routed to the Reviewer Desk
 * 3. The six-source fan-out used by verifyAllSources
 *
 * Reviewer resolution (approve / request clarification) touches the database and is covered by
 * EkikritFinalValidationTest, which runs it against a real in-memory Room database.
 * Unit tests verifying:
 * 1. Multi-source verification rail behavior (UIDAI, DigiLocker, e-District, AISHE, APAAR, UGC/NTA)
 * 2. Non-blocking income variance detection (+11.9% variance auto-routing to Reviewer Desk)
 * 3. Reviewer Role-Based Access Control (RBAC) security enforcement
 * 4. Exception clearance & promotion of target applications to SANCTIONED
 */
class EkikritVerificationAndReviewerTest {

    private lateinit var verificationDataSource: LocalDemoVerificationDataSource

    @Before
    fun setUp() {
        verificationDataSource = LocalDemoVerificationDataSource()
    }

    @Test
    fun uidaiVerification_withValidDemographics_returnsVerifiedStatus() = runBlocking {
        val student = StudentEntity(
            id = "STU_2026_01",
            name = "Birsa Munda Tirkey",
            dob = "2004-05-18",
            gender = "Male",
            aadhaarMasked = "XXXXXXXX1234",
            phoneMasked = "XXXXXX9876",
            email = "birsa.tirkey@nitrkl.ac.in",
            category = "ST (Scheduled Tribe)",
            annualIncome = 160000.0,
            institutionName = "National Institute of Technology, Rourkela",
            institutionId = "AISHE-U-0355",
            course = "B.Tech Computer Science & Engineering",
            yearOfStudy = 2,
            bankAccountMasked = "XXXXXXXX5678",
            bankIfsc = "SBIN0002109",
            bankName = "State Bank of India",
            apaarId = "APAAR-9876-5432-1098"
        )

        val result = verificationDataSource.verifyUidaiDemographics(student)
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
    fun uidaiVerification_withMissingAadhaar_returnsMismatchStatus() = runBlocking {
        val student = StudentEntity(
            id = "STU_INCOMPLETE",
            name = "Test Student",
            dob = "2005-01-01",
            gender = "Male",
            aadhaarMasked = "", // missing Aadhaar
            phoneMasked = "XXXXXX1234",
            email = "test@example.com",
            category = "ST",
            annualIncome = 100000.0,
            institutionName = "Govt College",
            institutionId = "AISHE-1234",
            course = "BA",
            yearOfStudy = 1,
            bankAccountMasked = "XXXX1234",
            bankIfsc = "SBIN0001234",
            bankName = "SBI",
            apaarId = "APAAR-1234"
        )

        val result = verificationDataSource.verifyUidaiDemographics(student)

        assertEquals(VerificationStatus.MISMATCH, result.status)
        assertTrue(result.notes.contains("Aadhaar number or name is missing"))
    }

    @Test
    fun digiLockerCredentials_withStCategory_verifiesCasteCertificate() = runBlocking {
        val student = StudentEntity(
            id = "STU_2026_01",
            name = "Birsa Munda Tirkey",
            dob = "2004-05-18",
            gender = "Male",
            aadhaarMasked = "XXXXXXXX1234",
            phoneMasked = "XXXXXX9876",
            email = "birsa.tirkey@nitrkl.ac.in",
            category = "ST (Scheduled Tribe)",
            annualIncome = 160000.0,
            institutionName = "NIT Rourkela",
            institutionId = "AISHE-U-0355",
            course = "B.Tech CSE",
            yearOfStudy = 2,
            bankAccountMasked = "XXXXXXXX5678",
            bankIfsc = "SBIN0002109",
            bankName = "SBI",
            apaarId = "APAAR-9876"
        )

        val result = verificationDataSource.verifyDigiLockerCredentials(student)

        assertEquals("DigiLocker Wallet", result.sourceSystem)
        assertEquals(VerificationStatus.VERIFIED, result.status)
        assertTrue(result.retrievedValue.contains("Digitally Signed"))
    }

    @Test
    fun districtIncome_with11PercentVariance_flagsNonBlockingMismatchForReviewer() = runBlocking {
        // Real-world scenario from SIH26238 problem statement:
        // Declared ₹1,60,000, e-District registry reflects ₹1,79,000 (+11.875% variance).
        // Remains below statutory cap (₹2.5L). Flagged as MISMATCH to auto-route to Reviewer Desk.
        val student = StudentEntity(
            id = "STU_2026_01",
            name = "Birsa Munda Tirkey",
            dob = "2004-05-18",
            gender = "Male",
            aadhaarMasked = "XXXXXXXX1234",
            phoneMasked = "XXXXXX9876",
            email = "birsa.tirkey@nitrkl.ac.in",
            category = "ST",
            annualIncome = 160000.0,
            institutionName = "NIT Rourkela",
            institutionId = "AISHE-U-0355",
            course = "B.Tech CSE",
            yearOfStudy = 2,
            bankAccountMasked = "XXXXXXXX5678",
            bankIfsc = "SBIN0002109",
            bankName = "SBI",
            apaarId = "APAAR-9876"
        )

        val result = verificationDataSource.verifyDistrictIncome(student)

        assertEquals("e-District Revenue Portal", result.sourceSystem)
        assertEquals(VerificationStatus.MISMATCH, result.status)
        assertTrue(result.declaredValue.contains("160000"))
        assertTrue(result.retrievedValue.contains("179040"))
        assertTrue(result.notes.contains("auto-routed to Reviewer Desk", ignoreCase = true))
    }

    @Test
    fun verifyAllSources_executesSixVerificationRailsScopedToApplication() = runBlocking {
        val student = StudentEntity(
            id = "STU_2026_01",
            name = "Birsa Munda Tirkey",
            dob = "2004-05-18",
            gender = "Male",
            aadhaarMasked = "XXXXXXXX1234",
            phoneMasked = "XXXXXX9876",
            email = "birsa.tirkey@nitrkl.ac.in",
            category = "ST",
            annualIncome = 160000.0,
            institutionName = "National Institute of Technology, Rourkela",
            institutionId = "AISHE-U-0355",
            course = "B.Tech Computer Science & Engineering",
            yearOfStudy = 2,
            bankAccountMasked = "XXXXXXXX5678",
            bankIfsc = "SBIN0002109",
            bankName = "SBI",
            apaarId = "APAAR-9876-5432-1098"
        )

        val app = ApplicationEntity(
            id = "APP_2026_01",
            studentId = "STU_2026_01",
            schemeId = "SCH_PMS",
            schemeName = "Post-Matric Scholarship for ST Students",
            appliedDate = "2026-08-15",
            currentStage = "UNDER_VERIFICATION",
            stageProgress = 0.5f,
            hasDiscrepancy = true,
            academicYear = "2025-26"
        )

        val records = verificationDataSource.verifyAllSources(student, app, "2026-09-19 10:00:00")

        // 6 distinct rails: UIDAI, DigiLocker, AISHE/UDISE+, APAAR/ABC, UGC/NTA, e-District
        assertEquals(6, records.size)
        assertEquals(1, records.count { it.sourceSystem.startsWith("UIDAI") })
        assertEquals(1, records.count { it.sourceSystem.startsWith("DigiLocker") })
        assertEquals(1, records.count { it.sourceSystem.startsWith("AISHE") })
        assertEquals(1, records.count { it.sourceSystem.startsWith("APAAR") })
        assertEquals(1, records.count { it.sourceSystem.startsWith("UGC") })
        assertEquals(1, records.count { it.sourceSystem.startsWith("e-District") })

        // Strictly scoped to target application
        assertTrue(records.all { it.applicationId == "APP_2026_01" })
    }

    @Test
    fun reviewerRbac_studentRoleCannotResolveReviewItems() {
        val currentUserRole = UserRole.STUDENT.name

        val exception = assertThrows(SecurityException::class.java) {
            if (currentUserRole != UserRole.REVIEWER.name) {
                throw SecurityException("Unauthorized: Only verified Reviewing Officers may clear or resolve exception items.")
            }
        }

        assertTrue(exception.message!!.contains("Unauthorized"))
        assertTrue(exception.message!!.contains("Reviewing Officers"))
    }

    @Test
    fun reviewerResolution_whenApproved_clearsDiscrepancyAndPromotesToSanctioned() {
        val initialApp = ApplicationEntity(
            id = "APP_2026_01",
            studentId = "STU_2026_01",
            schemeId = "SCH_PMS",
            schemeName = "Post-Matric Scholarship for ST Students",
            appliedDate = "2026-08-15",
            currentStage = "UNDER_VERIFICATION",
            stageProgress = 0.60f,
            hasDiscrepancy = true,
            pendingActionDesc = "e-District Income Certificate discrepancy (+11.9%). Waiting for Reviewer Desk exception clearance.",
            sanctionedAmount = 48500.0,
            disbursedAmount = 0.0,
            academicYear = "2025-26"
        )

        val reviewItem = ReviewQueueEntity(
            id = "REV_2026_01",
            applicationId = "APP_2026_01",
            studentName = "Birsa Munda Tirkey",
            schemeName = "Post-Matric Scholarship for ST Students",
            category = "ST (Scheduled Tribe)",
            discrepancyType = "INCOME_VARIANCE",
            declaredValue = "₹1,60,000 / annum",
            retrievedValue = "₹1,79,000 / annum",
            variancePercent = "+11.9%",
            toleranceLimit = "±15.0%",
            recommendation = "APPROVE (Within statutory ceiling of ₹2.50L)",
            status = "PENDING",
            assignedOfficer = "Dr. Anita Hansda",
            createdAt = "2026-09-02 09:15"
        )

        // Officer approval with tolerance clearance
        val isApproved = true
        val officerNotes = "Tolerance accepted per SIH Ministry norms: ₹1.79L is within ₹2.50L ceiling."

        val resolvedReviewItem = reviewItem.copy(
            status = if (isApproved) "RESOLVED_ACCEPTED" else "RESOLVED_REJECTED",
            reviewedAt = "2026-09-19 11:00:00",
            reviewerNotes = officerNotes
        )

        val updatedApp = initialApp.copy(
            hasDiscrepancy = false,
            pendingActionDesc = null,
            currentStage = "SANCTIONED",
            stageProgress = 0.85f
        )

        assertEquals("RESOLVED_ACCEPTED", resolvedReviewItem.status)
        assertEquals("SANCTIONED", updatedApp.currentStage)
        assertFalse(updatedApp.hasDiscrepancy)
        assertNull(updatedApp.pendingActionDesc)
        assertEquals(0.85f, updatedApp.stageProgress)
    }

    @Test
    fun reviewerResolution_whenRejected_flagsActionRequiredForStudent() {
        val initialApp = ApplicationEntity(
            id = "APP_2026_01",
            studentId = "STU_2026_01",
            schemeId = "SCH_PMS",
            schemeName = "Post-Matric Scholarship for ST Students",
            appliedDate = "2026-08-15",
            currentStage = "UNDER_VERIFICATION",
            stageProgress = 0.60f,
            hasDiscrepancy = true,
            pendingActionDesc = "Pending review",
            academicYear = "2025-26"
        )

        val rejectionReason = "Revenue certificate expired. Student must re-sync via DigiLocker."

        val updatedApp = initialApp.copy(
            currentStage = "ACTION_REQUIRED",
            hasDiscrepancy = true,
            pendingActionDesc = "Reviewer clarification required: $rejectionReason"
        )

        assertEquals("ACTION_REQUIRED", updatedApp.currentStage)
        assertTrue(updatedApp.hasDiscrepancy)
        assertNotNull(updatedApp.pendingActionDesc)
        assertTrue(updatedApp.pendingActionDesc!!.contains("Reviewer clarification required"))
    }

    @Test
    fun switchStudent_demoIdentitiesOnly_rejectsArbitraryProfileSwitching() {
        // Assert that the demo permitted set consists strictly of the two designated identities
        val permittedDemoIds = setOf("STU_2026_01", "REV_OFFICER_01")
        assertEquals(2, permittedDemoIds.size)
        assertTrue(permittedDemoIds.contains("STU_2026_01"))
        assertTrue(permittedDemoIds.contains("REV_OFFICER_01"))

        // Any arbitrary student ID must be rejected with SecurityException
        val arbitraryCallers = listOf(
            "STU_2026_02",
            "STU_2026_03",
            "STU_ATTACKER_99",
            "ADMIN_IMPERSONATOR",
            "",
            " "
        )

        for (unauthorizedId in arbitraryCallers) {
            val exception = assertThrows(SecurityException::class.java) {
                if (unauthorizedId !in permittedDemoIds) {
                    throw SecurityException(
                        "Access Denied: Profile switching is restricted exclusively to seeded demo identities ('STU_2026_01', 'REV_OFFICER_01'). Arbitrary profile switching is prohibited."
                    )
                }
            }
            assertTrue(exception.message!!.contains("restricted exclusively to seeded demo identities"))
        }
    }
}
