package com.flash.headway.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Keeps the last crash so it can be read without ADB. A crash is written to a file before the app
 * dies; the next launch shows it (in plain Android views, no Compose) with a way to copy it.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"

    /** Records any uncaught crash, then lets the system handle it as before. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName
                File(app.filesDir, FILE).writeText(
                    "Headway $version · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}\n\n" + error.stackTraceToString(),
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * If the last run crashed, shows what happened and returns true; the activity then waits for
     * the rider to continue. "Open Headway" clears the record and starts the app normally.
     */
    fun showIfCrashed(activity: Activity, onContinue: () -> Unit): Boolean {
        val file = File(activity.filesDir, FILE)
        if (!file.exists()) return false
        val text = runCatching { file.readText() }.getOrDefault("")
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }
        column.addView(TextView(activity).apply {
            this.text = "Headway crashed"
            textSize = 22f
        })
        column.addView(TextView(activity).apply {
            this.text = "Copy the details and send them to the developer."
            setPadding(0, pad / 2, 0, pad)
        })
        column.addView(Button(activity).apply {
            this.text = "Copy details"
            setOnClickListener {
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("Headway crash", text))
                Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
            }
        })
        column.addView(Button(activity).apply {
            this.text = "Open Headway"
            setOnClickListener {
                file.delete()
                onContinue()
            }
        })
        column.addView(TextView(activity).apply {
            this.text = text
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(0, pad, 0, 0)
        })
        activity.setContentView(ScrollView(activity).apply { addView(column) })
        return true
    }
}
