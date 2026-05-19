package dev.anchildress1.vestige.ui.history

/**
 * TEMPORARY UI SEED — the 3-lens read + field grid in the resolved entry view have no model
 * backing yet (the extraction pipeline does not surface them). This hardcoded sample is a
 * deliberate, user-approved placeholder so the screen matches `poc/entry-full-final.png`
 * pending real wiring. It overrides the AGENTS.md "no user-facing fiction" guardrail for this
 * one screen only; it is isolated here so the seam is obvious and easy to delete on wire-up.
 */
enum class LensTone { CANONICAL, CONFLICT, AMBIGUOUS, CANDIDATE }

data class LensRead(val label: String, val value: String, val tone: LensTone)

data class FieldRow(val label: String, val value: String, val tone: LensTone)

object EntryDetailSeed {
    const val THREE_LENS_EYEBROW: String = "● THREE-LENS READ"
    const val THREE_LENS_STATUS: String = "CANONICAL · WITH CONFLICT"
    const val EXTRACTING_EYEBROW: String = "● EXTRACTING · 3 LENSES"
    const val EXTRACTING_BODY: String =
        "Convergence resolves in the background. Open the entry later for the full read."
    const val FAILED_EYEBROW: String = "● EXTRACTION DID NOT FINISH"
    const val FAILED_BODY: String = "The transcript was saved, but the structured read did not resolve."

    val lenses: List<LensRead> = listOf(
        LensRead("LITERAL", "battery yanked", LensTone.CANONICAL),
        LensRead("INFERENTIAL", "post-meeting energy crash", LensTone.CANONICAL),
        LensRead("SKEPTICAL", "\"not tired\" vs \"battery got yanked\"", LensTone.CONFLICT),
    )

    val fields: List<FieldRow> = listOf(
        FieldRow("BEHAVIOR", "post-meeting drop", LensTone.CANONICAL),
        FieldRow("STATE", "crashed", LensTone.CONFLICT),
        FieldRow("VOCAB", "battery, yanked", LensTone.CANONICAL),
        FieldRow("PROMISES", "—", LensTone.AMBIGUOUS),
        FieldRow("REPEAT", "matches Tue-Meetings (4/12)", LensTone.CANDIDATE),
    )
}
