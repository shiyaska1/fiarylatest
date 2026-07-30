package com.billing.pos.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AttachmentType { IMAGE, VIDEO, AUDIO, DOCUMENT, LOCATION }

/** A personal diary entry with optional reminder. */
@Entity(tableName = "diary_entries")
data class DiaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val remarks: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Diary category, from the diary_types master. 0 = none. */
    val typeId: Long = 0,
    /** Optional customer this entry is about. 0/blank = personal note. */
    val customerId: Long = 0,
    val customerName: String = "",
    val reminderEnabled: Boolean = false,
    /** For one-time reminders: exact date+time. For daily: the time-of-day used. */
    val reminderAt: Long = 0,
    val reminderDaily: Boolean = false,
    // --- Text formatting. Color 0 = use the app's default text color. ---
    val titleSize: Int = 20,
    val titleColor: Int = 0,
    val titleBold: Boolean = true,
    val titleItalic: Boolean = false,
    val bodySize: Int = 15,
    val bodyColor: Int = 0,
    val bodyBold: Boolean = false,
    val bodyItalic: Boolean = false
)

/** A file attached to a diary entry (copied into app storage). */
@Entity(tableName = "diary_attachments")
data class DiaryAttachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    val path: String,
    val name: String,
    val mime: String,
    val type: AttachmentType
)

/** Kind of content block making up a diary entry's body. */
enum class BlockType { TEXT, IMAGE, VIDEO, AUDIO, DOCUMENT, LOCATION }

/**
 * One ordered block in a diary entry's body. A TEXT block holds typed text; media
 * blocks reference a file in app storage. LOCATION stores its maps URL in [text].
 */
@Entity(tableName = "diary_blocks")
data class DiaryBlock(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    val position: Int,
    val type: BlockType,
    val text: String = "",
    val path: String = "",
    val name: String = "",
    val mime: String = "",
    val durationMs: Long = 0
)
