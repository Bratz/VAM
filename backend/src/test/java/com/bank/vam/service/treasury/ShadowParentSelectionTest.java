package com.bank.vam.service.treasury;

import com.bank.vam.entity.Program;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A shadow account must land under the aggregation for its own currency, every time.
 *
 * <p>findOrCreateDefaultAggregation returned {@code aggregations.get(0)} from a query with no ORDER
 * BY, under a comment reading "or could select by some criteria". Two faults in one line: the pick
 * was not deterministic, so the same call could resolve a different parent on a different database;
 * and it ignored currency. FINAL IHB's root has three aggregation children -- USD, GBP and AED -- so
 * attaching an AED bank account had roughly a two-in-three chance of landing under a USD or GBP
 * container. The root lookup two lines above had the same unordered {@code findFirst()}.
 */
class ShadowParentSelectionTest {

    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final ShadowAccountService service = new ShadowAccountService(
        vas, mock(PhysicalAccountRepository.class), mock(ProgramRepository.class),
        mock(LegalEntityRepository.class), mock(HierarchyVaService.class),
        new com.bank.vam.config.HomeBankProperties());

    private static final UUID PROGRAM_ID = UUID.randomUUID();

    private VirtualAccount va(String number, AccountCategory category, String ccy, int minutesOld) {
        VirtualAccount v = VirtualAccount.builder()
            .vaNumber(number).accountCategory(category).currencyCode(ccy)
            .programId(PROGRAM_ID)
            .build();
        v.setId(UUID.randomUUID());
        v.setCreatedAt(LocalDateTime.now().minusMinutes(minutesOld));
        return v;
    }

    private Program program(String ccy) {
        Program p = Program.builder().programCode("FINALIHB").currencyCode(ccy).build();
        p.setId(PROGRAM_ID);
        p.setCorporateId(UUID.randomUUID());
        return p;
    }

    /** The three aggregations are given in an order that puts the wanted currency last. */
    private VirtualAccount[] stubHierarchy(String... aggCurrencies) {
        VirtualAccount root = va("FINALI-ROOT", AccountCategory.ROOT, "AED", 100);
        when(vas.findByProgramIdAndAccountCategory(PROGRAM_ID, AccountCategory.ROOT))
            .thenReturn(List.of(root));
        VirtualAccount[] aggs = new VirtualAccount[aggCurrencies.length];
        for (int i = 0; i < aggCurrencies.length; i++) {
            aggs[i] = va("AGG-" + aggCurrencies[i], AccountCategory.AGGREGATION, aggCurrencies[i], 90 - i);
        }
        when(vas.findByParentAccountIdAndAccountCategory(eq(root.getId()), eq(AccountCategory.AGGREGATION)))
            .thenReturn(List.of(aggs));
        return aggs;
    }

    @Test
    void theAedShadowLandsUnderTheAedAggregationNotWhicheverCameFirst() {
        // USD first, GBP second, AED last -- get(0) would have returned the USD container
        stubHierarchy("USD", "GBP", "AED");

        VirtualAccount parent = service.findOrCreateDefaultAggregation(program("AED"), "AED");

        assertThat(parent.getCurrencyCode()).isEqualTo("AED");
        assertThat(parent.getVaNumber()).isEqualTo("AGG-AED");
    }

    @Test
    void theShadowsOwnCurrencyWinsOverTheProgramsCurrency() {
        // The program is AED but this bank account is GBP: it belongs in the GBP subtree.
        stubHierarchy("AED", "USD", "GBP");

        VirtualAccount parent = service.findOrCreateDefaultAggregation(program("AED"), "GBP");

        assertThat(parent.getCurrencyCode()).isEqualTo("GBP");
    }

    @Test
    void withNoContainerInThatCurrencyThePickIsStableRatherThanArbitrary() {
        // No EUR aggregation exists. The fallback must be reproducible: oldest first.
        VirtualAccount[] aggs = stubHierarchy("USD", "GBP", "AED");
        VirtualAccount oldest = aggs[0];   // minutesOld 90, the others 89 and 88

        VirtualAccount first = service.findOrCreateDefaultAggregation(program("AED"), "EUR");
        VirtualAccount again = service.findOrCreateDefaultAggregation(program("AED"), "EUR");

        assertThat(first.getId()).isEqualTo(oldest.getId());
        assertThat(again.getId()).isEqualTo(first.getId());
    }

    /** Two aggregations in the wanted currency: still the same one on every call. */
    @Test
    void duplicateCurrencyContainersResolveTheSameWayEveryTime() {
        stubHierarchy("AED", "AED", "USD");

        VirtualAccount a = service.findOrCreateDefaultAggregation(program("AED"), "AED");
        VirtualAccount b = service.findOrCreateDefaultAggregation(program("AED"), "AED");

        assertThat(a.getId()).isEqualTo(b.getId());
        assertThat(a.getVaNumber()).isEqualTo("AGG-AED");
    }

    /** A program carrying more than one ROOT must still resolve the same one. */
    @Test
    void multipleRootsResolveDeterministically() {
        VirtualAccount newer = va("TPOOL-LATER", AccountCategory.ROOT, "AED", 10);
        VirtualAccount older = va("FINALI-ROOT", AccountCategory.ROOT, "AED", 500);
        when(vas.findByProgramIdAndAccountCategory(PROGRAM_ID, AccountCategory.ROOT))
            .thenReturn(List.of(newer, older));   // newest first, as an unordered query might
        VirtualAccount agg = va("AGG-AED", AccountCategory.AGGREGATION, "AED", 400);
        when(vas.findByParentAccountIdAndAccountCategory(eq(older.getId()), eq(AccountCategory.AGGREGATION)))
            .thenReturn(List.of(agg));
        when(vas.findByParentAccountIdAndAccountCategory(eq(newer.getId()), eq(AccountCategory.AGGREGATION)))
            .thenReturn(List.of());

        VirtualAccount parent = service.findOrCreateDefaultAggregation(program("AED"), "AED");

        // resolved via the older root, not whichever the query happened to list first
        assertThat(parent.getVaNumber()).isEqualTo("AGG-AED");
    }
}
