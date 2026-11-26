package id.winatra.ai

import android.content.Context

class Settings(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("winatra", Context.MODE_PRIVATE)

    /** Nama file model di filesDir/models/ */
    var modelName: String?
        get() = p.getString("model", null)
        set(v) = p.edit().putString("model", v).apply()
}
