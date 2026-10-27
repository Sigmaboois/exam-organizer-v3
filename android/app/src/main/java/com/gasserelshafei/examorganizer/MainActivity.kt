package com.gasserelshafei.examorganizer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.gasserelshafei.examorganizer.ui.ExamOrganizerTheme
import com.gasserelshafei.examorganizer.ui.ExamOrganizerUi

class MainActivity : ComponentActivity() {
    companion object {
        /** Set by the "lesson link sent" notifications. */
        const val OPEN_LESSONS = "open_lessons"
    }

    private val state: AppState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ExamOrganizerTheme { ExamOrganizerUi(state) } }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** PDFs shared to the app (Share / Open with) from Files, WhatsApp, Gmail, notes apps… */
    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(OPEN_LESSONS, false) == true) state.screen = Screen.LESSONS
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) state.openIncoming(uris)
    }
}
