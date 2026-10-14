import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertTriangle,
  BadgeCheck,
  ChevronDown,
  Clock,
  Coins,
  Globe2,
  Route as RouteIcon,
  ShieldQuestion,
  Zap,
} from 'lucide-react';
import { Badge, Button, Card, Input, Spinner } from '../ui';
import { Alert } from '../ui/enhanced';
import {
  ROUTE_KIND,
  ROUTE_LABEL,
  type RouteOption,
  type RouteOptionsRequest,
  type RouteQuote,
  routingApi,
  countryFromBic,
} from '../../services/routingApi';
import { formatCurrency } from '../../utils';

/**
 * The routing step: what uc05 would do with this payment, and which one the corporate picks.
 *
 * <p>Three things this screen is careful about, because all three are easy to overstate:
 *
 * <p>These are estimates from a learner, not quotes from a bank. An option carries `proven` when
 * the learner considers its own estimate well supported, and the card says so when it does not.
 * Nothing here is a commitment, and the wording never implies one.
 *
 * <p>A corridor the learner was never built with (`destination_known: false`) is judged on the
 * route and the bands alone. That is weaker evidence and is stated as such rather than hidden
 * behind the same confident percentage.
 *
 * <p>The quote is a read at one moment. Confirming re-asks, and the backend refuses if the answer
 * moved against the corporate -- so this screen must be able to show a refusal and a fresh set of
 * options, which is the `refusal` state below rather than an error toast.
 */

interface Props {
  fromVaId: string;
  currency: string;
  amount: number;
  /** The beneficiary BIC; its characters 5-6 are where the destination comes from. */
  creditorBic?: string | null;
  chargeBearer?: string | null;
  purposeCode?: string | null;
  /** Called with the recorded selection once the backend has accepted it. */
  onSelected: (selectionId: string, channel: string, note: string | null) => void;
  /** Called when the corporate clears the choice, or a confirmed route is invalidated. */
  onCleared: () => void;
}

