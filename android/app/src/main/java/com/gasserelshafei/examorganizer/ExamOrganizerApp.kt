package com.gasserelshafei.examorganizer

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class ExamOrganizerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // PDFBox needs its fonts and resources loaded once
        PDFBoxResourceLoader.init(applicationContext)
        Subjects.load(this)
    }
}
