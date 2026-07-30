package com.billing.pos.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.io.File

// ---------- masters ----------

/** Kind of work the shop takes in ("Mobile", "Two wheeler", "AC service"…). */
@Entity(tableName = "service_types")
data class ServiceType(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

/** Job-card / job statuses. Seeded with Pending, Partial Complete, Completed, Rejected. */
@Entity(tableName = "service_statuses")
data class ServiceStatus(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

/** A model / vehicle / item the shop services ("iPhone 12", "Activa 5G"…). */
@Entity(tableName = "service_models")
data class ServiceModel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

/** A workshop employee/technician who can be assigned a job card. */
@Entity(tableName = "service_employees")
data class ServiceEmployee(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = ""
)

/** A job the workshop offers, with its usual price ("Screen replacement", "Oil change"…). */
@Entity(tableName = "service_jobs_master")
data class ServiceJobMaster(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val price: Double = 0.0
)

// ---------- job cards ----------

/**
 * A job card: one device/vehicle taken in for service. Money is jobsTotal + otherCharges;
 * balance is grandTotal - advance, derived rather than stored.
 */
@Entity(tableName = "service_job_cards")
data class ServiceJobCard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobNo: String,
    val name: String = "",
    val dateMillis: Long,
    val customerId: Long = 0,
    val customerName: String = "",
    val customerPhone: String = "",
    val typeId: Long = 0,
    val typeName: String = "",
    val modelId: Long = 0,
    val modelName: String = "",
    val employeeId: Long = 0,
    val employeeName: String = "",
    val status: String = "Pending",
    /** High / Medium / Low — drives list ordering and the red/orange highlight. */
    val priority: String = "Medium",
    /** Expected date the whole card is done. */
    val expectedMillis: Long = 0,
    val jobsTotal: Double = 0.0,
    val otherCharges: Double = 0.0,
    val otherChargesNote: String = "",
    val grandTotal: Double = 0.0,
    val advance: Double = 0.0,
    val remarks: String = ""
) {
    val balance: Double get() = grandTotal - advance
}

/** One job on a card, with its own price, status and expected completion date. */
@Entity(tableName = "service_job_lines")
data class ServiceJobLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val jobId: Long = 0,
    val name: String,
    val price: Double = 0.0,
    val status: String = "Pending",
    val expectedMillis: Long = 0
)

/** A photo/file attached to a job card (copied into app storage). */
@Entity(tableName = "service_job_attachments")
data class ServiceJobAttachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val path: String,
    val name: String,
    val mime: String
) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

@Dao
interface ServiceDao {
    // service types
    @Query("SELECT * FROM service_types ORDER BY name COLLATE NOCASE") fun types(): Flow<List<ServiceType>>
    @Query("SELECT * FROM service_types") suspend fun typesOnce(): List<ServiceType>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertType(t: ServiceType): Long
    @Delete suspend fun deleteType(t: ServiceType)

