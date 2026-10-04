-- Advance payment requests whose payment demonstrably completed.
--
-- Every payment_requests row in the database read SUBMITTED, with transaction_ref and completed_at
-- null. Not a coincidence: executePayment set the status to SUBMITTED *after* it had already created
-- the debit transaction, and then never touched the request again, so SUBMITTED was the terminal state
-- for every payment request ever made. markCompleted, markProcessing and markRejected on the entity
-- had no callers at all. The code now calls markCompleted once the payable is settled; this repairs
-- the rows written before that.
--
-- Advanced only on the payable's evidence, which is the durable record: its status is PAID and it has
-- been paid at least the request's amount. Two of the three rows have a transaction_id pointing at a
-- va_movements row that no longer exists, so the transaction cannot be the test -- the payable can.
-- Anything without that evidence is left alone rather than guessed at.
--
-- completed_at is taken from the payable's updated_at, the moment it was settled, rather than now():
-- these completed in January 2026 and stamping them with the migration's run time would be a
-- falsehood that outlives the fix. transaction_ref is filled only where the movement still exists,
-- for the same reason -- the request_number happens to match the surviving movement's reference, but
-- inferring the other two from that pattern would be invention.
--
-- Re-runnable: only touches rows still sitting at SUBMITTED.
UPDATE payment_requests pr
SET status          = 'COMPLETED',
    completed_at    = p.updated_at,
    transaction_ref = COALESCE(
        pr.transaction_ref,
        (SELECT m.reference_number FROM va_movements m WHERE m.id = pr.transaction_id))
FROM payables p
WHERE pr.payable_id = p.id
  AND pr.status = 'SUBMITTED'
  AND p.status = 'PAID'
  AND COALESCE(p.paid_amount, 0) >= pr.amount;
