package com.example

import com.example.data.model.ApplicationEntity
import com.example.data.model.StudentEntity
import com.example.data.model.VerificationStatus
import com.example.data.verification.LocalDemoVerificationDataSource
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
 */
class EkikritVerificationAndReviewerTest {

    private lateinit var verificationDataSource: LocalDemoVerificationDataSource

    @Before
    fun setUp() {
        verificationDataSource = LocalDemoVerificationDataSource()
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
    }
}