    // statuses
    @Query("SELECT * FROM service_statuses ORDER BY id") fun statuses(): Flow<List<ServiceStatus>>
    @Query("SELECT COUNT(*) FROM service_statuses") suspend fun statusCount(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertStatus(s: ServiceStatus): Long
    @Delete suspend fun deleteStatus(s: ServiceStatus)

    // models / vehicles / items
    @Query("SELECT * FROM service_models ORDER BY name COLLATE NOCASE") fun models(): Flow<List<ServiceModel>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertModel(m: ServiceModel): Long
    @Delete suspend fun deleteModel(m: ServiceModel)
    @Query("SELECT * FROM service_models WHERE name = :name COLLATE NOCASE LIMIT 1") suspend fun modelByName(name: String): ServiceModel?

    // employees
    @Query("SELECT * FROM service_employees ORDER BY name COLLATE NOCASE") fun employees(): Flow<List<ServiceEmployee>>
    @Query("SELECT * FROM service_employees") suspend fun employeesOnce(): List<ServiceEmployee>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertEmployee(e: ServiceEmployee): Long
    @Delete suspend fun deleteEmployee(e: ServiceEmployee)

    // jobs master
    @Query("SELECT * FROM service_jobs_master ORDER BY name COLLATE NOCASE") fun jobs(): Flow<List<ServiceJobMaster>>
    @Query("SELECT * FROM service_jobs_master") suspend fun jobsOnce(): List<ServiceJobMaster>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertJob(j: ServiceJobMaster): Long
    @Delete suspend fun deleteJob(j: ServiceJobMaster)

    // job cards
    @Query("SELECT COUNT(*) FROM service_job_cards") suspend fun cardCount(): Int
    @Query("SELECT * FROM service_job_cards ORDER BY dateMillis DESC") fun cards(): Flow<List<ServiceJobCard>>
    @Query("SELECT * FROM service_job_cards WHERE id = :id LIMIT 1") suspend fun cardById(id: Long): ServiceJobCard?
    @Query("SELECT * FROM service_job_lines") fun allLines(): Flow<List<ServiceJobLine>>
    @Query("SELECT * FROM service_job_lines WHERE cardId = :id ORDER BY id") suspend fun linesFor(id: Long): List<ServiceJobLine>
    @Query("UPDATE service_job_cards SET status = :status WHERE id = :id") suspend fun setCardStatus(id: Long, status: String)

    @Insert suspend fun insertCard(c: ServiceJobCard): Long
    @Update suspend fun updateCard(c: ServiceJobCard)
    @Delete suspend fun deleteCard(c: ServiceJobCard)
    @Insert suspend fun insertLines(lines: List<ServiceJobLine>)
    @Update suspend fun updateLine(l: ServiceJobLine)
    @Query("DELETE FROM service_job_lines WHERE cardId = :id") suspend fun deleteLines(id: Long)

    @Transaction suspend fun save(c: ServiceJobCard, lines: List<ServiceJobLine>): Long {
        val id = insertCard(c)
        insertLines(lines.map { it.copy(id = 0, cardId = id) })
        return id
    }
    @Transaction suspend fun update(c: ServiceJobCard, lines: List<ServiceJobLine>) {
        updateCard(c); deleteLines(c.id); insertLines(lines.map { it.copy(id = 0, cardId = c.id) })
    }
    @Transaction suspend fun delete(c: ServiceJobCard) {
        deleteLines(c.id); deleteAttachmentsFor(c.id); deleteCard(c)
    }

    // attachments
    @Query("SELECT * FROM service_job_attachments WHERE cardId = :id") suspend fun attachmentsFor(id: Long): List<ServiceJobAttachment>
    @Insert suspend fun insertAttachments(a: List<ServiceJobAttachment>)
    @Query("DELETE FROM service_job_attachments WHERE cardId = :id") suspend fun deleteAttachmentsFor(id: Long)
}

/** Copies picked/captured files attached to a job card into app storage. */
object ServiceAttachmentStore {

    fun dir(context: Context): File = File(context.filesDir, "servicejobs").apply { mkdirs() }

    fun copyIn(context: Context, uri: Uri): ServiceJobAttachment? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val displayName = queryName(context, uri) ?: "file_${System.nanoTime()}"
        val ext = displayName.substringAfterLast('.', "").ifBlank { extFromMime(mime) }
        val target = File(dir(context), "job_${System.nanoTime()}" + if (ext.isNotBlank()) ".$ext" else "")
        return try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            ServiceJobAttachment(cardId = 0, path = target.absolutePath, name = displayName, mime = mime)
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    fun delete(attachment: ServiceJobAttachment) {
        runCatching { File(attachment.path).delete() }
    }

    fun uriFor(context: Context, attachment: ServiceJobAttachment): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.provider", File(attachment.path))

    /** Opens the attachment in a viewer app. Returns false if nothing can open it. */
    fun open(context: Context, attachment: ServiceJobAttachment): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(context, attachment), attachment.mime.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    private fun extFromMime(mime: String): String = when (mime) {
        "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"
        "application/pdf" -> "pdf"; else -> ""
    }

    private fun queryName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
}
