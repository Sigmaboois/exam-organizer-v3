package com.gasserelshafei.examorganizer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.UUID

/**
 * Where the app keeps the teacher's files. The teacher picks one folder (Documents, Downloads,
 * Google Drive…); inside it the app makes "Exams" (sorted papers) and "Classes/<class>/<student>"
 * (marked PDFs and mistakes reports). Until a folder is picked, the app's own storage is used.
 * Paths are kept relative to that folder ("Classes/Year 11/Sara/…"), so renaming keeps working.
 */
class Store(private val context: Context) {
    private val prefs = context.getSharedPreferences("store", Context.MODE_PRIVATE)

    val chosenFolder: Uri? get() = prefs.getString("tree", null)?.let(Uri::parse)

    val label: String
        get() = chosenFolder?.let { DocumentFile.fromTreeUri(context, it)?.name } ?: "This phone (the app's own storage)"

    fun choose(tree: Uri) {
        context.contentResolver.takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.edit().putString("tree", tree.toString()).apply()
    }

    fun useAppStorage() = prefs.edit().remove("tree").apply()

    /** The root folder: the chosen one, or Android/data/<app>/files/Exam Organizer. */
    fun root(): DocumentFile {
        chosenFolder?.let { tree ->
            DocumentFile.fromTreeUri(context, tree)?.takeIf { it.canWrite() }?.let { return it }
        }
        val own = File(context.getExternalFilesDir(null) ?: context.filesDir, "Exam Organizer").apply { mkdirs() }
        return DocumentFile.fromFile(own)
    }

    /** A folder at this path below the root, created if needed. */
    fun folder(path: List<String>, create: Boolean = true): DocumentFile? {
        var current = root()
        for (raw in path) {
            val name = safeName(raw)
            current = current.findFile(name)?.takeIf { it.isDirectory }
                ?: (if (create) current.createDirectory(name) else null)
                ?: return null
        }
        return current
    }

    fun file(path: String?): DocumentFile? {
        if (path.isNullOrBlank()) return null
        val parts = path.split('/')
        return folder(parts.dropLast(1), create = false)?.findFile(parts.last())?.takeIf { it.isFile }
    }

    /** Copy a local file into a folder below the root. Returns its relative path. */
    fun save(source: File, path: List<String>, name: String, mime: String, replace: Boolean = false): String {
        val folder = folder(path) ?: throw UserError("I couldn't create the folder ${path.joinToString("/")}.")
        val safe = safeName(name)
        val relative = (path.map(::safeName) + safe).joinToString("/")
        if (folder.uri.scheme == "file") {
            // The app's own storage: plain files (DocumentFile would add a second ".pdf")
            val target = File(folder.uri.path!!, safe)
            if (target.exists() && !replace) throw UserError("Already sorted. An exam with the same name is already in that folder.")
            source.copyTo(target, overwrite = true)
            return relative
        }
        folder.findFile(safe)?.let { existing ->
            if (!replace) throw UserError("Already sorted. An exam with the same name is already in that folder.")
            existing.delete()
        }
        val target = folder.createFile(mime, safe) ?: throw UserError("I couldn't save $safe.")
        context.contentResolver.openOutputStream(target.uri, "w")?.use { out -> source.inputStream().use { it.copyTo(out) } }
            ?: throw UserError("I couldn't save $safe.")
        return (path.map(::safeName) + (target.name ?: safe)).joinToString("/")
    }

    /** A name that isn't taken yet in that folder: "x.pdf", "x (2).pdf"… */
    fun freeName(path: List<String>, name: String): String {
        val folder = folder(path) ?: return name
        if (folder.findFile(name) == null) return name
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var number = 2
        while (folder.findFile("$stem ($number)${if (ext.isEmpty()) "" else ".$ext"}") != null) number++
        return "$stem ($number)${if (ext.isEmpty()) "" else ".$ext"}"
    }

    fun rename(path: List<String>, newName: String): Boolean {
        val folder = folder(path, create = false) ?: return false
        if (folder.parentFile?.findFile(safeName(newName)) != null) return false
        return folder.renameTo(safeName(newName))
    }

