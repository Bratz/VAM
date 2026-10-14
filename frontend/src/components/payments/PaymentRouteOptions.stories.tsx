import type { Meta, StoryObj } from '@storybook/react-vite';
import type { AxiosAdapter } from 'axios';
import { PaymentRouteOptions } from './PaymentRouteOptions';
import { apiClient } from '../../services/api';

/**
 * The routing step, in each state it can actually be in.
 *
 * <p>The component fetches, so the stories swap the shared axios adapter for a canned one. The
 * payloads are uc05's own shape -- snake_case, naive local times -- for the same reason the backend
 * records are: one vocabulary, and a drift shows up as a visibly empty field rather than as a
 * plausible wrong number.
 */

const ARRIVAL = '2026-10-15T11:30:00';
const DEADLINE = '2026-10-15T17:00:00';

const why = {
  on_time: {
    prior: 0.91,
    estimate: 0.9412,
    margin: 0.0233,
    top: [['corridor_sg', 0.0182], ['amount_band_high', -0.0041]] as [string, number][],
    rest: 0.0012,
  },
  deduction: {
    prior: 22, estimate: 18, margin: 4.1,
    top: [['charge_bearer_sha', -3.2]] as [string, number][], rest: 0.8,
  },
  fail: { prior: 0.009, estimate: 0.0071, margin: 0.004, top: [] as [string, number][], rest: 0 },
};

const THREE_OPTIONS = {
  asked: {
    currency: 'USD', amount: 250000, destination: 'SG',
    at: '2026-10-14T09:30:00', by_when: DEADLINE, charge_bearer: 'SHA',
  },
  options: [
    {
      rank: 1, labels: ['cheapest'], route: 'batch_usd_sg', name: 'ACH via SCB Singapore',
      kind: 'batch', charge: 12.5, parts: 1, send: '2026-10-14T18:00:00', arrival: ARRIVAL,
      minutes_to_spare: 330, expected_deduction: 0, on_time: 0.9122, on_time_margin: 0.0311,
      failure: 0.0094, expected_total: 12.5, proven: true, why,
    },
    {
      rank: 2, labels: ['fastest', 'surest'], route: 'rtgs_usd', name: 'Fedwire Funds',
      kind: 'rtgs', charge: 42.5, parts: 1, send: '2026-10-14T10:00:00',
      arrival: '2026-10-14T14:15:00', minutes_to_spare: 1605, expected_deduction: 0,
      on_time: 0.9841, on_time_margin: 0.0104, failure: 0.0021, expected_total: 42.5,
      proven: true, why,
    },
    {
      rank: 3, labels: [], route: 'correspondent_usd_sg', name: 'Correspondent via DBS',
      kind: 'correspondent', charge: 31, parts: 2, send: '2026-10-14T11:00:00',
      arrival: '2026-10-15T15:45:00', minutes_to_spare: 75, expected_deduction: 18,
      on_time: 0.8402, on_time_margin: 0.0712, failure: 0.0183, expected_total: 49,
      // the learner is not confident here, and the card has to say so
      proven: false, why,
    },
  ],
  none: null,
  considered: 5,
  excluded: [
    { route: 'instant_usd', name: 'RTP', reason: 'above the scheme limit for this amount' },
    { route: 'stablecoin_usdc', name: 'USDC', reason: 'the corridor is not enabled for this corporate' },
  ],
  floor: 0.9,
  sheet_version: 7,
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
  amount: 250000,
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

/** Three options with their labels, charges, arrivals and the two excluded routes. */
export const ThreeOptions: Story = {
  decorators: [withAdapter(serve({ quotable: true, note: null, quote: THREE_OPTIONS }))],
};

/** A corridor the learner has no data of its own for: same figures, weaker evidence, said out loud. */
export const UnknownCorridor: Story = {
  decorators: [
    withAdapter(serve({
      quotable: true, note: null,
      quote: { ...THREE_OPTIONS, destination_known: false },
    })),
  ],
};

/** uc05 considered the routes and offered none. Its reason is shown, not an empty list. */
export const NothingEligible: Story = {
  decorators: [
    withAdapter(serve({
      quotable: true, note: null,
      quote: { ...THREE_OPTIONS, options: [], none: 'no eligible route', considered: 0 },
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

/** Domestic: no beneficiary country in the BIC, so no destination goes to uc05. */
export const Domestic: Story = {
  args: { ...args, currency: 'GBP', creditorBic: '' },
  decorators: [
    withAdapter(serve({
      quotable: true, note: null,
      quote: {
        ...THREE_OPTIONS,
        asked: { ...THREE_OPTIONS.asked, currency: 'GBP', destination: null },
        destination_known: null,
        excluded: [],
      },
    })),
  ],
};