/** A deadline the desk will recognise: end of today, local, as an ISO local date-time. */
function defaultDeadline(): string {
  const d = new Date();
  d.setHours(17, 0, 0, 0);
  if (d.getTime() < Date.now()) {
    d.setDate(d.getDate() + 1);
  }
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

const KIND_ICON: Record<string, React.ReactNode> = {
  rtgs: <Zap className="w-4 h-4" />,
  instant: <Zap className="w-4 h-4" />,
  batch: <Clock className="w-4 h-4" />,
  correspondent: <Globe2 className="w-4 h-4" />,
  operator: <RouteIcon className="w-4 h-4" />,
  card_push: <Coins className="w-4 h-4" />,
  token: <Coins className="w-4 h-4" />,
  stablecoin: <Coins className="w-4 h-4" />,
};

function hoursAndMinutes(minutes: number): string {
  if (minutes < 0) return 'past the deadline';
  if (minutes < 60) return `${minutes} min to spare`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m === 0 ? `${h}h to spare` : `${h}h ${m}m to spare`;
}

function arrivalLabel(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleString(undefined, {
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export const PaymentRouteOptions: React.FC<Props> = ({
  fromVaId,
  currency,
  amount,
  creditorBic,
  chargeBearer,
  purposeCode,
  onSelected,
  onCleared,
}) => {
  const [byWhen, setByWhen] = useState<string>(defaultDeadline);
  const [loading, setLoading] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [quote, setQuote] = useState<RouteQuote | null>(null);
  const [chosen, setChosen] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [showExcluded, setShowExcluded] = useState(false);

  const destination = countryFromBic(creditorBic);

  const ask = useMemo<RouteOptionsRequest>(
    () => ({
      fromVaId,
      currency,
      amount,
      byWhen,
      beneficiaryCountry: destination,
      chargeBearer: chargeBearer ?? null,
      purposeCode: purposeCode ?? null,
      reachCard: false,
      reachToken: false,
    }),
    [fromVaId, currency, amount, byWhen, destination, chargeBearer, purposeCode],
  );

  const load = useCallback(async () => {
    if (!fromVaId || !(amount > 0)) {
      setQuote(null);
      setNote(null);
      return;
    }
    setLoading(true);
    try {
      const response = await routingApi.quote(ask);
      setQuote(response.quote);
      setNote(response.note);
    } catch {
      // The payment must stay possible when routing is not: no toast, no blocking error.
      setQuote(null);
      setNote('Routing options could not be loaded. Continue without choosing a route.');
    } finally {
      setLoading(false);
    }
  }, [ask, fromVaId, amount]);

  // Held in a ref so the effect below can drop a stale choice without taking the callback as a
  // dependency. Callers pass inline arrows, so the identity changes on every parent render, and
  // depending on it would re-fire the debounced quote on every keystroke in the form.
  const clearedRef = useRef(onCleared);
  clearedRef.current = onCleared;

  // Re-quote whenever the ask changes. A choice made against the old answer is not a choice about
  // this payment -- change the amount after confirming a route and the route must go with it,
  // or the payment leaves carrying a selection that was priced on a different number.
  useEffect(() => {
    setChosen(null);
    setRefusal(null);
    clearedRef.current();
    const id = setTimeout(load, 500);
    return () => clearTimeout(id);
  }, [load]);

  const confirm = async (option: RouteOption) => {
    setConfirming(true);
    setRefusal(null);
    try {
      const result = await routingApi.select(ask, option.route, option.charge, option.arrival);
      if (result.accepted && result.selectionId && result.channel) {
        setChosen(option.route);
        onSelected(result.selectionId, result.channel, result.requoteNote);
      } else {
        // Refused: the world moved. Show why, and the options as they now stand.
        setRefusal(result.requoteNote ?? 'The options have changed. Choose again.');
        setChosen(null);
        onCleared();
        if (result.quote) setQuote(result.quote);
      }
    } catch {
      setRefusal('The route could not be confirmed. Try again, or continue without one.');
      setChosen(null);
      onCleared();
    } finally {
      setConfirming(false);
    }
  };

  // --------------------------------------------------------------------------

  const options = quote?.options ?? [];
  const unknownCorridor = quote?.destination_known === false;

  return (
    <Card padding="md" className="space-y-4">
      <div className="flex items-start justify-between gap-4 flex-wrap">
        <div className="flex items-center gap-2">
          <RouteIcon className="w-5 h-5 text-accent-500" />
          <div>
            <h3 className="body-strong">Payment route</h3>
            <p className="caption">
              {destination
                ? `Cross-border to ${destination}, from the beneficiary BIC`
                : 'Domestic — no beneficiary country in the BIC'}
            </p>
          </div>
        </div>
        <div className="w-full sm:w-auto">
          <Input
            type="datetime-local"
            label="Needs to arrive by"
            value={byWhen}
            onChange={(e) => setByWhen(e.target.value)}
          />
        </div>
      </div>

      {loading && (
        <div className="flex items-center gap-2 caption">
          <Spinner size="sm" />
          Looking at the routes…
        </div>
      )}

      {!loading && note && <Alert variant="info">{note}</Alert>}

      {!loading && !note && quote?.none && <Alert variant="warning">{quote.none}</Alert>}

      {!loading && refusal && (
        <Alert variant="warning" title="The options moved while you were deciding">
          {refusal}
        </Alert>
      )}

      {!loading && unknownCorridor && (
        <Alert variant="warning">
          This corridor is not one the routing model has data of its own for. The figures below are
          judged from the route and its kind, so treat them as weaker than usual.
        </Alert>
      )}

      {!loading && options.length > 0 && (
        <div className="space-y-3">
          {options.map((option) => {
            const isChosen = chosen === option.route;
            const isOpen = expanded === option.route;
            return (
              <div
                key={option.route}
                className={[
                  'rounded-lg border transition-colors',
                  isChosen
                    ? 'border-accent-500 bg-accent-50/60 dark:bg-accent-900/20'
                    : 'border-neutral-200 dark:border-neutral-700',
                ].join(' ')}
              >
                <div className="p-4 space-y-3">
                  <div className="flex items-start justify-between gap-3 flex-wrap">
                    <div className="space-y-1">
                      <div className="flex items-center gap-2 flex-wrap">
                        {KIND_ICON[option.kind] ?? <RouteIcon className="w-4 h-4" />}
                        <span className="body-strong">{option.name}</span>
                        <Badge variant="neutral" size="xs">
                          {ROUTE_KIND[option.kind] ?? option.kind}
                        </Badge>
                      </div>
                      <div className="flex items-center gap-1.5 flex-wrap">
                        {option.labels.map((label) => (
                          <Badge key={label} variant="accent" size="xs">
                            {ROUTE_LABEL[label] ?? label}
                          </Badge>
                        ))}
                        {!option.proven && (
                          <Badge variant="warning" size="xs" icon={<ShieldQuestion className="w-3 h-3" />}>
                            Estimate not yet well supported
                          </Badge>
                        )}
                      </div>
                    </div>
                    <Button
                      size="sm"
                      variant={isChosen ? 'success' : 'accent'}
                      loading={confirming && !isChosen}
                      disabled={confirming}
                      leftIcon={isChosen ? <BadgeCheck className="w-4 h-4" /> : undefined}
                      onClick={() => confirm(option)}
                    >
                      {isChosen ? 'Chosen' : 'Choose this route'}
                    </Button>
                  </div>

                  <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                    <div>
                      <p className="label">Charge</p>
                      <p className="body-strong">{formatCurrency(option.charge, currency)}</p>
                    </div>
                    <div>
                      <p className="label">Arrives</p>
                      <p className="body-strong">{arrivalLabel(option.arrival)}</p>
                      <p className="caption">{hoursAndMinutes(option.minutes_to_spare)}</p>
                    </div>
                    <div>
                      <p className="label">On time</p>
                      <p className="body-strong">{Math.round(option.on_time * 100)}%</p>
                      <p className="caption">±{Math.round(option.on_time_margin * 100)} pts</p>
                    </div>
                    <div>
                      <p className="label">Expected total</p>
                      <p className="body-strong">
                        {formatCurrency(option.expected_total, currency)}
                      </p>
                      {option.expected_deduction > 0 && (
                        <p className="caption">
                          incl. {formatCurrency(option.expected_deduction, currency)} deducted en route
                        </p>
                      )}
                    </div>
                  </div>

                  <button
                    type="button"
                    className="flex items-center gap-1 caption hover:underline"
                    onClick={() => setExpanded(isOpen ? null : option.route)}
                  >
                    <ChevronDown
                      className={`w-3.5 h-3.5 transition-transform ${isOpen ? 'rotate-180' : ''}`}
                    />
                    {isOpen ? 'Hide' : 'Why this estimate'}
                  </button>

                  {isOpen && (
                    <div className="rounded-md bg-neutral-50 dark:bg-neutral-800/60 p-3 space-y-2">
                      {(['on_time', 'deduction', 'fail'] as const).map((term) => {
                        const detail = option.why?.[term];
                        if (!detail) return null;
                        const heading =
                          term === 'on_time'
                            ? 'Arriving on time'
                            : term === 'deduction'
                              ? 'Deducted en route'
                              : 'Failing outright';
                        return (
                          <div key={term}>
                            <p className="label">{heading}</p>
                            <p className="caption">
                              starts at {detail.prior}, estimated {detail.estimate} (±{detail.margin})
                            </p>
                            {detail.top?.length > 0 && (
                              <ul className="caption list-disc pl-4">
                                {detail.top.map(([name, value]) => (
                                  <li key={name}>
                                    {name}: {value > 0 ? '+' : ''}
                                    {value}
                                  </li>
                                ))}
                              </ul>
                            )}
                          </div>
                        );
                      })}
                      <p className="caption">
                        Estimates from the routing model, not a quoted price. The charge and arrival
                        are checked again when you confirm.
                      </p>
                    </div>
                  )}
                </div>
              </div>
            );
          })}

          {(quote?.excluded?.length ?? 0) > 0 && (
            <div>
              <button
                type="button"
                className="flex items-center gap-1 caption hover:underline"
                onClick={() => setShowExcluded(!showExcluded)}
              >
                <ChevronDown
                  className={`w-3.5 h-3.5 transition-transform ${showExcluded ? 'rotate-180' : ''}`}
                />
                {quote!.excluded.length} route{quote!.excluded.length === 1 ? '' : 's'} not offered
              </button>
              {showExcluded && (
                <ul className="mt-2 space-y-1">
                  {quote!.excluded.map((item) => (
                    <li key={item.route} className="flex items-start gap-2 caption">
                      <AlertTriangle className="w-3.5 h-3.5 mt-0.5 shrink-0 text-warning-500" />
                      <span>
                        <span className="font-medium">{item.name}</span> — {item.reason}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}

          {quote?.sheet_version != null && (
            <p className="caption">
              Route data version {quote.sheet_version} &middot; {quote.considered} route
              {quote.considered === 1 ? ' was' : 's were'} in play.
            </p>
          )}
        </div>
      )}
    </Card>
  );
};

export default PaymentRouteOptions;