    /** A copy of a stored file in the app's cache (to read it as a normal file). */
    fun localCopy(path: String): File? {
        val document = file(path) ?: return null
        val target = File(context.cacheDir, "open/${UUID.randomUUID()}/${document.name ?: "file"}").apply { parentFile?.mkdirs() }
        context.contentResolver.openInputStream(document.uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: return null
        return target
    }

    /** A content:// link other apps (PDF viewers, Share) can open. */
    fun shareUri(document: DocumentFile): Uri =
        if (document.uri.scheme == "file") FileProvider.getUriForFile(context, "${context.packageName}.files", File(document.uri.path!!))
        else document.uri

    companion object {
        fun safeName(text: String): String {
            val cleaned = text.map { if (it in "<>:\"/\\|?*") '-' else it }.joinToString("").trim(' ', '.')
            return cleaned.ifEmpty { "Untitled" }
        }
    }
}

/** Private working copies of the PDFs teachers add (originals are never changed). */
object Imports {
    fun folder(context: Context) = File(context.filesDir, "imports").apply { mkdirs() }

    fun copy(context: Context, uri: Uri, name: String?): File {
        val safe = Store.safeName(name?.takeIf { it.isNotBlank() } ?: "paper.pdf").let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
        val target = File(folder(context), "${UUID.randomUUID()}/$safe").apply { parentFile?.mkdirs() }
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: throw UserError("This file couldn't be opened.")
        return target
    }

    fun copy(context: Context, file: File, name: String = file.name): File {
        val target = File(folder(context), "${UUID.randomUUID()}/${Store.safeName(name)}").apply { parentFile?.mkdirs() }
        file.copyTo(target)
        return target
    }

    fun remove(context: Context, file: File) {
        val parent = file.parentFile ?: return
        if (parent.parentFile == folder(context)) parent.deleteRecursively()
    }

    fun clearAll(context: Context) {
        folder(context).deleteRecursively()
    }

    fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')

    /** Every PDF in a picked folder (and its sub-folders). */
    fun pdfsIn(context: Context, tree: Uri): List<DocumentFile> {
        val found = mutableListOf<DocumentFile>()
        fun walk(folder: DocumentFile) {
            for (item in folder.listFiles()) {
                if (item.isDirectory) walk(item)
                else if (item.name?.endsWith(".pdf", ignoreCase = true) == true) found += item
            }
        }
        DocumentFile.fromTreeUri(context, tree)?.let(::walk)
        return found.sortedBy { it.name }
    }
}

/** The sorting rules (the desktop app's renamer.py): one folder per paper, named the same way. */
object Library {
    private val seriesFolders = mapOf("GT" to "Grade thresholds", "ER" to "Examiner report")
    private val seriesNames = mapOf("GT" to "Grade thresholds", "ER" to "Examiner report", "IN" to "Insert",
        "CI" to "Confidential instructions")

    fun hasComponent(m: PaperMetadata) = m.paper != null && m.variant != null && m.paperType != "GT"

    /** Exams/Physics/IGCSE/0625/2024/May-June/4/2 */
    fun folder(m: PaperMetadata): List<String> {
        val parts = mutableListOf("Exams", m.subjectName ?: "Unknown subject", m.qualification ?: "Unknown",
            m.subjectCode ?: "0000", m.year ?: "Unknown year", m.session ?: "Unknown session")
        if (hasComponent(m)) parts += listOf(m.paper!!, m.variant!!) else parts += seriesFolders[m.paperType] ?: "Series"
        return parts
    }

    /** "Physics - Paper 4 v2 QP.pdf", or "Physics - Grade thresholds.pdf" for a series file. */
    fun fileName(m: PaperMetadata): String {
        val subject = m.subjectName ?: "Unknown subject"
        if (hasComponent(m)) return Store.safeName("$subject - Paper ${m.paper} v${m.variant} ${m.paperType}") + ".pdf"
        val label = seriesNames[m.paperType] ?: m.paperType ?: "Paper"
        return Store.safeName("$subject - $label") + ".pdf"
    }
}
