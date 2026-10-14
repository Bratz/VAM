import type { Meta, StoryObj } from '@storybook/react-vite';
import type { AxiosAdapter } from 'axios';
import { PaymentRouteOptions } from './PaymentRouteOptions';
import { apiClient } from '../../services/api';

/**
 * The routing step, in each state it can actually be in.
 *
 * <p>The component fetches, so the stories swap the shared axios adapter for a canned one.
 *
 * <p>**The payloads are transcribed from a live uc05**, not invented: a quote for 10,000 USD to SG
 * and one for 5,000 GBP domestic, taken from `GET /routing/quote` with route data loaded. Route ids,
 * names, charges, the on-time estimates and their margins, the exclusion reasons and the learner's
 * own feature names (`route:corr_usd_3`, `family:correspondent|amount`, `corridor:SG`) are all as it
 * returned them. Earlier versions of this file used made-up values and drifted from the real service
 * in ways that only showed up when something was finally pointed at it -- `floor` is 0.95 and the
 * route sheet is version 6, where the invented fixtures said 0.9 and 7.
 */

const ARRIVAL = '2026-10-15T09:30:00';
const DEADLINE = '2026-10-16T01:00:20';

/** Real explainability terms for corr_usd_3, as the learner returned them. */
const WHY_CORR_3 = {
  on_time: {
    prior: 0.98, estimate: 0.9829, margin: 0.0164,
    top: [
      ['route:corr_usd_3', 0.0315],
      ['family:correspondent|amount', -0.0243],
      ['route:corr_usd_3|corridor:SG', -0.0114],
      ['corridor:SG', 0.0109],
      ['bias', -0.0091],
    ] as [string, number][],
    rest: 0.0053,
  },
  deduction: {
    prior: 3.0, estimate: 6.1328, margin: 1.3118,
    top: [
      ['family:correspondent', 2.1759],
      ['route:corr_usd_3', 1.3215],
      ['bias', 1.0098],
      ['family:correspondent|amount', -0.7369],
      ['corridor:SG', -0.5648],
    ] as [string, number][],
    rest: -0.0727,
  },
  fail: {
    prior: 0.02, estimate: 0.01, margin: 0.0131,
    top: [
      ['route:corr_usd_3', -0.0279],
      ['bias', 0.0109],
      ['corridor:SG', -0.01],
    ] as [string, number][],
    rest: 0.001,
  },
};

/** 10,000 USD to SG, by_when +30h. Three options, five routes excluded. */
const USD_TO_SG = {
  asked: {
    currency: 'USD', amount: 10000, destination: 'SG',
    at: '2026-10-14T19:00:20', by_when: DEADLINE, charge_bearer: 'SHA',
  },
  options: [
    {
      rank: 1, labels: ['cheapest', 'surest'], route: 'corr_usd_3',
      name: 'Correspondent USD 3', kind: 'correspondent',
      charge: 42.0, parts: 1, send: '2026-10-15T08:00:00', arrival: ARRIVAL,
      minutes_to_spare: 930, expected_deduction: 6.13,
      on_time: 0.9829, on_time_margin: 0.0164, failure: 0.01,
      expected_total: 49.03, proven: true, why: WHY_CORR_3,
    },
    {
      rank: 2, labels: ['fastest'], route: 'mto_1', name: 'Operator 1', kind: 'operator',
      charge: 73.0, parts: 1, send: '2026-10-14T19:00:20', arrival: '2026-10-14T19:30:20',
      minutes_to_spare: 1770, expected_deduction: 0.0,
      on_time: 0.9605, on_time_margin: 0.0171, failure: 0.0372,
      expected_total: 77.47, proven: true, why: WHY_CORR_3,
    },
    {
      // the learner is not confident on this one, and the card has to say so
      rank: 3, labels: [], route: 'corr_usd_1', name: 'Correspondent USD 1',
      kind: 'correspondent',
      charge: 50.0, parts: 1, send: '2026-10-15T08:00:00', arrival: '2026-10-15T10:00:00',
      minutes_to_spare: 900, expected_deduction: 8.58,
      on_time: 0.9609, on_time_margin: 0.0226, failure: 0.0099,
      expected_total: 59.63, proven: false, why: WHY_CORR_3,
    },
  ],
  none: null,
  considered: 5,
  excluded: [
    { route: 'mto_3', name: 'Operator 3', reason: 'corridor not served' },
    { route: 'card_visa_direct', name: 'Visa Direct', reason: 'beneficiary not reachable by card' },
    { route: 'card_mc_send', name: 'Mastercard Send', reason: 'beneficiary not reachable by card' },
    { route: 'token_usd', name: 'Tokenised-deposit network (USD)', reason: 'beneficiary not reachable by token' },
    { route: 'stable_usdx', name: 'Stablecoin (USDX) with local off-ramp', reason: 'corridor not served' },
  ],
  floor: 0.95,
  sheet_version: 6,
  destination_known: true,
};

