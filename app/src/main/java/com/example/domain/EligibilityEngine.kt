package com.example.domain

import com.example.data.model.ApplicationEntity
import com.example.data.model.DocumentEntity
import com.example.data.model.SchemeEntity
import com.example.data.model.StudentEntity

enum class EligibilityStatus {
    ELIGIBLE,
    NOT_ELIGIBLE,
    NEEDS_REVIEW
}

data class EligibilityResult(
    val status: EligibilityStatus,
    val matchPercentage: Int,
    val matchedCriteria: List<String>,
    val failedCriteria: List<String>,
    val missingDocuments: List<String>,
    val conflictReason: String? = null,
    val summaryRecommendation: String
)

data class EligibilityEvaluation(
    val schemeId: String,
    val schemeCode: String,
    val schemeName: String,
    val isEligible: Boolean,
    val matchReasons: List<String>,
    val disqualificationReasons: List<String>,
    val estimatedGrant: String,
    val isUnclaimed: Boolean,
    val isDemoRule: Boolean = true
)

object EligibilityEngine {

    /**
     * Evaluates a student's profile, academic credentials, document inventory,
     * and existing active applications against a specific government scholarship scheme.
     * Enforces the "One Active Scholarship/Fellowship Rule".
     */
    fun evaluate(
        student: StudentEntity,
        scheme: SchemeEntity,
        documents: List<DocumentEntity>,
        existingApplications: List<ApplicationEntity>
    ): EligibilityResult {
        val matched = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val missingDocs = mutableListOf<String>()
        var conflictReason: String? = null

        // 1. ST Category & PVTG Check (Mandatory across MoTA schemes)
        val isSt = student.category.contains("ST", ignoreCase = true) || 
                   student.category.contains("Scheduled Tribe", ignoreCase = true) ||
                   !student.pvtgCommunity.isNullOrBlank()

        if (isSt) {
            matched.add("Category verified: ${student.category}")
            if (!student.pvtgCommunity.isNullOrBlank()) {
                matched.add("PVTG community priority identified (${student.pvtgCommunity})")
            }
        } else {
            failed.add("Applicant must belong to Scheduled Tribe (ST) category under Ministry guidelines.")
        }

        // 2. Active Scholarship Conflict Check (One Active Award Rule)
        val activeOtherApp = existingApplications.firstOrNull { app ->
            app.schemeId != scheme.id &&
            app.currentStage in listOf("SUBMITTED", "INSTITUTE_VERIFICATION", "STATE_VERIFICATION", "MINISTRY_REVIEW", "SANCTIONED")
        }

        if (activeOtherApp != null) {
            conflictReason = "Ekikrit found an active award linked to your profile (${activeOtherApp.schemeName}). Under Ministry of Tribal Affairs guidelines, only one concurrent scholarship or fellowship can be drawn per academic cycle."
        }

        // 3. Scheme-Specific Evaluation Rules
        when (scheme.id) {
            "SCH_PRE" -> {
                // Pre-Matric: Class IX & X
                if (student.academicLevel == "SECONDARY" || student.course.contains("Class", ignoreCase = true)) {
                    matched.add("Enrolled in recognized Secondary Education (${student.institutionName})")
                } else {
                    failed.add("Pre-Matric is strictly for secondary school students (Classes IX and X).")
                }

                if (student.annualIncome <= 250000.0) {
                    matched.add("Annual income (₹${student.annualIncome.toInt()}) is below ₹2.50 Lakh ceiling")
                } else {
                    failed.add("Annual household income exceeds ₹2.50 Lakh threshold for Pre-Matric.")
                }
            }

            "SCH_PMS" -> {
                // Post-Matric: Post-secondary recognized courses
                if (student.academicLevel in listOf("UNDERGRADUATE", "POSTGRADUATE", "HIGHER_SECONDARY", "PHD") ||
                    !student.institutionId.startsWith("UDISE")) {
                    matched.add("Post-secondary enrollment verified via AISHE (${student.institutionName})")
                } else {
                    failed.add("Must be enrolled in post-matriculation / higher education course.")
                }

                if (student.annualIncome <= 250000.0) {
                    matched.add("Annual family income (₹${student.annualIncome.toInt()}) satisfies ₹2.50L ceiling")
                } else {
                    failed.add("Family income exceeds ₹2.50 Lakh limit for Post-Matric ST grant.")
                }
            }

            "SCH_TOPCLASS" -> {
                // Top Class Education: Premier Institutes (IIT, NIT, IIM, AIIMS, National Law Universities)
                val isPremierInstitute = student.institutionName.contains("NIT", ignoreCase = true) ||
                                         student.institutionName.contains("IIT", ignoreCase = true) ||
                                         student.institutionName.contains("National Institute", ignoreCase = true) ||
                                         student.institutionName.contains("Indian Institute", ignoreCase = true)

                if (isPremierInstitute) {
                    matched.add("Enrolled in Notified Premier Institution of National Importance (${student.institutionName})")
                } else {
                    failed.add("Top Class Scheme requires admission to Ministry-notified premier institutions (IIT/NIT/IIM/AIIMS).")
                }

                if (student.annualIncome <= 600000.0) {
                    matched.add("Income is within Top Class limit of ₹6.00 Lakh (Current: ₹${student.annualIncome.toInt()})")
                } else {
                    failed.add("Family income exceeds ₹6.00 Lakh ceiling for Top Class scheme.")
                }
            }

            "SCH_NFST" -> {
                // National Fellowship for Higher Education: M.Phil / Ph.D with NET / Entrance qualification
                if (student.academicLevel == "PHD" || student.course.contains("Ph.D", ignoreCase = true) ||
                    student.qualificationDetails.contains("NET", ignoreCase = true)) {
                    matched.add("Higher education fellowship qualification verified")
                } else {
                    failed.add("Candidate must be pursuing M.Phil / Ph.D or qualified UGC-NET / CSIR-NET.")
                }
            }

            "SCH_NOS" -> {
                // National Overseas Scholarship: Top 500 QS foreign university admit
                if (student.qualificationDetails.contains("QS", ignoreCase = true) ||
                    student.qualificationDetails.contains("Abroad", ignoreCase = true) ||
                    student.course.contains("Overseas", ignoreCase = true)) {
                    matched.add("Overseas university admission criteria authenticated")
                } else {
                    failed.add("Requires unconditional admission offer from Top 500 QS World University Ranked foreign institute.")
                }
            }

            else -> {
                matched.add("General tribal scheme eligibility conditions satisfied")
            }
        }

        // 4. Document Inventory Cross-Check
        val hasAadhaar = documents.any { it.type.contains("Aadhaar", ignoreCase = true) && it.verificationStatus == "VERIFIED" }
        val hasCaste = documents.any { it.type.contains("Caste", ignoreCase = true) || it.type.contains("ST", ignoreCase = true) }
        val hasIncome = documents.any { it.type.contains("Income", ignoreCase = true) }
        val hasMarksheet = documents.any { it.type.contains("Marksheet", ignoreCase = true) || it.type.contains("Secondary", ignoreCase = true) }

        if (hasAadhaar) matched.add("Aadhaar e-KYC linked") else missingDocs.add("Aadhaar Demographic Card")
        if (hasCaste) matched.add("DigiLocker ST Caste Certificate available") else missingDocs.add("Scheduled Tribe Certificate")
        if (hasIncome) matched.add("Annual Income Certificate available") else missingDocs.add("Income Certificate")
        if (hasMarksheet) matched.add("Academic Marksheet / Transcript linked") else missingDocs.add("Academic Marksheet")

        // 5. Compute Match Score and Overall Status
        val totalCriteria = (matched.size + failed.size + missingDocs.size).coerceAtLeast(1)
        val matchPercentage = ((matched.size.toDouble() / totalCriteria) * 100).toInt().coerceIn(0, 100)

        val status = when {
            failed.isNotEmpty() -> EligibilityStatus.NOT_ELIGIBLE
            conflictReason != null -> EligibilityStatus.NEEDS_REVIEW
            missingDocs.isNotEmpty() -> EligibilityStatus.NEEDS_REVIEW
            else -> EligibilityStatus.ELIGIBLE
        }

        val summary = when (status) {
            EligibilityStatus.ELIGIBLE -> "You are 100% eligible for this scheme with existing digital credentials. Ready for 1-click application!"
            EligibilityStatus.NEEDS_REVIEW -> {
                if (conflictReason != null) {
                    "Active scholarship conflict detected: Review existing award before applying."
                } else {
                    "Partial match (${matchPercentage}%). Missing ${missingDocs.size} certificate(s) in DigiLocker wallet."
                }
            }
            EligibilityStatus.NOT_ELIGIBLE -> "Currently does not meet core eligibility criteria: ${failed.firstOrNull()}"
        }

        return EligibilityResult(
            status = status,
            matchPercentage = matchPercentage,
            matchedCriteria = matched,
            failedCriteria = failed,
            missingDocuments = missingDocs,
            conflictReason = conflictReason,
            summaryRecommendation = summary
        )
    }

