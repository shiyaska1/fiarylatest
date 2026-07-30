package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// ---------- masters ----------
@Entity(tableName = "coach_courses")
data class Course(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val durationMonths: Int = 0,
    val admissionFee: Double = 0.0,
    val totalFee: Double = 0.0,
    val description: String = ""
)

@Entity(tableName = "coach_classes")
data class CoachingClass(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

@Entity(tableName = "coach_subjects")
data class CoachSubject(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val classId: Long = 0,
    val staffId: Long = 0
)

@Entity(tableName = "coach_staff")
data class CoachStaff(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val designation: String = "",
    val address: String = ""
)

// ---------- student + fees ----------
@Entity(tableName = "coach_students")
data class CoachStudent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val gender: String = "",
    val guardian: String = "",
    val address: String = "",
    val photoPath: String = "",
    val classId: Long = 0,
    val joinDateMillis: Long,
    val admissionFee: Double = 0.0,
    val totalFee: Double = 0.0,
    val installments: Int = 1,
    val active: Boolean = true
)

/** Courses a student is enrolled in (multiple allowed). */
@Entity(tableName = "coach_student_courses")
data class StudentCourse(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val studentId: Long,
    val courseId: Long,
    val courseName: String = ""
)

@Entity(tableName = "coach_fees")
data class CoachFee(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val studentId: Long,
    val dueDateMillis: Long,
    val amount: Double,
    val paidAmount: Double = 0.0,
    val paidDateMillis: Long = 0,
    val kind: String = "Installment",
    val note: String = ""
)

// ---------- enquiry / leads ----------
@Entity(tableName = "coach_enquiries")
data class Enquiry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val courseInterest: String = "",
    val dateMillis: Long,
    val source: String = "",
    /** New, Follow-up, Converted, Lost. */
    val status: String = "New",
    val notes: String = "",
    val nextFollowupMillis: Long = 0,
    val convertedStudentId: Long = 0
)

@Entity(tableName = "coach_enquiry_followups")
data class EnquiryFollowup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val enquiryId: Long,
    val dateMillis: Long,
    val note: String,
    val status: String = ""
)

// ---------- attendance (Stage 2 UI; schema ready now) ----------
@Entity(tableName = "coach_attendance")
data class CoachAttendance(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateMillis: Long,
    val classId: Long,
    val subjectId: Long,
    val staffId: Long = 0,
    val studentId: Long,
    val present: Boolean = true
)

@Dao
interface CoachingDao {
    // courses
    @Query("SELECT * FROM coach_courses ORDER BY name COLLATE NOCASE") fun courses(): Flow<List<Course>>
    @Query("SELECT * FROM coach_courses") suspend fun coursesOnce(): List<Course>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertCourse(c: Course): Long
    @Delete suspend fun deleteCourse(c: Course)

    // classes
    @Query("SELECT * FROM coach_classes ORDER BY name COLLATE NOCASE") fun classes(): Flow<List<CoachingClass>>
    @Query("SELECT * FROM coach_classes") suspend fun classesOnce(): List<CoachingClass>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertClass(c: CoachingClass): Long
    @Delete suspend fun deleteClass(c: CoachingClass)

    // subjects
    @Query("SELECT * FROM coach_subjects ORDER BY name COLLATE NOCASE") fun subjects(): Flow<List<CoachSubject>>
    @Query("SELECT * FROM coach_subjects") suspend fun subjectsOnce(): List<CoachSubject>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSubject(s: CoachSubject): Long
    @Delete suspend fun deleteSubject(s: CoachSubject)

    // staff
    @Query("SELECT * FROM coach_staff ORDER BY name COLLATE NOCASE") fun staff(): Flow<List<CoachStaff>>
    @Query("SELECT * FROM coach_staff") suspend fun staffOnce(): List<CoachStaff>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertStaff(s: CoachStaff): Long
    @Delete suspend fun deleteStaff(s: CoachStaff)

    // students
    @Query("SELECT * FROM coach_students ORDER BY name COLLATE NOCASE") fun students(): Flow<List<CoachStudent>>
    @Query("SELECT * FROM coach_students WHERE id = :id") suspend fun studentById(id: Long): CoachStudent?
    @Query("SELECT * FROM coach_students WHERE classId = :c ORDER BY name COLLATE NOCASE") suspend fun studentsInClass(c: Long): List<CoachStudent>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertStudent(s: CoachStudent): Long
    @Update suspend fun updateStudent(s: CoachStudent)
    @Delete suspend fun deleteStudent(s: CoachStudent)

    // enrollments
    @Query("SELECT * FROM coach_student_courses WHERE studentId = :sid") suspend fun coursesFor(sid: Long): List<StudentCourse>
    @Insert suspend fun addEnrollment(e: StudentCourse)
    @Query("DELETE FROM coach_student_courses WHERE studentId = :sid") suspend fun clearEnrollments(sid: Long)

    // fees
    @Query("SELECT * FROM coach_fees ORDER BY dueDateMillis") fun allFees(): Flow<List<CoachFee>>
    @Query("SELECT * FROM coach_fees WHERE studentId = :sid ORDER BY dueDateMillis") suspend fun feesFor(sid: Long): List<CoachFee>
    @Insert suspend fun insertFees(f: List<CoachFee>)
    @Update suspend fun updateFee(f: CoachFee)
    @Query("DELETE FROM coach_fees WHERE studentId = :sid") suspend fun clearFees(sid: Long)

    // enquiry
    @Query("SELECT * FROM coach_enquiries ORDER BY dateMillis DESC") fun enquiries(): Flow<List<Enquiry>>
    @Query("SELECT * FROM coach_enquiries WHERE id = :id") suspend fun enquiryById(id: Long): Enquiry?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertEnquiry(e: Enquiry): Long
    @Update suspend fun updateEnquiry(e: Enquiry)
    @Delete suspend fun deleteEnquiry(e: Enquiry)
    @Query("SELECT * FROM coach_enquiry_followups WHERE enquiryId = :id ORDER BY dateMillis DESC") fun followups(id: Long): Flow<List<EnquiryFollowup>>
    @Insert suspend fun addFollowup(f: EnquiryFollowup)

    // attendance
    @Query("SELECT * FROM coach_attendance") fun attendance(): Flow<List<CoachAttendance>>
    @Query("SELECT * FROM coach_attendance WHERE classId=:c AND subjectId=:s AND dateMillis BETWEEN :from AND :to") suspend fun attendanceFor(c: Long, s: Long, from: Long, to: Long): List<CoachAttendance>
    @Insert suspend fun insertAttendance(a: List<CoachAttendance>)
    @Query("DELETE FROM coach_attendance WHERE classId=:c AND subjectId=:s AND dateMillis BETWEEN :from AND :to") suspend fun clearAttendance(c: Long, s: Long, from: Long, to: Long)
}
