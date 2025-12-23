package com.example.camerax

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal browser for exported session ZIP files.
 *
 * - No new screens / no layout changes.
 * - Opens from long-press on Export or Send.
 * - Actions: Send to PC (calls back), Share, Delete, Delete old…
 */
object ExportBrowser {

    data class ZipItem(
        val file: File,
        val type: SessionType,
        val sizeBytes: Long,
        val modifiedMs: Long
    ) {
        fun displayLine(activity: Activity): String {
            val size = Formatter.formatShortFileSize(activity, sizeBytes)
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(modifiedMs))
            return "${type.name} • ${file.name} • $size • $date"
        }
    }

    fun inferSessionType(zipFile: File): SessionType {
        val parent = zipFile.parentFile?.name?.lowercase(Locale.US) ?: ""
        return when (parent) {
            "calibration" -> SessionType.CALIBRATION
            "capture" -> SessionType.CAPTURE
            else -> SessionType.CAPTURE
        }
    }

    fun show(
        activity: Activity,
        exportRoot: File,
        onSend: (File) -> Unit
    ) {
        val items = collectZips(activity, exportRoot)
        if (items.isEmpty()) {
            Toast.makeText(activity, "No exported ZIPs found.", Toast.LENGTH_SHORT).show()
            return
        }

        val lines = items.map { it.displayLine(activity) }
        val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, lines)

        AlertDialog.Builder(activity)
            .setTitle("Exported ZIPs")
            .setAdapter(adapter) { _, which ->
                val item = items[which]
                showActions(activity, item, exportRoot, onSend)
            }
            .setNegativeButton("Close", null)
            .setNeutralButton("Delete old…") { _, _ ->
                showDeleteOld(activity, exportRoot) {
                    // re-open after delete
                    show(activity, exportRoot, onSend)
                }
            }
            .show()
    }

    private fun collectZips(activity: Activity, exportRoot: File): List<ZipItem> {
        if (!exportRoot.exists()) return emptyList()
        val captureDir = File(exportRoot, "capture")
        val calibDir = File(exportRoot, "calibration")

        val list = ArrayList<ZipItem>()
        fun addFromDir(dir: File, type: SessionType) {
            if (!dir.exists()) return
            dir.listFiles { f -> f.isFile && f.name.lowercase(Locale.US).endsWith(".zip") }
                ?.forEach { f ->
                    list.add(ZipItem(file = f, type = type, sizeBytes = f.length(), modifiedMs = f.lastModified()))
                }
        }
        addFromDir(captureDir, SessionType.CAPTURE)
        addFromDir(calibDir, SessionType.CALIBRATION)

        // If structure is different, fall back to scan.
        if (list.isEmpty()) {
            exportRoot.walkTopDown()
                .maxDepth(3)
                .filter { it.isFile && it.name.lowercase(Locale.US).endsWith(".zip") }
                .forEach { f ->
                    list.add(ZipItem(file = f, type = inferSessionType(f), sizeBytes = f.length(), modifiedMs = f.lastModified()))
                }
        }

        return list.sortedByDescending { it.modifiedMs }
    }

    private fun showActions(
        activity: Activity,
        item: ZipItem,
        exportRoot: File,
        onSend: (File) -> Unit
    ) {
        val options = arrayOf("Send to PC", "Share…", "Delete")
        AlertDialog.Builder(activity)
            .setTitle(item.file.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> onSend(item.file)
                    1 -> shareZip(activity, item.file)
                    2 -> confirmDelete(activity, item.file) {
                        // refresh listing after delete
                        show(activity, exportRoot, onSend)
                    }
                }
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun shareZip(activity: Activity, file: File) {
        try {
            val uri: Uri = try {
                FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", file)
            } catch (_: Throwable) {
                Uri.fromFile(file)
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "Share ZIP"))
        } catch (t: Throwable) {
            Toast.makeText(activity, "Share failed: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete(activity: Activity, file: File, onDone: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("Delete ZIP?")
            .setMessage(file.name)
            .setPositiveButton("Delete") { _, _ ->
                val ok = try { file.delete() } catch (_: Throwable) { false }
                Toast.makeText(activity, if (ok) "Deleted" else "Delete failed", Toast.LENGTH_SHORT).show()
                onDone()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteOld(activity: Activity, exportRoot: File, onDone: () -> Unit) {
        val options = arrayOf("Older than 7 days", "Older than 30 days", "Older than 90 days")
        val days = intArrayOf(7, 30, 90)

        AlertDialog.Builder(activity)
            .setTitle("Delete old ZIPs")
            .setItems(options) { _, which ->
                val cutoffMs = System.currentTimeMillis() - days[which].toLong() * 24L * 3600L * 1000L
                val deleted = deleteOlderThan(exportRoot, cutoffMs)
                Toast.makeText(activity, "Deleted $deleted ZIP(s).", Toast.LENGTH_SHORT).show()
                onDone()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteOlderThan(root: File, cutoffMs: Long): Int {
        var count = 0
        if (!root.exists()) return 0
        root.walkTopDown()
            .maxDepth(3)
            .filter { it.isFile && it.name.lowercase(Locale.US).endsWith(".zip") }
            .forEach { f ->
                if (f.lastModified() < cutoffMs) {
                    val ok = try { f.delete() } catch (_: Throwable) { false }
                    if (ok) count++
                }
            }
        return count
    }
}