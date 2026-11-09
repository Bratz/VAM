package com.bank.vam.collections;

import com.bank.vam.collections.CustomerMandate.Period;
import com.bank.vam.collections.CustomerMandate.Source;
import com.bank.vam.collections.CustomerMandate.Status;
import com.bank.vam.collections.CustomerMandate.Type;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.party.Party;
import com.bank.vam.entity.party.PartyBankAccount;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.fx.exposure.ExposureCsvParser;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.party.PartyBankAccountRepository;
import com.bank.vam.repository.party.PartyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Customer direct-debit mandates: registered in the portal, by file, or by API. */
@Service
@RequiredArgsConstructor
public class MandateService {

    static final int MAX_ROWS = 5000;

    private final CustomerMandateRepository mandates;
    private final PartyRepository parties;
    private final PartyBankAccountRepository partyAccounts;
    private final VirtualAccountRepository accounts;

    /**
     * The customer is {@code customerPartyId}, or {@code customer} (party code or legal name) from a file;
     * the receiving account is {@code creditorVaId} or {@code creditorAccount} (account number). Blank debtor
     * account details default to the customer's primary bank account.
     */
    public record MandateRequest(UUID corporateId, UUID customerPartyId, String customer, String debtorName,
                                 String debtorIban, String debtorBic, UUID creditorVaId, String creditorAccount,
                                 String mandateReference, String schemeCode, String currency, Type mandateType,
                                 LocalDate validFrom, LocalDate validTo, LocalDate signedDate,
                                 BigDecimal maxPerCollection, Integer frequencyCount, Period frequencyPeriod,
                                 Source source) {
    }

    public record MandateUpdate(UUID creditorVaId, LocalDate validTo, BigDecimal maxPerCollection,
                                Integer frequencyCount, Period frequencyPeriod) {
    }

    public record MandateView(UUID id, String mandateReference, UUID customerPartyId, String customerName,
                              String debtorName, String debtorIban, String debtorBic, UUID creditorVaId,
                              String creditorAccount, String schemeCode, String currency, Type mandateType,
                              LocalDate validFrom, LocalDate validTo, LocalDate signedDate,
                              BigDecimal maxPerCollection, Integer frequencyCount, Period frequencyPeriod,
                              Status status, String statusReason, Source source, boolean expired) {
    }

    public record ImportResult(int imported, List<MandateView> mandates) {
    }

    public List<MandateView> list(UUID corporateId) {
        return views(mandates.findByCorporateIdOrderByCreatedAtDesc(corporateId));
    }

    @Transactional
    public MandateView register(MandateRequest request) {
        return views(List.of(mandates.save(build(request, Set.of())))).get(0);
    }

    /** The whole file is refused if any row is bad, so a corporate never has half a file of mandates loaded. */
    @Transactional
    public ImportResult importFile(UUID corporateId, Reader reader) throws IOException {
        BufferedReader in = new BufferedReader(reader);
        String headerLine = in.readLine();
        if (headerLine == null) throw new BusinessException("MANDATE_UPLOAD_INVALID", "The file is empty");
        List<String> header = ExposureCsvParser.split(headerLine.replace("﻿", "")).stream()
                .map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        for (String required : List.of("mandatereference", "customer", "creditoraccount", "schemecode", "validfrom")) {
            if (!header.contains(required)) {
                throw new BusinessException("MANDATE_UPLOAD_INVALID", "Header must include mandateReference, customer, "
                        + "creditorAccount, schemeCode, validFrom (missing " + required + ")");
            }
        }

        List<CustomerMandate> built = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> refsInFile = new HashSet<>();
        String line;
        int lineNo = 1;
        while ((line = in.readLine()) != null) {
            lineNo++;
            if (line.isBlank()) continue;
            if (built.size() + errors.size() >= MAX_ROWS) {
                errors.add("More than " + MAX_ROWS + " rows; split the file");
                break;
            }
            try {
                CustomerMandate m = build(row(corporateId, ExposureCsvParser.split(line), header), refsInFile);
                refsInFile.add(m.getMandateReference().toUpperCase(Locale.ROOT));
                built.add(m);
            } catch (BusinessException | ResourceNotFoundException | IllegalArgumentException | DateTimeParseException e) {
                if (errors.size() < 20) errors.add("Line " + lineNo + ": " + e.getMessage());
            }
        }
        if (built.isEmpty() && errors.isEmpty()) errors.add("The file has a header but no rows");
        if (!errors.isEmpty()) {
            throw new BusinessException("MANDATE_UPLOAD_INVALID", "Nothing was imported. " + String.join("; ", errors));
        }
        return new ImportResult(built.size(), views(mandates.saveAll(built)));
    }

