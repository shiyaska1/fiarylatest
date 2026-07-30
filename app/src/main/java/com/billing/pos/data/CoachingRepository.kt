package com.billing.pos.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

class CoachingRepository(context: Context) {
    private val dao = AppDatabase.get(context).coachingDao()
    private val repo = Repository(context)

    val courses: Flow<List<Course>> = dao.courses()
    val classes: Flow<List<CoachingClass>> = dao.classes()
    val subjects: Flow<List<CoachSubject>> = dao.subjects()
    val staff: Flow<List<CoachStaff>> = dao.staff()
    val students: Flow<List<CoachStudent>> = dao.students()
    val allFees: Flow<List<CoachFee>> = dao.allFees()
    val enquiries: Flow<List<Enquiry>> = dao.enquiries()

    // masters
    suspend fun saveCourse(c: Course) { dao.upsertCourse(c) }
    suspend fun deleteCourse(c: Course) = dao.deleteCourse(c)
    suspend fun saveClass(c: CoachingClass) { dao.upsertClass(c) }
    suspend fun deleteClass(c: CoachingClass) = dao.deleteClass(c)
    suspend fun saveSubject(s: CoachSubject) { dao.upsertSubject(s) }
    suspend fun deleteSubject(s: CoachSubject) = dao.deleteSubject(s)
    suspend fun saveStaff(s: CoachStaff) { dao.upsertStaff(s) }
    suspend fun deleteStaff(s: CoachStaff) = dao.deleteStaff(s)
    suspend fun coursesOnce() = dao.coursesOnce()
    suspend fun classesOnce() = dao.classesOnce()
    suspend fun subjectsOnce() = dao.subjectsOnce()
    suspend fun staffOnce() = dao.staffOnce()

    // students
    suspend fun studentById(id: Long) = dao.studentById(id)
    suspend fun feesFor(id: Long) = dao.feesFor(id)
    suspend fun coursesFor(id: Long) = dao.coursesFor(id)

    suspend fun saveStudent(s: CoachStudent, courses: List<Course>): Long {
        val id: Long
        val isNew = s.id == 0L
        if (isNew) id = dao.insertStudent(s) else { dao.updateStudent(s); id = s.id }
        dao.clearEnrollments(id)
        courses.forEach { dao.addEnrollment(StudentCourse(studentId = id, courseId = it.id, courseName = it.name)) }
        if (isNew) generateSchedule(id, s)
        return id
    }

    private suspend fun generateSchedule(studentId: Long, s: CoachStudent) {
        val fees = ArrayList<CoachFee>()
        if (s.admissionFee > 0.0) fees.add(CoachFee(studentId = studentId, dueDateMillis = s.joinDateMillis, amount = s.admissionFee, kind = "Admission"))
        val n = s.installments.coerceAtLeast(1)
        if (s.totalFee > 0.0) {
            val per = Math.round((s.totalFee / n) * 100.0) / 100.0
            for (i in 0 until n) {
                val due = Calendar.getInstance().apply { timeInMillis = s.joinDateMillis; add(Calendar.MONTH, i) }.timeInMillis
                val amt = if (i == n - 1) (s.totalFee - per * (n - 1)) else per
                fees.add(CoachFee(studentId = studentId, dueDateMillis = due, amount = amt, kind = "Installment ${i + 1}/$n"))
            }
        }
        if (fees.isNotEmpty()) dao.insertFees(fees)
    }

    suspend fun collectFee(fee: CoachFee, amount: Double, mode: PayMode, studentName: String) {
        dao.updateFee(fee.copy(paidAmount = (fee.paidAmount + amount).coerceAtMost(fee.amount + 0.0001), paidDateMillis = System.currentTimeMillis()))
        repo.addStandaloneReceipt("$studentName (Coaching)", amount, mode)
    }

    // attendance
    val attendance: Flow<List<CoachAttendance>> = dao.attendance()
    suspend fun studentsInClass(classId: Long) = dao.studentsInClass(classId)
    suspend fun attendanceForDay(classId: Long, subjectId: Long, dateMillis: Long): List<CoachAttendance> {
        val ds = dayStart(dateMillis); val de = dayEnd(dateMillis)
        return dao.attendanceFor(classId, subjectId, ds, de)
    }
    suspend fun saveAttendance(classId: Long, subjectId: Long, staffId: Long, dateMillis: Long, marks: Map<Long, Boolean>) {
        val ds = dayStart(dateMillis); val de = dayEnd(dateMillis)
        dao.clearAttendance(classId, subjectId, ds, de)
        dao.insertAttendance(marks.map { CoachAttendance(dateMillis = dateMillis, classId = classId, subjectId = subjectId, staffId = staffId, studentId = it.key, present = it.value) })
    }
    private fun dayStart(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private fun dayEnd(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }.timeInMillis

    // enquiry
    suspend fun saveEnquiry(e: Enquiry): Long = dao.upsertEnquiry(e)
    suspend fun updateEnquiry(e: Enquiry) = dao.updateEnquiry(e)
    suspend fun enquiryById(id: Long) = dao.enquiryById(id)
    fun followups(id: Long): Flow<List<EnquiryFollowup>> = dao.followups(id)
    suspend fun addFollowup(enquiryId: Long, note: String, status: String) {
        dao.addFollowup(EnquiryFollowup(enquiryId = enquiryId, dateMillis = System.currentTimeMillis(), note = note, status = status))
        dao.enquiryById(enquiryId)?.let { dao.updateEnquiry(it.copy(status = status.ifBlank { it.status })) }
    }

    /** Convert a lead to a student admission (basic record; open the student to add fees/courses). */
    suspend fun convertEnquiry(e: Enquiry): Long {
        val sid = dao.insertStudent(CoachStudent(name = e.name, phone = e.phone, joinDateMillis = System.currentTimeMillis()))
        dao.updateEnquiry(e.copy(status = "Converted", convertedStudentId = sid))
        return sid
    }
}
