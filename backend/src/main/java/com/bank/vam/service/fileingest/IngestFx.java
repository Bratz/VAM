package com.bank.vam.service.fileingest;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.fileingest.IngestDomain;
import com.bank.vam.entity.fileingest.IngestJob;
import com.bank.vam.entity.fileingest.IngestStage;
import com.bank.vam.entity.fileingest.RowStatus;
import com.bank.vam.entity.fileingest.StagedTransaction;
import com.bank.vam.fx.deal.FxPaymentService;
import com.bank.vam.fx.deal.FxPaymentService.BatchFx;
import com.bank.vam.repository.fileingest.IngestJobRepository;
import com.bank.vam.repository.fileingest.StagedTransactionRepository;
import com.bank.vam.service.VirtualAccountService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * FX for a payments or payables file: planned over the whole file before any line is paid
 * ({@link FxPaymentService#prepareBatch}): per paying entity and currency pair the file's total is covered by the deals
 * that fit, one deal is booked for the rest within the streaming limit, and lines draw in file order. A line the deals
 * cannot cover is held (AWAITING_FX) with its transformed data, and paid by {@link #release} once treasury has booked
 * FX for it.
 */
@Component
public class IngestFx {

    private static final BatchFx AT_MARKET = new BatchFx(FxPaymentService.Instruction.AT_MARKET, null);

    private final VirtualAccountService accounts;
    private final FxPaymentService fx;
    private final ObjectMapper json;
    private final StagedTransactionRepository rows;
    private final IngestJobRepository jobs;
    private final TimelineEventPublisher timeline;
    private final PaymentsProcessor payments;
    private final PayablesProcessor payables;

    public IngestFx(VirtualAccountService accounts, FxPaymentService fx, ObjectMapper json, StagedTransactionRepository rows,
                    IngestJobRepository jobs, TimelineEventPublisher timeline,
                    @Lazy PaymentsProcessor payments, @Lazy PayablesProcessor payables) {
        this.accounts = accounts;
        this.fx = fx;
        this.json = json;
        this.rows = rows;
        this.jobs = jobs;
        this.timeline = timeline;
        this.payments = payments;
        this.payables = payables;
    }

    /** How to convert each ready line, keyed by row id; lines whose account cannot be found are left to fail on their own. */
    Map<UUID, BatchFx> plan(List<StagedTransaction> ready, Map<Integer, TransformedRow> byRowNumber) {
        List<StagedTransaction> planned = new ArrayList<>();
        List<FxPaymentService.BatchRow> batch = new ArrayList<>();
        for (StagedTransaction staged : ready) {
            TransformedRow row = byRowNumber.get(staged.getSourceRowNumber());
            if (row == null) {
                continue;
            }
            try {
                VirtualAccount source = accounts.getByViban(row.viban());
                planned.add(staged);
                batch.add(new FxPaymentService.BatchRow(source, row.currency(), row.amount()));
            } catch (RuntimeException unknownAccount) {
                // the line fails with its own reason when it is paid
            }
        }
        List<BatchFx> out = fx.prepareBatch(batch);
        Map<UUID, BatchFx> byId = new HashMap<>();
        for (int i = 0; i < planned.size(); i++) {
            byId.put(planned.get(i).getId(), out.get(i));
        }
        return byId;
    }

    BatchFx of(Map<UUID, BatchFx> plan, StagedTransaction staged) {
        return plan.getOrDefault(staged.getId(), AT_MARKET);
    }

    /** The line waits for FX, keeping what is needed to pay it. */
    void hold(StagedTransaction staged, TransformedRow row, String reason) {
        try {
            staged.setHeldRow(json.writeValueAsString(row));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not keep line " + staged.getSourceRowNumber() + " for release", e);
        }
        staged.setStatus(RowStatus.AWAITING_FX);
        staged.setReason(reason);
    }

    /**
     * Pays the job's lines waiting for FX, planning their FX again: with a deal now booked they draw on it; without
     * one they wait again, with the reason. Returns how many are still waiting.
     */
    public long release(UUID jobId) {
        IngestJob job = jobs.findById(jobId).orElseThrow(() -> new NoSuchElementException("No ingest job " + jobId));
        List<StagedTransaction> held = rows.findByIngestJobIdAndStatus(jobId, RowStatus.AWAITING_FX);
        if (held.isEmpty()) {
            return 0;
        }
        List<TransformedRow> lines = new ArrayList<>();
        for (StagedTransaction s : held) {
            try {
                lines.add(json.readValue(s.getHeldRow(), TransformedRow.class));
            } catch (JsonProcessingException | IllegalArgumentException e) {
                s.setStatus(RowStatus.FAILED);
                s.setReason("The held line could not be read back: " + e.getMessage());
                rows.save(s);
                continue;
            }
            s.setStatus(RowStatus.READY);
            s.setHeldRow(null);
            rows.save(s);
        }
        if (job.getDomain() == IngestDomain.PAYMENTS) {
            payments.process(jobId, lines);
        } else if (job.getDomain() == IngestDomain.PAYABLES) {
            payables.process(jobId, lines);
        }
        long still = rows.findByIngestJobIdAndStatus(jobId, RowStatus.AWAITING_FX).size();
        long paid = held.size() - still;
        timeline.emit(jobId, IngestStage.DONE, "COMPLETE", "FX released: " + paid + " line(s) processed, " + still + " still waiting for FX.");
        return still;
    }
}
