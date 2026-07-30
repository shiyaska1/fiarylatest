package com.billing.pos.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class ServiceRepository(context: Context) {
    private val dao = AppDatabase.get(context).serviceDao()
    private val repo = Repository(context)

    val types: Flow<List<ServiceType>> = dao.types()
    val statuses: Flow<List<ServiceStatus>> = dao.statuses()
    val jobs: Flow<List<ServiceJobMaster>> = dao.jobs()
    val employees: Flow<List<ServiceEmployee>> = dao.employees()
    val models: Flow<List<ServiceModel>> = dao.models()
    val cards: Flow<List<ServiceJobCard>> = dao.cards()
    val allLines: Flow<List<ServiceJobLine>> = dao.allLines()

    /** Seeds the status master the first time the module is opened. */
    suspend fun ensureDefaults() {
        if (dao.statusCount() == 0) {
            listOf("Pending", "Partial Complete", "Completed", "Rejected")
                .forEach { dao.upsertStatus(ServiceStatus(name = it)) }
        }
    }

    // masters
    suspend fun saveType(t: ServiceType): Long =
        if (t.name.isBlank()) 0 else dao.upsertType(t.copy(name = t.name.trim()))
    suspend fun deleteType(t: ServiceType) = dao.deleteType(t)
    suspend fun saveStatus(s: ServiceStatus) { if (s.name.isNotBlank()) dao.upsertStatus(s.copy(name = s.name.trim())) }
    suspend fun deleteStatus(s: ServiceStatus) = dao.deleteStatus(s)
    suspend fun saveJob(j: ServiceJobMaster) { if (j.name.isNotBlank()) dao.upsertJob(j.copy(name = j.name.trim())) }
    suspend fun deleteJob(j: ServiceJobMaster) = dao.deleteJob(j)
    /** Upserts a model by name (reuses an existing row on a case-insensitive match). */
    suspend fun saveModel(name: String): Long {
        val n = name.trim()
        if (n.isBlank()) return 0
        dao.modelByName(n)?.let { return it.id }
        return dao.upsertModel(ServiceModel(name = n))
    }
    suspend fun deleteModel(m: ServiceModel) = dao.deleteModel(m)

    suspend fun saveEmployee(e: ServiceEmployee): Long =
        if (e.name.isBlank()) 0 else dao.upsertEmployee(e.copy(name = e.name.trim(), phone = e.phone.trim()))
    suspend fun deleteEmployee(e: ServiceEmployee) = dao.deleteEmployee(e)

    // job cards
    suspend fun nextJobNo(): String = "JC-" + (dao.cardCount() + 1).toString().padStart(4, '0')
    suspend fun cardById(id: Long): ServiceJobCard? = dao.cardById(id)
    suspend fun linesFor(id: Long): List<ServiceJobLine> = dao.linesFor(id)
    suspend fun saveCard(c: ServiceJobCard, lines: List<ServiceJobLine>): Long {
        val id = dao.save(c, lines)
        postAdvanceReceipt(c, previousAdvance = 0.0)
        return id
    }

    suspend fun updateCard(c: ServiceJobCard, lines: List<ServiceJobLine>) {
        val old = dao.cardById(c.id)
        dao.update(c, lines)
        postAdvanceReceipt(c, previousAdvance = old?.advance ?: 0.0)
    }

    /**
     * Advance money is real money: any increase posts a receipt automatically (into the
     * customer's ledger, tagged with the job no). Only the increase — editing a card
     * without touching the advance posts nothing, so nothing is ever double-counted.
     */
    private suspend fun postAdvanceReceipt(c: ServiceJobCard, previousAdvance: Double) {
        val delta = c.advance - previousAdvance
        if (delta > 0.005 && c.customerName.isNotBlank()) {
            repo.addStandaloneReceipt(c.customerName, delta, PayMode.CASH, refNo = c.jobNo)
        }
    }
    suspend fun setCardStatus(id: Long, status: String) = dao.setCardStatus(id, status)

    suspend fun setCardExpected(id: Long, millis: Long) {
        dao.cardById(id)?.let { dao.updateCard(it.copy(expectedMillis = millis)) }
    }

    /** Updates one job on a card, then refreshes the card's money and status. */
    suspend fun updateLine(l: ServiceJobLine) {
        dao.updateLine(l)
        refreshCard(l.cardId)
    }

    /**
     * Rejected jobs don't count toward the total, and a card whose jobs are all
     * completed or rejected is itself Completed. Reopening a job undoes that.
     */
    private suspend fun refreshCard(cardId: Long) {
        val c = dao.cardById(cardId) ?: return
        val lines = dao.linesFor(cardId)
        val jobsTotal = lines.filter { !it.status.equals("Rejected", true) }.sumOf { it.price }
        val allDone = lines.isNotEmpty() &&
            lines.all { it.status.equals("Completed", true) || it.status.equals("Rejected", true) }
        val status = when {
            allDone -> "Completed"
            c.status.equals("Completed", true) -> "Pending"
            else -> c.status
        }
        dao.updateCard(c.copy(jobsTotal = jobsTotal, grandTotal = jobsTotal + c.otherCharges, status = status))
    }

    suspend fun deleteCard(c: ServiceJobCard) {
        dao.attachmentsFor(c.id).forEach { ServiceAttachmentStore.delete(it) }
        dao.delete(c)
    }

    // attachments — rewritten wholesale after a save, when the card id is known
    suspend fun attachmentsFor(cardId: Long): List<ServiceJobAttachment> = dao.attachmentsFor(cardId)
    suspend fun replaceAttachments(cardId: Long, list: List<ServiceJobAttachment>) {
        val keep = list.map { it.path }.toSet()
        dao.attachmentsFor(cardId).filter { it.path !in keep }.forEach { ServiceAttachmentStore.delete(it) }
        dao.deleteAttachmentsFor(cardId)
        dao.insertAttachments(list.map { it.copy(id = 0, cardId = cardId) })
    }

    // customers (shared master)
    val customers: Flow<List<Customer>> = repo.customers
    suspend fun addCustomer(name: String, phone: String, address: String, type: String = "General"): Customer {
        val id = repo.addCustomer(name, phone, address, customerType = type)
        return Customer(id = id, name = name.trim(), phone = phone.trim(), address = address.trim(), customerType = type.trim().ifBlank { "General" })
    }

    /**
     * A mobile number typed against the default cash customer identifies a real person:
     * reuse the customer who already has that number, else create "Cash Customer - <no>".
     */
    suspend fun resolveCashCustomer(phone: String): Customer {
        repo.customerByPhone(phone)?.let { return it }
        return repo.addCustomerReturning("Cash Customer - $phone", phone)
    }
}
