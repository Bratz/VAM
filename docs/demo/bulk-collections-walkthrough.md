# Bulk Collections — demo walkthrough

Recorded on **https://vam-five.vercel.app** on **9 Nov 2026**, end to end, by clicking through the portal.
Every value below is what was entered; every "You see" is what the portal showed at the time.
No sign-in is needed. There is no clearing link, so the bank's side (settlement, returns, mandate approval)
is played from the portal: **Settle due collections up to <date>**, **Mark returned**, **Bank accepted**.

**Story.** Test Multinational Corp's treasury collects what its customers owe: by direct debit where it
holds a mandate, by Request to Pay where it does not; collected money is held for the return window, and a
debit that bounces is reversed and turned into a Request to Pay. A Request to Pay reaches the customer as an
email with a pay link, a QR code and the invoice's own IBAN.

Corporate used throughout: **Test Multinational Corp** (shown as *TestMNC* / *TEST-MNC-001*).
Receiving account throughout: **QA-TXN-L7-53197 · Main Operating Account** (AED, legal entity MNC Treasury Center).

> **Watch the Corporate filter.** The Receivables page can reopen on another corporate (it did, once, on
> *TestShop*). Before each step on Receivables, check the **Corporate** filter says **TestMNC**.

---

## Records this walkthrough created

| What | Reference | Notes |
|---|---|---|
| Customer A | **Al Noor Trading LLC** · CUS-67706 | |
| Customer B | **Gulf Star Logistics LLC** · CUS-67707 | |
| Customer C | **Desert Rose Retail LLC** · CUS-67708 | |
| Invoice A1 | INV-2026-2010 · AED 3,200.00 · due 9 Nov | |
| Invoice A2 | INV-2026-3141 · AED 1,800.00 · due 16 Nov | |
| Invoice B1 | INV-2026-6941 · AED 4,500.00 · due 9 Nov | |
| Invoice C1 | INV-2026-5352 · AED 2,750.00 · due 16 Nov | |
| Invoice C-USD | INV-2026-1281 · **USD** 2,000.00 · due 9 Nov | |
| Mandate | **DEMO-A** · Al Noor · paper · max 5,000 · 1 a month | |
| Mandate | **DEMO-B** · Gulf Star · **e-mandate** · max 5,000 | |
| Mandate | **DEMO-UPL-1** · TONY STARK (PARTY-412) · uploaded · max 1,000 · 1 a month | |
| Mandate | **DEMO-C-USD** · Desert Rose · **USD** | |
| FX forward | **FXD-20261109-4F243F** · sell USD 1,000 / buy AED 3,671.69 @ 3.671685 · value 16 Nov | |
| Collection run | **CR-20261109-A07F9D** (A1, A2, B1, C1) | |
| Collection run | **CR-20261109-854FA7** (C-USD) | |
| Collection runs (file) | **CR-20261109-2BD853**, **CR-20261109-5D3626** | from the Collections file in step 6 |
| Customer + invoice (file) | **Oasis Fresh Foods LLC**, AED 250.00 | raised by row 2 of the file |

To repeat the demo, use new names/references (references must be unique per corporate), or cancel the
DEMO-* mandates first from their row menus.

---

## 0. Set up demo customers and invoices (≈5 min)

### 0.1 Onboard three customers
1. Sidebar **Parties & Entities → Parties & Counterparties**. Corporate filter: **TEST-MNC-001 - Test Multinational Corp**.
2. **Add Party**. Basic Info:
   - Party Type **Company**, Legal Name **Al Noor Trading LLC**, Roles: click **Customer**,
     Registration Country **AE** (it is pre-filled with AE — do not type a second "AE").
3. **Create Party**.
4. Repeat for **Gulf Star Logistics LLC** and **Desert Rose Retail LLC**. For Desert Rose, also fill
   **Email** `ap@desertrose.example` (Basic Info). Leave the other two without one: step 8 shows both cases.

You see: the three parties in the list, codes CUS-67706 / 67707 / 67708, KYC *Pending*.

