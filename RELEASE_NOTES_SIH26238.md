# Ekikrit — SIH26238 Final Demo Freeze Release Notes

## Overview
**Problem Statement:** SIH26238 — Unified Scholarship Portal for the Ministry of Tribal Affairs (MoTA).  
**Release Target:** Final Demo Freeze & Stabilization  
**Platform:** Native Android (Kotlin 2.2.10, AGP 9.1.1, Jetpack Compose BOM 2024.09.00, Room 2.7.0 + KSP, JDK 21).

---

## 1. Architecture Consolidation
- **Single Source of Truth for Eligibility:** Consolidated all scholarship scoring and statutory criteria enforcement into `com.example.domain.EligibilityEngine`. Removed duplicate legacy implementations in `data/eligibility`.
- **Unified Verification Engine:** Streamlined 7-source multi-registry verification under `com.example.domain.UnifiedVerificationEngine`. Removed deprecated `VerificationDataSource`.
- **Database Seeding & Lifecycle:**
  - Implemented Room database lifecycle callbacks (`onCreate`, `onDestructiveMigration`) in `EkikritDatabase` executing on `Dispatchers.IO`.
  - Added startup validation in `EkikritViewModel` ensuring all 5 Ministry schemes, 3 student personas, DigiLocker documents, and review items are seeded deterministically.
  - Fixed `MIGRATION_1_2` schema discrepancies to ensure forward and backward database compatibility.
- **Role Gating & Reviewer Desk:**
  - Standardized role switching through `EkikritViewModel.switchToReviewerRole()` and `switchToStudentRole()`.
  - Linked `UserMode.OFFICER` to `REV_OFFICER_01` and `UserMode.STUDENT` to active student ID in `EkikritRepository`.
  - Enforced server/repository-side role authorization checks: unauthorized non-reviewer identities are strictly blocked from resolving exceptions.
  - Unified TopAppBar role toggle button under single `demo_switcher_btn` test tag.

---

## 2. Five Core MoTA Schemes (Verified & Tested)
1. **Pre-Matric Scholarship for ST Students (Classes IX & X):** Income ceiling ₹2.50L, curb dropout at secondary transition.
2. **Post-Matric Scholarship Scheme for ST Students:** Post-secondary diploma/degree, mandatory Aadhaar-linked bank account, income ceiling ₹2.50L.
3. **National Fellowship for ST Students (NFST):** M.Phil/Ph.D full-time research scholars, UGC-NET/CSIR-NET/GATE qualified.
4. **National Overseas Scholarship for ST Candidates (NOS):** Top 500 QS World Ranked universities for Masters/Ph.D, income ceiling ₹8.00L.
5. **National Fellowship and Scholarship for Higher Education (Top Class ST):** Premier institutes (IITs, NITs, IIMs, AIIMS, NLUs), income ceiling ₹6.00L.

---

## 3. Seven Verification Sources & Non-Blocking Exception Routing
- **Sources Queried Concurrently:** UIDAI, DigiLocker, AISHE, UDISE+, APAAR/ABC, UGC/NTA, e-District.
- **Automated Exception Routing:** e-District income variance of +11.9% (Birsa Munda Tirkey) triggers a non-blocking review queue item for officer tolerance check under Section 12 Rule without blocking or rejecting the application.
- **Privacy Scoping in Reviewer Desk:** Applicant name is de-identified (`APP-CASE-...`); caste/category is displayed only when caste is the field under dispute.

---

## 4. UI/UX and Standards Compliance
- **Material Design 3 AutoMirrored Icons:** Replaced deprecated icon references with `Icons.AutoMirrored.Filled.*` across modals, banners, and dashboard screens.
- **Complete TestTag Coverage:**
  - `demo_switcher_btn` (exactly once in TopAppBar)
  - `jago_fab_btn` & `jago_quick_chip_<index>`
  - `persona_picker_item_<studentId>`
  - `approve_exception_btn` & `request_resubmit_btn`
  - `disbursement_track_btn`
  - `apply_scheme_btn_<schemeId>`
- **Privacy & Security (DPDP Act):** Zero hardcoded API keys; masked Aadhaar (`XXXX-XXXX-8924`) and masked bank accounts (`A/C **4821`).

---

## 5. Automated Validation & Test Suite
All 33 unit tests pass on JVM (`./gradlew testDebugUnitTest`):
- `com.example.EkikritFinalValidationTest`: 7 tests verifying database seeding, unique index constraints, identity isolation, and reviewer queue resolution.
- `com.example.EkikritVerificationAndReviewerTest`: 5 tests verifying production `EkikritRepository` and `UnifiedVerificationEngine`.
- `com.example.EkikritEligibilityAndOwnershipTest`: 6 tests verifying eligibility rules and applicant isolation.
- `com.example.UnifiedVerificationTest`: 2 tests verifying multi-source concurrent verification.
- `com.example.EligibilityEngineTest`: 6 tests verifying statutory scheme ceilings and conflicts.
- `com.example.JagoGroundingTest`: 5 tests verifying PII sanitization, masking, and language context.
- `com.example.VerificationGatewayTest`: 2 tests verifying remote gateway models and mock interceptors.
