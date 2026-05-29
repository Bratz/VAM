package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.BankHoliday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.UUID;

@Repository
public interface BankHolidayRepository extends JpaRepository<BankHoliday, UUID> {

    boolean existsByCalendarCodeAndHolidayDate(String calendarCode, LocalDate date);
}
