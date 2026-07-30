package com.billing.pos.data

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

/* ==================== Test master ==================== */

/** A lab test that can be billed (e.g. "Lipid Profile"). Billing shows name + [price]. */
@Entity(tableName = "lab_tests")
data class LabTest(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val price: Double,
    val sampleType: String = "",
    val category: String = ""
)

/** One measurable parameter of a test, with its reference/normal value and an optional group. */
@Entity(tableName = "lab_evaluations")
data class LabEvaluation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val testId: Long,
    val name: String,
    val unit: String = "",
    val normalValue: String = "",
    /** Optional heading these evaluations are grouped under on the result print. */
    val groupName: String = "",
    val sortOrder: Int = 0,
    /** A bold section heading row (no result entered/printed). */
    val isHeading: Boolean = false,
    /** A page-break marker: everything after it prints on a new A4 page. */
    val isPageBreak: Boolean = false
)

/* ---- Reusable masters: groups + evaluations ---- */

@Entity(tableName = "lab_groups")
data class LabGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

/** A reusable evaluation in the master; may belong to a group. */
@Entity(tableName = "lab_eval_master")
data class LabEvalMaster(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val unit: String = "",
    val normalValue: String = "",
    val groupName: String = ""
)

/** A reusable bold heading. */
@Entity(tableName = "lab_headings")
data class LabHeading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

/** A reusable referring doctor. */
@Entity(tableName = "lab_doctors")
data class LabDoctor(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

@Dao
interface LabMasterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertGroup(g: LabGroup): Long
    @Delete suspend fun deleteGroup(g: LabGroup)
    @Query("SELECT * FROM lab_groups ORDER BY name COLLATE NOCASE") fun observeGroups(): Flow<List<LabGroup>>
    @Query("SELECT * FROM lab_groups") suspend fun allGroups(): List<LabGroup>
    @Query("SELECT * FROM lab_groups WHERE name = :name COLLATE NOCASE LIMIT 1") suspend fun groupByName(name: String): LabGroup?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEval(e: LabEvalMaster): Long
    @Delete suspend fun deleteEval(e: LabEvalMaster)
    @Update suspend fun updateEval(e: LabEvalMaster)
    @Query("SELECT * FROM lab_eval_master ORDER BY name COLLATE NOCASE") fun observeEvals(): Flow<List<LabEvalMaster>>
    @Query("SELECT * FROM lab_eval_master") suspend fun allEvals(): List<LabEvalMaster>
    @Query("SELECT * FROM lab_eval_master WHERE name = :name COLLATE NOCASE LIMIT 1") suspend fun evalByName(name: String): LabEvalMaster?
    @Query("SELECT * FROM lab_eval_master WHERE groupName = :group COLLATE NOCASE ORDER BY name COLLATE NOCASE") suspend fun evalsInGroup(group: String): List<LabEvalMaster>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHeading(h: LabHeading): Long
    @Delete suspend fun deleteHeading(h: LabHeading)
    @Query("SELECT * FROM lab_headings ORDER BY name COLLATE NOCASE") fun observeHeadings(): Flow<List<LabHeading>>
    @Query("SELECT * FROM lab_headings") suspend fun allHeadings(): List<LabHeading>
    @Query("SELECT * FROM lab_headings WHERE name = :name COLLATE NOCASE LIMIT 1") suspend fun headingByName(name: String): LabHeading?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertDoctor(d: LabDoctor): Long
    @Delete suspend fun deleteDoctor(d: LabDoctor)
    @Query("SELECT * FROM lab_doctors ORDER BY name COLLATE NOCASE") fun observeDoctors(): Flow<List<LabDoctor>>
    @Query("SELECT * FROM lab_doctors") suspend fun allDoctors(): List<LabDoctor>
    @Query("SELECT * FROM lab_doctors WHERE name = :name COLLATE NOCASE LIMIT 1") suspend fun doctorByName(name: String): LabDoctor?
}

data class LabTestWithEvaluations(val test: LabTest, val evaluations: List<LabEvaluation>)

@Dao
interface LabTestDao {
    @Query("SELECT COUNT(*) FROM lab_tests") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTest(t: LabTest): Long
    @Update suspend fun updateTest(t: LabTest)
    @Delete suspend fun deleteTest(t: LabTest)
    @Insert suspend fun insertEvaluations(e: List<LabEvaluation>)
    @Query("DELETE FROM lab_evaluations WHERE testId = :testId") suspend fun deleteEvaluations(testId: Long)

    @Transaction
    suspend fun saveTest(t: LabTest, evals: List<LabEvaluation>): Long {
        val id = insertTest(t)
        deleteEvaluations(id)
        insertEvaluations(evals.mapIndexed { i, e -> e.copy(id = 0, testId = id, sortOrder = i) })
        return id
    }
    @Transaction
    suspend fun delete(t: LabTest) { deleteEvaluations(t.id); deleteTest(t) }

    @Query("SELECT * FROM lab_tests ORDER BY name COLLATE NOCASE") fun observeTests(): Flow<List<LabTest>>
    @Query("SELECT * FROM lab_tests") suspend fun allTests(): List<LabTest>
    @Query("SELECT * FROM lab_tests WHERE id = :id LIMIT 1") suspend fun testById(id: Long): LabTest?
    @Query("SELECT * FROM lab_evaluations WHERE testId = :testId ORDER BY sortOrder, id") suspend fun evaluationsFor(testId: Long): List<LabEvaluation>
    @Query("SELECT * FROM lab_evaluations") suspend fun allEvaluations(): List<LabEvaluation>
}