### 0.2 Raise four AED invoices
1. Sidebar **Payments & Collections → Receivables (AR)** → **Create Invoice** (top right).
2. **Customer**: type *Al Noor*, pick **Al Noor Trading LLC**.
3. **Amount** `3200`, currency **AED**, **Payment Terms** **Immediate** (due today).
4. **Collection Account**: click **Main Operating Account**.
5. Summary reads *Al Noor Trading LLC · AED 3,200.00 · Due 09 Nov 2026 · Main Operating Account* → **Create Invoice**.
6. Repeat (Receivables → Create Invoice each time):

| Customer | Amount | Terms | Due |
|---|---|---|---|
| Al Noor Trading LLC | 1800 | Net 7 | 16 Nov |
| Gulf Star Logistics LLC | 4500 | Immediate | 9 Nov |
| Desert Rose Retail LLC | 2750 | Net 7 | 16 Nov |

When recorded (9 Nov) the form's number was replaced on save by an INV-2026-xxxx one (table above). Since 10 Nov the
invoice keeps the number shown in the form (INV-202611-…), which can be edited; a number already used is refused.

### 0.3 Hold period
**Receivables (AR) → Collection runs** tab → *Hold collected direct debits for* **5** → **Save**.

You see: *"Collected money is now held for 5 business days."*

---

## 1. Mandates — three ways in

**Receivables (AR) → Mandates** tab.

### 1.1 Paper mandate (registered by the corporate) — DEMO-A
**Register mandate**:
- Customer **Al Noor Trading LLC · CUS-67706**; Paid into **QA-TXN-L7-53197 · AED · Main Operating Account**
- Mandate reference **DEMO-A**, Scheme **UAE_DDS**, Customer IBAN **AE070331234567890123456**
- Type **Recurring**, Valid from today, Most per collection **5000**, At most **1** Collections **a month**
- Leave *Send to the customer's bank for approval* **unticked** → **Register**.

You see: `DEMO-A · UAE_DDS · Recurring · Al Noor Trading LLC · Up to AED 5,000.00 · 1× a month · Active`.

> Talking point: the IBAN's check digits are verified at registration; a typo is refused here, not by the first bounced collection.

### 1.2 Electronic mandate (approved by the payer's bank) — DEMO-B
**Register mandate**: Customer **Gulf Star Logistics LLC**, same account and IBAN, reference **DEMO-B**,
Most per collection **5000**, no frequency cap, and **tick** *Send to the customer's bank for approval (e-mandate)* → **Register**.

You see: *"DEMO-B was sent to the customer's bank. It can collect once the bank accepts it."* and the row
status **Awaiting bank** · *e-mandate*.

- Row menu (⋯) → **Download last message**: the pain.009 sent to the bank, e.g.
  `MsgId MRQ-AF0465E5E2DF41F8AD48 · MndtId DEMO-B · SeqTp RCUR · MaxAmt AED 5000.00 · Cdtr Test Multinational Corp ·
  CdtrAcct QA-TXN-L7-53197 · CdtrAgt EBILAEADXXX · Dbtr Gulf Star Logistics LLC · DbtrAcct AE070331234567890123456`.
