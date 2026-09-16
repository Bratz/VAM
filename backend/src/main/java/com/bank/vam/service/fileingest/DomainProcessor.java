package com.bank.vam.service.fileingest;

import java.util.List;
import java.util.UUID;

/** What every domain's live-processing stage looks like: take the rows already staged and
 * reconciled, post each READY one to the real domain service, and mark it PROCESSED or FAILED
 * without blocking its siblings. */
public interface DomainProcessor {

    void process(UUID ingestJobId, List<TransformedRow> rows);
}