/* ==================== Patients ==================== */

@Entity(tableName = "patients")
data class Patient(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val age: String = "",
    val gender: String = "",
    val phone: String = "",
    val address: String = "",
    val referredBy: String = ""
)

@Dao
interface PatientDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(p: Patient): Long
    @Update suspend fun update(p: Patient)
    @Delete suspend fun delete(p: Patient)
    @Query("SELECT * FROM patients ORDER BY name COLLATE NOCASE") fun observeAll(): Flow<List<Patient>>
    @Query("SELECT * FROM patients") suspend fun all(): List<Patient>
    @Query("SELECT * FROM patients WHERE id = :id LIMIT 1") suspend fun byId(id: Long): Patient?
}

/* ==================== Lab bill + results ==================== */

@Entity(tableName = "lab_bills")
data class LabBill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billNo: String,
    val dateMillis: Long,
    val patientId: Long,
    val patientName: String,
    val patientPhone: String = "",
    val age: String = "",
    val gender: String = "",
    val referredBy: String = "",
    val subTotal: Double,
    val discount: Double,
    val grandTotal: Double,
    val remarks: String = "",
    val resultEntered: Boolean = false,
    val resultDateMillis: Long = 0,
    /** Cash / UPI / Card / Credit. Not printed on the result. */
    val paymentMethod: String = "Cash",
    /** Amount actually collected (equals grandTotal for non-credit). */
    val paidAmount: Double = 0.0
)

@Entity(tableName = "lab_bill_tests")
data class LabBillTest(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billId: Long,
    val testId: Long,
    val testName: String,
    val price: Double
)

/** One filled-in result value for an evaluation on a given bill. */
@Entity(tableName = "lab_results")
data class LabResultValue(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billId: Long,
    val testId: Long,
    val testName: String,
    val evaluationId: Long,
    val evaluationName: String,
    val groupName: String = "",
    val unit: String = "",
    val normalValue: String = "",
    val result: String = "",
    val sortOrder: Int = 0,
    val isHeading: Boolean = false,
    val isPageBreak: Boolean = false
)

data class LabBillWithTests(val bill: LabBill, val tests: List<LabBillTest>)

/** A payment collected against a lab bill after billing (balance / partial receipts). */
@Entity(tableName = "lab_receipts")
data class LabReceipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val labBillId: Long,
    val billNo: String,
    val patientName: String,
    val dateMillis: Long,
    val amount: Double,
    val mode: String = "Cash"
)

data class BillIdSum(val id: Long, val total: Double)

@Dao
interface LabReceiptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(r: LabReceipt): Long
    @Delete suspend fun delete(r: LabReceipt)
    @Query("SELECT * FROM lab_receipts ORDER BY dateMillis DESC") fun observeAll(): Flow<List<LabReceipt>>
    @Query("SELECT * FROM lab_receipts") suspend fun all(): List<LabReceipt>
    @Query("SELECT COALESCE(SUM(amount),0) FROM lab_receipts WHERE labBillId = :billId") suspend fun sumForBill(billId: Long): Double
    @Query("SELECT labBillId AS id, COALESCE(SUM(amount),0) AS total FROM lab_receipts GROUP BY labBillId")
    fun observeSumByBill(): Flow<List<BillIdSum>>
}

@Dao
interface LabBillDao {
    @Query("SELECT COUNT(*) FROM lab_bills") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertBill(b: LabBill): Long
    @Update suspend fun updateBill(b: LabBill)
    @Insert suspend fun insertTests(t: List<LabBillTest>)
    @Query("DELETE FROM lab_bill_tests WHERE billId = :billId") suspend fun deleteTests(billId: Long)
    @Delete suspend fun deleteBill(b: LabBill)
    @Query("DELETE FROM lab_results WHERE billId = :billId") suspend fun deleteResults(billId: Long)
    @Insert suspend fun insertResults(r: List<LabResultValue>)

    @Transaction
    suspend fun saveBill(b: LabBill, tests: List<LabBillTest>): Long {
        val id = insertBill(b)
        deleteTests(id)
        insertTests(tests.map { it.copy(id = 0, billId = id) })
        return id
    }
    @Transaction
    suspend fun delete(b: LabBill) { deleteTests(b.id); deleteResults(b.id); deleteBill(b) }

    /** Replaces all result rows for a bill and marks it as result-entered. */
    @Transaction
    suspend fun saveResults(bill: LabBill, results: List<LabResultValue>) {
        deleteResults(bill.id)
        insertResults(results.map { it.copy(id = 0, billId = bill.id) })
        updateBill(bill)
    }

    @Query("SELECT * FROM lab_bills ORDER BY dateMillis DESC") fun observeBills(): Flow<List<LabBill>>
    @Query("SELECT * FROM lab_bills") suspend fun allBills(): List<LabBill>
    @Query("SELECT * FROM lab_bills WHERE id = :id LIMIT 1") suspend fun billById(id: Long): LabBill?
    @Query("SELECT * FROM lab_bill_tests WHERE billId = :billId") suspend fun testsFor(billId: Long): List<LabBillTest>
    @Query("SELECT * FROM lab_bill_tests") suspend fun allBillTests(): List<LabBillTest>
    @Query("SELECT * FROM lab_results WHERE billId = :billId ORDER BY sortOrder, id") suspend fun resultsFor(billId: Long): List<LabResultValue>
    @Query("SELECT * FROM lab_results") suspend fun allResults(): List<LabResultValue>
}