    /**
     * Fast statutory rule check for ST, income ceiling, course level, premier institute.
     */
    fun evaluateEligibility(
        student: StudentEntity?,
        scheme: SchemeEntity,
        existingApplications: List<ApplicationEntity>
    ): EligibilityEvaluation {
        if (student == null) {
            return EligibilityEvaluation(
                schemeId = scheme.id,
                schemeCode = scheme.code,
                schemeName = scheme.name,
                isEligible = false,
                matchReasons = emptyList(),
                disqualificationReasons = listOf("No active student profile selected."),
                estimatedGrant = scheme.maxAmount,
                isUnclaimed = false
            )
        }

        val matchReasons = mutableListOf<String>()
        val disqualifications = mutableListOf<String>()

        // 1. Beneficiary Category Check (Schemes are for ST beneficiaries)
        val isST = student.category.contains("ST", ignoreCase = true) ||
                   student.category.contains("Scheduled Tribe", ignoreCase = true) ||
                   !student.pvtgCommunity.isNullOrBlank()

        if (isST) {
            val label = if (!student.pvtgCommunity.isNullOrBlank()) "ST (PVTG - ${student.pvtgCommunity})" else student.category
            matchReasons.add("Beneficiary verified as Scheduled Tribe ($label)")
        } else if (student.category.isBlank()) {
            disqualifications.add("Category not specified in profile. Complete profile verification.")
        } else {
            disqualifications.add("Scheme is reserved for ST beneficiaries (Current category: ${student.category})")
        }

        // 2. Scheme-Specific Multi-Factor Checks
        when (scheme.id) {
            "SCH_PRE" -> {
                val isSecondary = student.course.contains("Class IX", ignoreCase = true) ||
                                  student.course.contains("Class 9", ignoreCase = true) ||
                                  student.course.contains("Class X", ignoreCase = true) ||
                                  student.course.contains("Class 10", ignoreCase = true) ||
                                  student.course.contains("Secondary", ignoreCase = true) ||
                                  student.institutionId.startsWith("UDISE")

                if (isSecondary) {
                    matchReasons.add("Enrolled in recognized secondary education (${student.course.ifBlank { "Secondary School" }})")
                } else {
                    disqualifications.add("Requires regular enrollment in Class IX or X (Current: ${student.course.ifBlank { "Higher Education" }})")
                }

                if (student.annualIncome in 1.0..250000.0) {
                    matchReasons.add("Family income within ₹2.50L threshold (Declared: ₹${student.annualIncome.toInt()})")
                } else if (student.annualIncome > 250000.0) {
                    disqualifications.add("Family income exceeds ₹2.50L statutory ceiling (Declared: ₹${student.annualIncome.toInt()})")
                } else if (student.annualIncome == 0.0) {
                    disqualifications.add("Annual income certificate required.")
                }
            }

            "SCH_PMS" -> {
                val isSecondary = student.course.contains("Class IX", ignoreCase = true) ||
                                  student.course.contains("Class X", ignoreCase = true)

                if (!isSecondary && (student.course.isNotBlank() || student.institutionName.isNotBlank())) {
                    matchReasons.add("Enrolled in post-matric / post-secondary course (${student.course.ifBlank { "Higher Education" }})")
                } else {
                    disqualifications.add("Requires post-matriculation stage of education.")
                }

                if (student.annualIncome in 1.0..250000.0) {
                    matchReasons.add("Family income within ₹2.50L threshold (Declared: ₹${student.annualIncome.toInt()})")
                } else if (student.annualIncome > 250000.0) {
                    disqualifications.add("Family income exceeds ₹2.50L ceiling (Declared: ₹${student.annualIncome.toInt()})")
                } else if (student.annualIncome == 0.0) {
                    disqualifications.add("Annual income certificate required.")
                }
            }

            "SCH_TOPCLASS" -> {
                val premierKeywords = listOf("IIT", "NIT", "IIM", "AIIMS", "National Institute", "Indian Institute")
                val isPremierInstitute = premierKeywords.any { student.institutionName.contains(it, ignoreCase = true) }

                if (isPremierInstitute) {
                    matchReasons.add("Enrolled in notified premier institute (${student.institutionName})")
                } else {
                    disqualifications.add("Requires admission to notified premier institute (IIT/NIT/IIM/AIIMS) (Current: ${student.institutionName.ifBlank { "Non-premier" }})")
                }

                if (student.annualIncome in 1.0..600000.0) {
                    matchReasons.add("Family income within ₹6.00L ceiling (Declared: ₹${student.annualIncome.toInt()})")
                } else if (student.annualIncome > 600000.0) {
                    disqualifications.add("Family income exceeds ₹6.00L ceiling (Declared: ₹${student.annualIncome.toInt()})")
                } else if (student.annualIncome == 0.0) {
                    disqualifications.add("Annual income certificate required.")
                }
            }

            "SCH_NFST" -> {
                val isResearch = student.course.contains("Ph.D", ignoreCase = true) ||
                                 student.course.contains("M.Phil", ignoreCase = true) ||
                                 student.course.contains("Research", ignoreCase = true)
                if (isResearch) {
                    matchReasons.add("Enrolled in full-time M.Phil / Ph.D research program")
                } else {
                    disqualifications.add("Requires qualification in UGC-NET and full-time M.Phil/Ph.D enrollment.")
                }
            }

            "SCH_NOS" -> {
                val isOverseas = student.institutionName.contains("Foreign", ignoreCase = true) ||
                                 student.institutionName.contains("Abroad", ignoreCase = true)
                if (isOverseas) {
                    matchReasons.add("Selected for accredited overseas institution")
                } else {
                    disqualifications.add("Requires selection in Top 500 QS World University Ranked foreign institute.")
                }
            }
        }

        val isEligible = isST && disqualifications.isEmpty()
        val hasActiveApp = existingApplications.any {
            it.schemeId == scheme.id && it.currentStage !in listOf("NOT_APPLIED", "REJECTED")
        }
        val isUnclaimed = isEligible && !hasActiveApp

        return EligibilityEvaluation(
            schemeId = scheme.id,
            schemeCode = scheme.code,
            schemeName = scheme.name,
            isEligible = isEligible,
            matchReasons = matchReasons,
            disqualificationReasons = disqualifications,
            estimatedGrant = scheme.maxAmount,
            isUnclaimed = isUnclaimed
        )
    }

    /**
     * Finds the highest priority unclaimed scholarship match for the student.
     */
    fun findTopUnreachedScheme(
        student: StudentEntity?,
        schemes: List<SchemeEntity>,
        existingApplications: List<ApplicationEntity>
    ): EligibilityEvaluation? {
        if (student == null) return null
        return schemes
            .map { evaluateEligibility(student, it, existingApplications) }
            .firstOrNull { it.isUnclaimed }
    }
}

