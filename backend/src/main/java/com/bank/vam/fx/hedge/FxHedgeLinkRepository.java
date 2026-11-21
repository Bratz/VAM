package com.bank.vam.fx.hedge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FxHedgeLinkRepository extends JpaRepository<FxHedgeLink, UUID> {

    List<FxHedgeLink> findByDealIdOrderByLinkedAtAsc(UUID dealId);

    List<FxHedgeLink> findByKindAndInvoiceIdIn(FxHedgeLink.Kind kind, Collection<UUID> invoiceIds);
}
