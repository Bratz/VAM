// What can be done to a deal, in one place: the row menu on the Deals list and the deal drawer both ask here,
// so they can never disagree about whether Close out or Draw down is on offer.

import type { Deal, DealStatus } from '../../services/fxDealApi';

/** Deals the corporate can still act on. */
export const IN_FORCE: DealStatus[] = ['ACTIVE', 'PARTIALLY_DRAWN', 'SETTLEMENT_FAILED'];

/** Deals that count against the credit limit and as hedges (the server's DealStatus.OPEN). */
export const OPEN_STATUSES: DealStatus[] = ['BOOKED', 'ACTIVE', 'PENDING_SETTLEMENT', 'PARTIALLY_DRAWN', 'SETTLEMENT_FAILED'];

const FINISHED: DealStatus[] = ['SETTLED', 'CLOSED_OUT', 'ROLLED', 'CANCELLED'];

export type DealAction = 'dispute' | 'draw' | 'close' | 'roll' | 'settlement';

export const dealRules = (d: Deal) => {
  const inForce = IN_FORCE.includes(d.status);
  const canAct = inForce && d.instrument !== 'SPOT' && d.confirmationStatus !== 'DISPUTED';
  return {
    inForce,
    /** The bank's confirmation is waiting for the corporate to confirm or dispute it. */
    canConfirm: d.confirmationStatus === 'ISSUED' && !FINISHED.includes(d.status),
    canAct,
    canDraw: canAct && d.instrument === 'WINDOW' && d.confirmationStatus === 'AFFIRMED' && d.remainingAmount > 0,
    /** The settlement accounts can change until the day before value date, or while a failed settlement waits. */
    canChangeAccounts: !!d.entityId && OPEN_STATUSES.includes(d.status)
      && (d.status === 'SETTLEMENT_FAILED' || d.valueDate > new Date().toLocaleDateString('en-CA')),
    /** Something the corporate has to do or wait out: an unconfirmed or disputed confirmation, or a failed settlement. */
    needsAttention: !FINISHED.includes(d.status)
      && (d.confirmationStatus === 'ISSUED' || d.confirmationStatus === 'DISPUTED' || d.status === 'SETTLEMENT_FAILED'),
  };
};