    @Transactional
    public MandateView update(UUID id, MandateUpdate u) {
        CustomerMandate m = find(id);
        if (m.getStatus() == Status.CANCELLED || m.getStatus() == Status.USED) {
            throw new BusinessException("Mandate " + m.getMandateReference() + " is " + m.getStatus() + " and can no longer change");
        }
        if (u.creditorVaId() != null) m.setCreditorVaId(account(m.getCorporateId(), u.creditorVaId(), null).getId());
        m.setValidTo(u.validTo());
        m.setMaxPerCollection(u.maxPerCollection());
        m.setFrequencyCount(u.frequencyCount());
        m.setFrequencyPeriod(u.frequencyPeriod());
        checkLimits(m);
        return views(List.of(m)).get(0);
    }

    /** suspend: ACTIVE → SUSPENDED; resume: SUSPENDED → ACTIVE; cancel: anything not yet ended → CANCELLED. */
    @Transactional
    public MandateView changeStatus(UUID id, String action, String reason) {
        CustomerMandate m = find(id);
        Status from = m.getStatus();
        Status to = switch (action) {
            case "suspend" -> from == Status.ACTIVE ? Status.SUSPENDED : null;
            case "resume" -> from == Status.SUSPENDED ? Status.ACTIVE : null;
            case "cancel" -> from == Status.CANCELLED || from == Status.USED ? null : Status.CANCELLED;
            default -> throw new BusinessException("Unknown action: " + action);
        };
        if (to == null) throw new BusinessException("Cannot " + action + " a mandate that is " + from);
        m.setStatus(to);
        m.setStatusReason(reason == null || reason.isBlank() ? null : reason.trim());
        return views(List.of(m)).get(0);
    }

    // ------------------------------------------------------------------------------------------------

    private CustomerMandate build(MandateRequest r, Set<String> refsInFile) {
        if (r.corporateId() == null) throw new BusinessException("corporateId is required");
        Party party = party(r.corporateId(), r.customerPartyId(), r.customer());
        VirtualAccount va = account(r.corporateId(), r.creditorVaId(), r.creditorAccount());

        CustomerMandate m = new CustomerMandate();
        m.setCorporateId(r.corporateId());
        m.setCustomerPartyId(party.getId());

        String ref = blankToNull(r.mandateReference());
        if (ref == null) throw new BusinessException("mandateReference is required");
        if (ref.length() > 35) throw new BusinessException("mandateReference is longer than 35 characters");
        if (refsInFile.contains(ref.toUpperCase(Locale.ROOT)) || mandates.existsByCorporateIdAndMandateReferenceIgnoreCase(r.corporateId(), ref)) {
            throw new BusinessException("Mandate reference " + ref + " already exists");
        }
        m.setMandateReference(ref);

        String scheme = blankToNull(r.schemeCode());
        if (scheme == null || scheme.length() > 20) throw new BusinessException("schemeCode is required (up to 20 characters)");
        m.setSchemeCode(scheme.toUpperCase(Locale.ROOT));

        String iban = blankToNull(r.debtorIban());
        String bic = blankToNull(r.debtorBic());
        String currency = blankToNull(r.currency());
        if (iban == null) {
            PartyBankAccount primary = partyAccounts.findByPartyIdAndIsPrimaryTrue(party.getId())
                    .orElseThrow(() -> new BusinessException("Enter the customer's IBAN (debtorIban): " + party.getLegalName() + " has no primary bank account"));
            iban = primary.getIban();
            if (bic == null) bic = primary.getBankCode();
            if (currency == null) currency = primary.getCurrency();
        }
        m.setDebtorIban(iban(iban));
        if (bic != null && !bic.toUpperCase(Locale.ROOT).matches("[A-Z0-9]{8}([A-Z0-9]{3})?")) {
            throw new BusinessException("Customer bank BIC (debtorBic) is not a valid BIC: " + bic);
        }
        m.setDebtorBic(bic == null ? null : bic.toUpperCase(Locale.ROOT));
        m.setDebtorName(blankToNull(r.debtorName()) != null ? r.debtorName().trim() : party.getLegalName());
        m.setCreditorVaId(va.getId());

        currency = (currency != null ? currency : va.getCurrencyCode()).toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) throw new BusinessException("currency must be a 3-letter code: " + currency);
        m.setCurrency(currency);

