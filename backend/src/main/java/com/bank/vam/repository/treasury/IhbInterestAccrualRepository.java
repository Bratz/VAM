package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.IhbInterestAccrual;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface IhbInterestAccrualRepository extends JpaRepository<IhbInterestAccrual, UUID> {

    List<IhbInterestAccrual> findByVaIdAndAccrualDateBetweenOrderByAccrualDateAscCreatedAtAsc(
        UUID vaId, LocalDate from, LocalDate to);
}
