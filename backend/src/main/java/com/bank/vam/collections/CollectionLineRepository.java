package com.bank.vam.collections;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CollectionLineRepository extends JpaRepository<CollectionLine, UUID> {

    List<CollectionLine> findByRunIdOrderByCreatedAtAsc(UUID runId);

    List<CollectionLine> findByRunIdIn(Collection<UUID> runIds);

    List<CollectionLine> findByReceivableIdInAndStatusIn(Collection<UUID> receivableIds, Collection<CollectionLine.Status> statuses);

    List<CollectionLine> findByMandateIdInAndStatusIn(Collection<UUID> mandateIds, Collection<CollectionLine.Status> statuses);

    List<CollectionLine> findByStatusAndCollectionDateLessThanEqualOrderByCollectionDateAscCreatedAtAsc(CollectionLine.Status status, LocalDate date);

    List<CollectionLine> findByStatusAndHoldUntilLessThanEqualOrderByHoldUntilAscCreatedAtAsc(CollectionLine.Status status, LocalDate date);

    /** Direct debits sent and due by {@code asOf}. */
    default List<UUID> dueToSettle(LocalDate asOf) {
        return findByStatusAndCollectionDateLessThanEqualOrderByCollectionDateAscCreatedAtAsc(CollectionLine.Status.SENT, asOf)
                .stream().map(CollectionLine::getId).toList();
    }

    /** Settled debits whose hold ends by {@code asOf}. */
    default List<UUID> dueToRelease(LocalDate asOf) {
        return findByStatusAndHoldUntilLessThanEqualOrderByHoldUntilAscCreatedAtAsc(CollectionLine.Status.SETTLED, asOf)
                .stream().map(CollectionLine::getId).toList();
    }
}