        m.setMandateType(r.mandateType() != null ? r.mandateType() : Type.RECURRING);
        m.setSignedDate(r.signedDate());
        m.setValidFrom(r.validFrom() != null ? r.validFrom() : r.signedDate() != null ? r.signedDate() : LocalDate.now());
        m.setValidTo(r.validTo());
        m.setMaxPerCollection(r.maxPerCollection());
        m.setFrequencyCount(r.frequencyCount());
        m.setFrequencyPeriod(r.frequencyPeriod());
        checkLimits(m);
        m.setStatus(Status.ACTIVE);
        m.setSource(r.source() != null ? r.source() : Source.API);
        return m;
    }

    private static void checkLimits(CustomerMandate m) {
        if (m.getValidTo() != null && m.getValidTo().isBefore(m.getValidFrom())) {
            throw new BusinessException("validTo is before validFrom");
        }
        if (m.getMaxPerCollection() != null && m.getMaxPerCollection().signum() <= 0) {
            throw new BusinessException("maxPerCollection must be positive");
        }
        if ((m.getFrequencyCount() == null) != (m.getFrequencyPeriod() == null)) {
            throw new BusinessException("frequency needs both a count and a period");
        }
        if (m.getFrequencyCount() != null && m.getFrequencyCount() < 1) {
            throw new BusinessException("frequency count must be at least 1");
        }
    }

    /** Format plus the ISO 13616 mod-97 check, so a mistyped account is caught at registration, not at the first return. */
    static String iban(String raw) {
        String iban = raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!iban.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}")) throw new BusinessException("Customer IBAN (debtorIban) is not an IBAN: " + raw);
        String moved = iban.substring(4) + iban.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char c : moved.toCharArray()) digits.append(Character.getNumericValue(c));
        if (new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() != 1) {
            throw new BusinessException("Customer IBAN (debtorIban) fails its check digits, so it is mistyped: " + raw);
        }
        return iban;
    }

    private Party party(UUID corporateId, UUID partyId, String customer) {
        Party p;
        if (partyId != null) {
            p = parties.findById(partyId).orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + partyId));
        } else if (blankToNull(customer) != null) {
            String c = customer.trim();
            Optional<Party> byCode = parties.findByPartyCode(c).filter(x -> corporateId.equals(x.getCorporateId()));
            if (byCode.isPresent()) {
                p = byCode.get();
            } else {
                // names are not unique: two customers can share one, and then only the party code identifies it
                List<Party> named = parties.findAllByCorporateIdAndLegalNameIgnoreCase(corporateId, c);
                if (named.isEmpty()) throw new BusinessException("No customer with code or name " + c);
                if (named.size() > 1) throw new BusinessException(named.size() + " customers are called " + c + "; use the party code");
                p = named.get(0);
            }
        } else {
            throw new BusinessException("customer is required");
        }
        if (!corporateId.equals(p.getCorporateId())) throw new BusinessException("Customer belongs to another corporate");
        return p;
    }

    private VirtualAccount account(UUID corporateId, UUID vaId, String number) {
        VirtualAccount va;
        if (vaId != null) {
            va = accounts.findById(vaId).orElseThrow(() -> new ResourceNotFoundException("Account not found: " + vaId));
        } else if (blankToNull(number) != null) {
            va = accounts.findByVaNumber(number.trim()).orElseThrow(() -> new BusinessException("No account " + number.trim()));
        } else {
            throw new BusinessException("creditorAccount is required");
        }
        if (!corporateId.equals(va.getCorporateId())) throw new BusinessException("Account " + va.getVaNumber() + " belongs to another corporate");
        if (!va.isActive()) throw new BusinessException("Account " + va.getVaNumber() + " is not active");
        return va;
    }

    private static MandateRequest row(UUID corporateId, List<String> cells, List<String> header) {
        Function<String, String> cell = col -> {
            int i = header.indexOf(col);
            return i >= 0 && i < cells.size() ? blankToNull(cells.get(i)) : null;
        };
        Function<String, LocalDate> date = col -> cell.apply(col) == null ? null : LocalDate.parse(cell.apply(col));
        String max = cell.apply("maxpercollection");
        String type = cell.apply("type");
        // frequency is written "1/MONTH": at most one collection a month
        String freq = cell.apply("frequency");
        Integer count = null;
        Period period = null;
        if (freq != null) {
            String[] parts = freq.split("/");
            if (parts.length != 2) throw new IllegalArgumentException("frequency must look like 1/MONTH: " + freq);
            try {
                count = Integer.valueOf(parts[0].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("frequency must look like 1/MONTH: " + freq);
            }
            period = enumOf(Period.class, parts[1], "frequency period");
        }
        BigDecimal maxAmount;
        try {
            maxAmount = max == null ? null : new BigDecimal(max.replace(",", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("maxPerCollection is not a number: " + max);
        }
        return new MandateRequest(corporateId, null, cell.apply("customer"), cell.apply("debtorname"),
                cell.apply("debtoriban"), cell.apply("debtorbic"), null, cell.apply("creditoraccount"),
                cell.apply("mandatereference"), cell.apply("schemecode"), cell.apply("currency"),
                type == null ? null : enumOf(Type.class, type.replace('-', '_'), "type"),
                date.apply("validfrom"), date.apply("validto"), date.apply("signeddate"),
                maxAmount, count, period, Source.FILE);
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value, String what) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + " must be one of " + Arrays.toString(type.getEnumConstants()) + ": " + value);
        }
    }

    private CustomerMandate find(UUID id) {
        return mandates.findById(id).orElseThrow(() -> new ResourceNotFoundException("Mandate not found: " + id));
    }

    List<MandateView> views(List<CustomerMandate> list) {
        Map<UUID, String> names = parties.findAllById(list.stream().map(CustomerMandate::getCustomerPartyId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Party::getId, Party::getLegalName));
        Map<UUID, String> numbers = accounts.findAllById(list.stream().map(CustomerMandate::getCreditorVaId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(VirtualAccount::getId, VirtualAccount::getVaNumber));
        LocalDate today = LocalDate.now();
        return list.stream().map(m -> new MandateView(m.getId(), m.getMandateReference(), m.getCustomerPartyId(),
                names.get(m.getCustomerPartyId()), m.getDebtorName(), m.getDebtorIban(), m.getDebtorBic(), m.getCreditorVaId(),
                numbers.get(m.getCreditorVaId()), m.getSchemeCode(), m.getCurrency(), m.getMandateType(), m.getValidFrom(),
                m.getValidTo(), m.getSignedDate(), m.getMaxPerCollection(), m.getFrequencyCount(), m.getFrequencyPeriod(),
                m.getStatus(), m.getStatusReason(), m.getSource(), m.getValidTo() != null && m.getValidTo().isBefore(today))).toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
