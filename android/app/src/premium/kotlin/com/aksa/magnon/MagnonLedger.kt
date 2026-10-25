package com.aksa.magnon

import android.content.Context
import org.json.JSONObject

data class MagnonReserve(val ok: Boolean, val message: String)

/** Credits, the points board, and measurement jobs. Premium flavor only. */
object MagnonLedger {
    fun leaderboard(context: Context): String {
        val response = MagnonAccount.get(context, "/api/magnon/board", MagnonAccount.session(context))
        if (response.has("error")) return response.optString("error")
        return formatBoard(response)
    }

    fun jobs(context: Context): JSONObject =
        MagnonAccount.get(context, "/v1/magnon/jobs", MagnonAccount.session(context))

    fun decide(context: Context, jobId: String, accept: Boolean): JSONObject {
        val body = JSONObject().put("job_id", jobId).put("accept", accept)
        return MagnonAccount.post(context, "/v1/magnon/jobs", body, MagnonAccount.session(context))
    }

    fun reserve(context: Context, kind: String): MagnonReserve {
        val token = MagnonAccount.session(context) ?: return MagnonReserve(false, "Masuk dengan Gmail dulu.")
        val response = MagnonAccount.post(context, "/v1/magnon/reserve", JSONObject().put("kind", kind), token)
        val error = response.optString("error")
        if (error.isNotBlank()) {
            val message = when (error) {
                "credit_exhausted" -> if (kind == "post") "Kredit post hari ini habis." else "Kredit tes hari ini habis."
                "stay_on_lite" -> "Akun ini Magnon Lite. Tetap di aplikasi Lite."
                "login_required" -> "Masuk dengan Gmail dulu."
                else -> error
            }
            return MagnonReserve(false, message)
        }
        return MagnonReserve(response.optBoolean("ok"), "")
    }

    private fun formatBoard(json: JSONObject): String = buildString {
        append("Bulan ini\n")
        append(names(json.optJSONArray("month")))
        append("\nSepanjang waktu\n")
        append(names(json.optJSONArray("all_time")))
    }

    private fun names(rows: org.json.JSONArray?): String {
        if (rows == null || rows.length() == 0) return "—"
        return buildString {
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                append(index + 1)
                append(". ")
                append(row.optString("name"))
                append(" · ")
                append(row.optInt("points"))
                append('\n')
            }
        }.trim()
    }
}