/** Replaces the shared client's transport for the life of a story. */
function serve(body: unknown, status = 200): AxiosAdapter {
  return async (config) =>
    ({ data: body, status, statusText: 'OK', headers: {}, config } as never);
}

function withAdapter(adapter: AxiosAdapter) {
  return (Story: React.ComponentType) => {
    apiClient.defaults.adapter = adapter;
    return <div className="max-w-3xl"><Story /></div>;
  };
}

const args = {
  fromVaId: '2f4b1c8e-0000-4000-8000-000000000001',
  currency: 'USD',
  amount: 10000,
  creditorBic: 'SCBLSGSGXXX',
  chargeBearer: 'SHA',
  purposeCode: 'SUPP',
  onSelected: () => undefined,
  onCleared: () => undefined,
};

const meta: Meta<typeof PaymentRouteOptions> = {
  title: 'Payments/PaymentRouteOptions',
  component: PaymentRouteOptions,
  args,
};
export default meta;
type Story = StoryObj<typeof PaymentRouteOptions>;

/** Three options with their labels, charges and arrivals, and five routes excluded. */
export const ThreeOptions: Story = {
  decorators: [withAdapter(serve({ quotable: true, note: null, quote: USD_TO_SG }))],
};

/** A corridor the learner has no data of its own for: same figures, weaker evidence, said out loud. */
export const UnknownCorridor: Story = {
  decorators: [
    withAdapter(serve({
      quotable: true, note: null,
      quote: {
        ...USD_TO_SG,
        asked: { ...USD_TO_SG.asked, destination: 'ZW' },
        destination_known: false,
      },
    })),
  ],
};

/**
 * uc05 considered the routes and offered none. Its own words, from a 250,000 USD ask that
 * exceeded what the routes' accounts could cover.
 */
export const NothingEligible: Story = {
  args: { ...args, amount: 250000 },
  decorators: [
    withAdapter(serve({
      quotable: true, note: null,
      quote: {
        ...USD_TO_SG,
        asked: { ...USD_TO_SG.asked, amount: 250000 },
        options: [],
        none: 'more than its account may hold',
        considered: 0,
        excluded: [
          { route: 'corr_usd_1', name: 'Correspondent USD 1', reason: 'too late for the by-when' },
          { route: 'mto_2', name: 'Operator 2', reason: 'would need more than 5 parts' },
          { route: 'mto_3', name: 'Operator 3', reason: 'corridor not served' },
        ],
      },
    })),
  ],
};

/** AED today: uc05 quotes USD, EUR and GBP only, and the screen says so rather than failing. */
export const CurrencyNotQuoted: Story = {
  args: { ...args, currency: 'AED' },
  decorators: [
    withAdapter(serve({
      quotable: false,
      note: 'AED is not a currency the routing service quotes. It quotes USD, EUR, GBP.',
      quote: null,
    })),
  ],
};

/** uc05 unreachable. The payment stays possible; nothing blocks and nothing throws. */
export const RoutingUnavailable: Story = {
  decorators: [
    withAdapter(async () => {
      throw new Error('ECONNREFUSED');
    }),
  ],
};

/**
 * Domestic GBP: no beneficiary country in the BIC, so no destination goes to uc05 and
 * destination_known comes back null. Instant payments take all three labels at once.
 */
export const Domestic: Story = {
  args: { ...args, currency: 'GBP', amount: 5000, creditorBic: '' },
  decorators: [
    withAdapter(serve({
      quotable: true, note: null,
      quote: {
        asked: {
          currency: 'GBP', amount: 5000, destination: null,
          at: '2026-10-14T19:01:19', by_when: '2026-10-16T01:01:19', charge_bearer: 'SHA',
        },
        options: [
          {
            rank: 1, labels: ['cheapest', 'fastest', 'surest'], route: 'instant_gbp',
            name: 'Instant payments (GBP)', kind: 'instant',
            charge: 0.05, parts: 1,
            send: '2026-10-14T19:01:19', arrival: '2026-10-14T19:01:19',
            minutes_to_spare: 1800, expected_deduction: 0.0,
            on_time: 0.9956, on_time_margin: 0.0089, failure: 0.0014,
            expected_total: 0.05, proven: true, why: WHY_CORR_3,
          },
        ],
        none: null,
        considered: 2,
        excluded: [],
        floor: 0.95,
        sheet_version: 6,
        destination_known: null,
      },
    })),
  ],
};
