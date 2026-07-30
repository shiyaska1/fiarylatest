package com.billing.pos.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

/** Data access + fee logic for the Gym module. Fee collection also posts a receipt into accounts. */
class GymRepository(context: Context) {
    private val memberDao = AppDatabase.get(context).gymMemberDao()
    private val feeDao = AppDatabase.get(context).gymFeeDao()
    private val repo = Repository(context)

    val members: Flow<List<GymMember>> = memberDao.observeAll()
    val allFees: Flow<List<GymFee>> = feeDao.observeAll()

    suspend fun memberById(id: Long): GymMember? = memberDao.byId(id)
    fun feesForMember(id: Long): Flow<List<GymFee>> = feeDao.observeForMember(id)
    suspend fun feesOnce(id: Long): List<GymFee> = feeDao.forMember(id)

    /** Saves a member and, for a NEW member, generates the admission fee + installment schedule. */
    suspend fun saveMember(m: GymMember): Long {
        if (m.id != 0L) { memberDao.update(m); return m.id }
        val id = memberDao.insert(m)
        generateSchedule(id, m)
        return id
    }

    suspend fun deleteMember(m: GymMember) {
        feeDao.deleteForMember(m.id)
        memberDao.delete(m)
    }

    private suspend fun generateSchedule(memberId: Long, m: GymMember) {
        val fees = ArrayList<GymFee>()
        if (m.admissionFee > 0.0) {
            fees.add(GymFee(memberId = memberId, dueDateMillis = m.joinDateMillis, amount = m.admissionFee, kind = "Admission"))
        }
        val n = m.installments.coerceAtLeast(1)
        if (m.totalFee > 0.0) {
            val per = Math.round((m.totalFee / n) * 100.0) / 100.0
            for (i in 0 until n) {
                val due = Calendar.getInstance().apply { timeInMillis = m.joinDateMillis; add(Calendar.MONTH, i) }.timeInMillis
                val amt = if (i == n - 1) (m.totalFee - per * (n - 1)) else per
                fees.add(GymFee(memberId = memberId, dueDateMillis = due, amount = amt, kind = "Installment ${i + 1}/$n"))
            }
        }
        if (fees.isNotEmpty()) feeDao.insertAll(fees)
    }

    /** Records a payment against a fee line and posts a receipt so it appears in the accounts. */
    suspend fun collectFee(fee: GymFee, amount: Double, mode: PayMode, memberName: String) {
        val newPaid = (fee.paidAmount + amount).coerceAtMost(fee.amount + 0.0001)
        feeDao.update(fee.copy(paidAmount = newPaid, paidDateMillis = System.currentTimeMillis()))
        repo.addStandaloneReceipt("$memberName (Gym)", amount, mode)
    }
}
