package com.bank.vam.service.treasury.refresh;

import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.BalanceDataSource;
import com.bank.vam.repository.PhysicalAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Refreshes a shadow's balance from its linked {@link PhysicalAccount}.
 * Used for {@link BalanceDataSource#CORE_BANKING} shadows (CBS-integrated).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoreBankingAdapter implements ShadowBalanceAdapter {

    private final PhysicalAccountRepository physicalAccountRepository;

    @Override
    public BalanceDataSource source() {
        return BalanceDataSource.CORE_BANKING;
    }

    @Override
    public RefreshResult fetch(VirtualAccount shadow) {
        if (shadow.getLinkedPhysicalAccountId() == null) {
            return RefreshResult.fail("Shadow has no linked physical account");
        }
        return physicalAccountRepository.findById(shadow.getLinkedPhysicalAccountId())
                .map(pa -> RefreshResult.ok(pa.getCurrentBalance(),
                                            pa.getAvailableBalance(),
                                            LocalDateTime.now()))
                .orElseGet(() -> {
                    log.warn("Linked physical account not found for shadow {}", shadow.getVaNumber());
                    return RefreshResult.fail("Linked physical account not found");
                });
    }
}
