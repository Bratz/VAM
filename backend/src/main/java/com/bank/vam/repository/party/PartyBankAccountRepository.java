package com.bank.vam.repository.party;

import com.bank.vam.entity.party.PartyBankAccount;
import com.bank.vam.entity.party.PartyBankAccount.AccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PartyBankAccountRepository extends JpaRepository<PartyBankAccount, UUID> {

    List<PartyBankAccount> findByPartyId(UUID partyId);

    List<PartyBankAccount> findByPartyIdAndStatus(UUID partyId, AccountStatus status);

    Optional<PartyBankAccount> findByPartyIdAndIsPrimaryTrue(UUID partyId);

    Optional<PartyBankAccount> findByIban(String iban);

    Optional<PartyBankAccount> findByAccountNumber(String accountNumber);

    long countByPartyId(UUID partyId);

    @Modifying
    @Query("UPDATE PartyBankAccount pba SET pba.isPrimary = false WHERE pba.party.id = :partyId AND pba.id != :accountId")
    void clearPrimaryExcept(@Param("partyId") UUID partyId, @Param("accountId") UUID accountId);

    @Query("SELECT pba FROM PartyBankAccount pba WHERE pba.party.id = :partyId AND pba.isVerified = false")
    List<PartyBankAccount> findUnverifiedByPartyId(@Param("partyId") UUID partyId);
}