- Row menu → **Bank accepted** (stands in for the bank's pain.012).

You see: *"DEMO-B: accepted, it can collect now."*, status **Active**.

> While Awaiting bank, a collection plan routes Gulf Star to Request to Pay with *"Mandate DEMO-B is pending"*.

### 1.3 File upload (all rows or none)
**Upload mandates** → **Download template** shows the columns. First upload a file with two bad rows:

```
mandateReference,customer,creditorAccount,schemeCode,validFrom,debtorIban,maxPerCollection,frequency,type
DEMO-UPL-1,TONY STARK,QA-TXN-L7-53197,UAE_DDS,2026-11-09,AE070331234567890123456,1000,,RECURRING
DEMO-UPL-2,Al Nour Trading LLC,QA-TXN-L7-53197,UAE_DDS,2026-11-09,AE070331234567890123456,1000,,RECURRING
```

You see: *"Nothing was imported. Line 2: 2 customers are called TONY STARK; use the party code; Line 3: No
customer with code or name Al Nour Trading LLC"*.

Then upload the corrected file:

```
mandateReference,customer,creditorAccount,schemeCode,validFrom,debtorIban,maxPerCollection,frequency,type
DEMO-UPL-1,PARTY-412,QA-TXN-L7-53197,UAE_DDS,2026-11-09,AE070331234567890123456,1000,1/MONTH,RECURRING
```

You see: `DEMO-UPL-1 · TONY STARK · Up to AED 1,000.00 · 1× a month · Active`.

---

## 2. Plan and send a collection run

1. **All Invoices** tab. Tick **INV-2026-2010**, **INV-2026-3141**, **INV-2026-6941**, **INV-2026-5352**
   (overdue invoices can be ticked too).
2. **Plan collection (4)**. You see:

| Invoice | Customer | Outstanding | How | Collects on | Mandate / reason |
|---|---|---|---|---|---|
| INV-2026-2010 | Al Noor Trading LLC | AED 3,200.00 | Direct debit | 10 Nov 2026 | DEMO-A · FRST |
| INV-2026-3141 | Al Noor Trading LLC | AED 1,800.00 | Request to Pay | When the customer pays | *Mandate DEMO-A allows 1 a month and has 1 in the one starting 2026-11-01* |
| INV-2026-6941 | Gulf Star Logistics LLC | AED 4,500.00 | Direct debit | 10 Nov 2026 | DEMO-B · FRST |
| INV-2026-5352 | Desert Rose Retail LLC | AED 2,750.00 | Request to Pay | When the customer pays | *No mandate* |

   Totals: *Direct debit · 2 — AED 7,700.00* and *Request to Pay · 2 — AED 4,550.00*.
   Due-today invoices collect on the next business day (lead time 1 day, weekends skipped).
3. **Send collections**. You see: **Collection run CR-20261109-A07F9D** — the two debits *Debit sent*, the
   two others *Request sent*.
4. **Download pain.008**: one message `MsgId CR-20261109-A07F9D · NbOfTxs 2 · CtrlSum 7700.00`, a payment
   block `SeqTp FRST · ReqdColltnDt 2026-11-10` with DEMO-A (3,200.00) and DEMO-B (4,500.00), each with its EndToEndId.

---

## 3. The money arrives — and is held

Before: Main Operating Account **current 4,968 · available 4,968 · held 0**.

1. **Collection runs** tab → *Settle due collections up to* **10 Nov 2026** → **Settle**.

You see: *"Up to 10 Nov 2026: 2 collected, 0 released from hold."*

2. Click the run **CR-20261109-A07F9D**: INV-2026-2010 and INV-2026-6941 **Collected, held — Held until 17 Nov 2026**.

After: **current 12,658 · available 4,968 · held 7,690** (7,700 collected less 2 × 5.00 collection fee). The
balance went up; *available* did not. Both invoices are **Paid**.

---

## 4. One comes back

In the run drawer, row menu of **INV-2026-6941** (Gulf Star) → **Mark returned** → reason **AM04 · Insufficient funds** → **Mark returned**.

You see: *"INV-2026-6941: Returned after collection: AM04 Insufficient funds; Request to Pay sent"*; the
line shows **Returned · AM04 on 9 Nov 2026**.

- Account: **current 8,158 · available 4,963 · held 3,195** — the 4,500 went back to the payer, its hold was
  released, and the 5.00 collection fee is not refunded.
- Invoice INV-2026-6941 is **Open** again for AED 4,500.00, and Gulf Star was sent a Request to Pay.
- DEMO-B stays **Active** (insufficient funds is not a mandate problem). Returning with **MD01** or **AC04**
  instead would **suspend** the mandate.

---

## 5. The hold ends

**Collection runs** → *Settle due collections up to* **17 Nov 2026** → **Settle**.

You see: *"Up to 17 Nov 2026: 0 collected, 1 released from hold."*; the run reads
*Collected · 1 · Request sent · 2 · Returned · 1*. Account: **current 8,158 · available 8,158 · held 0**.

---

## 6. Bulk by file (ERP route)

**File Ingest Pipeline** → *What are you uploading?* **Collections** → Corporate **TestMNC** → choose the
file → **Upload and start pipeline**.

```
amount,currency,viban,debtorName,debtorAccount,remittanceInformation,endToEndId
17.00,AED,AE630410000000000000866483,TONY STARK,,INV-2026-9308,R1
250.00,AED,QA-TXN-L7-53197,Oasis Fresh Foods LLC,,,R2
10.00,AED,QA-TXN-L7-53197,,,INV-DOES-NOT-EXIST,R3
```

You see: the job reaches **Done** and lists each row with what happened to it:

| Row | Status | Reason |
|---|---|---|
| 1 | Processed | *CR-20261109-5D3626: direct debit on 2026-11-10 under DEMO-UPL-1* |
| 2 | Processed | *CR-20261109-2BD853: Request to Pay sent (No mandate)* — customer **Oasis Fresh Foods LLC** and its invoice were raised from the row |
| 3 | Failed | *No invoice INV-DOES-NOT-EXIST, and no customer to raise one for* |

**Collection runs** then lists the file's run(s) with source **File upload**. Row 1 names its account by the
invoice's virtual IBAN; the account number (e.g. QA-TXN-L7-53197) works too, and spaces or lower case in an IBAN
don't matter.

> Recorded in two passes on 9 Nov: the first upload returned a 500 (fixed by migration V46), and on the next run
> row 1 failed with *"No account AE6304…"* (virtual IBANs were not looked up in the VIBAN table; fixed in
> 85c54be). Rows 2–3 come from that run, row 1 from a re-upload of just that row — hence two File runs.

---

## 7. Foreign currency — converted on credit, deal first

### 7.1 Book an FX forward that sells USD for AED
1. **Foreign Exchange → Market Rates**. On **USD/AED**, click the **You sell** price (3.67177).
2. The **New deal** panel: For entity **MNC Treasury Center**, pair **USD/AED**, **Sell USD**, Amount **1000**,
   Instrument **Forward**, Value date **16 Nov 2026**, Purpose *Demo: hedge USD collections* → **Request price**.
3. Firm price **3.671685** — *"You buy AED 3,671.69 and sell USD 1,000.00"* → **Accept price**.
4. **Foreign Exchange → FX Deals**: row **FXD-20261109-4F243F** → ⋯ → **Confirm deal** → **Confirm deal**.
   (A deal must be confirmed before anything can use it.)

### 7.2 USD mandate into the AED account, and a USD invoice
1. **Receivables → Mandates → Register mandate**: Customer **Desert Rose Retail LLC**, Paid into
   **QA-TXN-L7-53197** (AED), reference **DEMO-C-USD**, IBAN as before, **Currency `USD`** → **Register**.
   You see: `DEMO-C-USD · Any amount in USD · Active`.
2. **Create Invoice**: Desert Rose Retail LLC, Amount **2000**, currency **USD**, **Immediate**.
   With USD selected the Collection Account list shows USD accounts — pick **MAIN OPERATING ACCOUNT** (USD).
   The money is credited to the account on the **mandate** (the AED one), converting on the way.
   Saved as **INV-2026-1281**.

### 7.3 Collect and settle
1. **All Invoices** → tick **INV-2026-1281** → **Plan collection**: *Direct debit · 10 Nov 2026 · DEMO-C-USD · FRST* → **Send collections** (run **CR-20261109-854FA7**).
2. **Collection runs** → Settle up to **10 Nov 2026** → *"1 collected"*.
3. Open the run. You see:
   `INV-2026-1281 · USD 2,000.00 · AED 7,343.46 at 3.67173 · FXD-20261109-4F243F +1 · Collected, held · Held until 17 Nov 2026`
   — the forward covered the first USD 1,000 at its rate, the other 1,000 was sold at the live price (a
   second deal, the "+1").
4. **FX Deals**: FXD-20261109-4F243F is now **Settled** (fully used by the collection).

---

## 8. Request to Pay — what the customer gets (≈3 min)

> **Script, not yet recorded.** Added after 9 Nov (commits 746864a, 8e79f0a). Values in *italics* are what to
> expect; IBANs and tokens differ per run. Requests sent before the release carry no link, IBAN or email: on
> such a line, row menu → **Send Request to Pay again** first.

Every Request to Pay (step 2's two, and the one step 4 sent after the return) now gives the invoice:
a **pay link**, a **virtual IBAN** of its own, a kept **pain.013**, and an **email** to the customer.
There is no mail server: emails are kept in an **Outbox** and nothing is delivered.

### 8.1 The pay link and QR
1. **Collection runs** → click the run from step 2.
2. On **INV-2026-5352** (Desert Rose, *Request sent*) click **Pay link & QR**.

You see: a window **Pay INV-2026-5352** · *Desert Rose Retail LLC · AED 2,750.00* with
- a QR code, and the link *https://vam-five.vercel.app/?page=pay-invoice&token=…*;
- **Copy link** and **Open pay page**;
- *Or pay by bank transfer to this invoice's IBAN (matched automatically)* — an **AE…** virtual IBAN;
- below it, *The email the customer got*: To *Desert Rose Retail LLC <ap@desertrose.example>*, subject
  *Payment request: INV-2026-5352 · AED 2,750.00 · Test Multinational Corp*, the body, and the QR again.

3. Scan the QR with a phone, or **Open pay page**: the customer's page opens without a sign-in, showing the
   invoice, the amount and the IBAN.

### 8.2 The ISO message
Row menu of the same line → **Download pain.013**. The file is the CreditorPaymentActivationRequest that was
sent; its remittance carries two lines: `<Ustrd>INV-2026-5352…</Ustrd>` and
`<Ustrd>Pay online: https://vam-five.vercel.app/?page=pay-invoice&token=…</Ustrd>`.

### 8.3 The Outbox
**Receivables (AR) → Outbox** tab (Corporate **TestMNC**).

You see one email per Request to Pay, newest first:

| To | Subject | Status |
|---|---|---|
| Gulf Star Logistics LLC · *no email address* | Payment request: INV-2026-6941 · AED 4,500.00 · … | **No address** (sent after the return in step 4) |
| Desert Rose Retail LLC · ap@desertrose.example | Payment request: INV-2026-5352 · AED 2,750.00 · … | **Sent (simulated)** |
| Al Noor Trading LLC · *no email address* | Payment request: INV-2026-3141 · AED 1,800.00 · … | **No address** |

Click a row to read the email. *No address* means neither the invoice nor the customer has an email; the
request still went out as pain.013, and the pay link still works.

> Talking point: one customer, three ways to pay the same request: tap the link, scan the code, or transfer
> to an IBAN that only this invoice uses, so the money reconciles itself.

---

## For a technical audience (Swagger: `/api/swagger-ui.html`)

| Endpoint | What it does |
|---|---|
| `POST /api/v1/iso20022/inward/pain012` | Payer's bank accepts/refuses an e-mandate request or amendment |
| `POST /api/v1/iso20022/inward/pacs004` | Debits returned after collection (by OrgnlEndToEndId) |
| `POST /api/v1/iso20022/inward/pain002` | Debits rejected before collection (transaction, block or whole run) |
| `POST /api/v1/iso20022/inward/camt054` | A credit that settles a waiting debit; debit entries with a return reason |
| `POST /api/v1/collections/runs/lines` | ERP: lines naming invoices, or new invoices to raise and collect |
| `GET /api/v1/collections/runs/{id}/pain008` | The run's pain.008 |
| `POST /api/v1/receivables/{id}/request-to-pay/send` | Send a Request to Pay: pay link, VIBAN, pain.013, outbox email |
| `GET /api/v1/receivables/{id}/request-to-pay/message` | The last pain.013 sent for the invoice |
| `GET /api/v1/outbox?corporateId=` · `?relatedId=` | The simulated outbox, for a corporate or one invoice |

## Gotchas seen while recording

- **Corporate filter** on Receivables can come back on another corporate — check it says TestMNC.
- Registration Country on **Add Party** is pre-filled with AE.
- Before 10 Nov the Create Invoice form's number was replaced on save; it is now kept as shown.
- Requests to Pay sent before 10 Nov (the 9 Nov recording) have no pay link, VIBAN or email: use the line's
  **Send Request to Pay again**. A paid invoice is refused (*already paid; there is nothing to request*).
- Pay links are built on the server from `FRONTEND_BASE_URL`; the OCI compose file sets it to
  https://vam-five.vercel.app. A *localhost* link in an email means a deploy dropped that setting.
- "Settle due collections up to" moves the simulated clock; it settles everything due up to that date for
  every corporate, the same as the evening job.
