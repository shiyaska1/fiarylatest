package com.billing.pos.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exports a single diary entry (text + its attachment files) into a .zip. */
object DiaryExport {

    suspend fun exportEntry(context: Context, entryId: Long): File? {
        val dao = AppDatabase.get(context).diaryDao()
        val entry = dao.byId(entryId) ?: return null
        val attachments = dao.attachmentsFor(entryId)
        val blocks = dao.blocksFor(entryId)

        val root = JSONObject()
        root.put("title", entry.title)
        root.put("remarks", entry.remarks)
        root.put("createdAt", entry.createdAt)
        root.put("updatedAt", entry.updatedAt)
        val arr = JSONArray()
        attachments.forEach { a ->
            arr.put(
                JSONObject().put("name", a.name).put("mime", a.mime)
                    .put("type", a.type.name)
                    .put("file", if (a.type == AttachmentType.LOCATION) "" else File(a.path).name)
                    .put("location", if (a.type == AttachmentType.LOCATION) a.path else "")
            )
        }
        root.put("attachments", arr)
        val blockArr = JSONArray()
        blocks.forEach { b ->
            blockArr.put(
                JSONObject().put("type", b.type.name).put("text", b.text)
                    .put("name", b.name).put("mime", b.mime)
                    .put("file", if (b.path.isBlank()) "" else File(b.path).name)
            )
        }
        root.put("blocks", blockArr)

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safe = (entry.title.ifBlank { "diary-$entryId" }).replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
        val zip = File(dir, "diary-$safe.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("entry.json"))
            zos.write(root.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            attachments.filter { it.type != AttachmentType.LOCATION }.forEach { a ->
                val f = File(a.path)
                if (f.exists()) {
                    zos.putNextEntry(ZipEntry("files/" + f.name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            blocks.filter { it.path.isNotBlank() }.forEach { b ->
                val f = File(b.path)
                if (f.exists()) {
                    zos.putNextEntry(ZipEntry("files/" + f.name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        return zip
    }
}
