package com.example.agent.bridge

/**
 * Tuning constants for [WhatsAppAgentBridge].
 *
 * Kept in their own file so the bridge class itself stays about wiring, not about numbers.
 */

/** Model calls kept for the Developer metrics panel. */
internal const val MAX_MODEL_METRICS = 50

/** Upper bound for the reflection interval: once a day is the slowest sensible cadence. */
internal const val MAX_AUTO_REFLECT_HOURS = 24
internal const val DEFAULT_AUTO_REFLECT_HOURS = 6

/** Scheduled task that performs the periodic self-review. */
internal const val AUTO_REFLECT_TASK_NAME = "Auto reflection"

/**
 * Prompt of the periodic self-review. Deliberately honest: storing nothing is an acceptable
 * outcome, which is what stops the agent from inventing "lessons".
 */
internal const val AUTO_REFLECT_PROMPT =
    "Lakukan refleksi singkat: tinjau percakapan dan tugas terakhir, lalu simpan 0-2 pelajaran " +
        "yang benar-benar berguna untuk masa depan dengan tool `reflect` (pakai `save_skill` bila " +
        "prosedurnya berulang). Jangan mengarang; bila tidak ada yang baru, balas \"Tidak ada pelajaran baru.\""
