# 0002 — Data requirements for a distributor's order desk

Status: draft (2026-11-19; revision 2 2026-11-20: requirements from two desk procedures, §Procedures; revision 3 2026-11-20: reference lookups and a test run, §Reference lookups). Purpose: the data a test environment should hold, and the business knowledge to write down, so that Spindle flows and a semantic graph on top of them can answer what customers ask an IT distributor's order desk by email.

The questions come from a sample of real order-desk mail, grouped into ten business areas. No mail, customer, part number or system name from that sample is reproduced here.

## Overview

| # | Area | Typical question | Priority |
| --- | --- | --- | --- |
| A | Order status and ETA | "When do the remaining 2 units arrive?" | P1 |
| B | Order holds and line status | "My order has been stuck in allocated for days." | P1 |
| C | Invoices, credits, serial numbers | "The invoice says 4 units shipped; we received 2." | P1 |
| D | Returns (RMA) | "Why is there still no return label for my request?" | P1 |
| E | Quotes, deals, prices | "Is this quote still valid? This is a rush order." | P2 |
| F | Freight and ship-from warehouse | "Why was freight charged above the free-freight threshold?" | P2 |
| G | Supply: stock, incoming, lead time | "If I order 260 today, when do they arrive?" | P2 |
| H | Products: end of life, replacements, warranty, compliance | "This part is end of life — what replaces it?" | P3 |
| I | Customers and owners | "Who handles this account?" | P3 |
| J | Order changes and cancellations (writes) | "Please cancel the back-ordered line." | P3 |

## Per area: data, samples, knowledge

Each area lists the data to prepare, the sample cases (at least two of each), and the business knowledge to document.

### A Order status and ETA
- **Data:** order header, lines, cartons and tracking, proof of delivery; drop-ship sales order → vendor POs → line ETAs; customer PO → sales orders.
- **Samples:** stock order shipped; partly shipped with a back order; drop-ship order split over 2+ vendor POs; ETA changed twice or more; ETA unknown (vendor must be asked); delivered with POD; deleted order; one customer PO → several sales orders; the same customer PO number used by two customers.
- **Knowledge:** meaning of every ETA status code; when to answer the customer and when to ask the vendor first; the time zone of stored times; where vendor-side order numbers can be looked up.

### B Order holds and line status
- **Data:** hold flag and type (credit, price, compliance, shortage, customer request) per order and line, who set and released it and when; line status (allocated, picking, back ordered, non-cancellable/non-returnable, shipped); customer credit status.
- **Samples:** credit hold open; credit hold released; price hold; allocated for more than 3 days without shipping; non-cancellable back-ordered line; customer account restricted.
- **Knowledge:** which team releases each hold type and how long it usually takes; how long "allocated" is normal; what the customer must confirm for non-cancellable lines; what may be said about credit status.

### C Invoices, credits, serial numbers
- **Data:** invoice header (number, date, due date, amount, status), invoice lines incl. freight and tax, invoice ↔ order; credit memos and the invoice they credit; **serial numbers per carton or line**; billing schedules for subscriptions and multi-year contracts.
- **Samples:** a plain invoice; several invoices for one order; an invoice with a credit; shipped serials that differ from what the customer reports; a multi-year subscription billed yearly; a bill-only order.
- **Knowledge:** invoice dispute process and owner; usual time to a credit memo; when the next subscription year is invoiced; when bill-only is allowed.

### D Returns (RMA)
- **Data:** return request (request number, RMA number, customer reference, original order and invoice, lines and serials, reason, status, step times); return label or call tag; warehouse receipt and inspection result; replacement order; credit.
- **Samples:** submitted, waiting for approval; approved, no label yet; received, credit pending; inspection discrepancy (open box); rejected ("no longer needed"); wrong item shipped, replacement; short shipment, replacement; closed.
- **Knowledge:** RMA status flow and the owner of each step; who pays return freight for each reason; how vendor exceptions are requested; what counts as an RMA being finished.

### E Quotes, deals, prices
- **Data:** quote lookup by the external quote number exactly as written; quote validity; vendor deal / bid / special-pricing numbers (vendor, customer, end user, validity, parts, authorised and used quantities); a customer's current price for a SKU (contract, public sector, education).
- **Samples:** valid quote; expired quote; quote converted to orders; quote that references a deal; expired deal; contract price for one customer and SKU.
- **Knowledge:** which number format belongs to which system; what may be said about prices; process after a deal expires; who produces a quote (order desk or vendor team).

### F Freight and ship-from warehouse
- **Data:** freight lines per order (freight, expedite, address-correction fees), third-party or customer freight accounts; free-freight rules per customer; warehouse cut-off times and time zones; the warehouse an order actually shipped from.
- **Samples:** order with free freight; order above the threshold still charged; overnight order on the customer's account; address-correction fee; shipped from a non-default warehouse.
- **Knowledge:** free-freight threshold and exceptions (heavy, overnight, drop-ship); who can waive freight; whether a ship-from warehouse can be chosen.

### G Supply: stock, incoming, lead time
- **Data:** stock per warehouse, open purchase-order lines, lead times (existing); plus SKU flags: stocked, non-cancellable, minimum order quantity, vendor drop-ship capability.
- **Samples:** stock in several warehouses; no stock but incoming; no stock, nothing incoming; incoming ETA already past; drop-ship-only vendor; SKU with a minimum order quantity.
- **Knowledge:** how "available" is computed; how lead times are quoted (business or calendar days, warehouse handling included or not); how web-shop stock relates to ERP stock.

### H Products: end of life, replacements, warranty, compliance
- **Data:** lifecycle and end-of-life date, replacement SKU; warranty / care-pack ↔ hardware mapping; country of origin, tariff code, weight and size; new-SKU setup status.
- **Samples:** end of life with a replacement; end of life without one; one device with its 2 valid warranties; an imported part with origin data; a SKU in setup.
- **Knowledge:** what happens to open orders when a part goes end of life; where warranty matching rules come from; who sets up new SKUs and how long it takes.

### I Customers and owners
- **Data:** customer master plus sales rep, desk and shared mailbox, parent/child accounts, payment terms, tax exemption.
- **Samples:** ordinary customer; parent with child accounts; tax-exempt customer; discontinued customer; restricted customer.
- **Knowledge:** rules from customer to desk to mailbox; which team owns account questions (portal login, account setup, merging accounts).

### J Order changes and cancellations (writes)
- **Data:** up to which stage an order can still be changed or cancelled (allocated, picked, vendor PO sent); change and cancellation log (what, why, who, when); writable test orders.
- **Samples:** cancellable back-ordered line; drop-ship line with the vendor PO already sent; picked order that can no longer change; order whose address can still change.
- **Knowledge:** the cut-off stage for each kind of change; vendor confirmation for drop-ship cancellations; non-cancellable rules; what a customer must supply for a change (company, full address, contact, phone).

## Procedures: what two desk procedures need (2026-11-20)

The desk standardises its work per business type: each type is a fixed procedure, and an assistant only does narrow, checked steps inside it (read the request, write a draft) while every system lookup is a fixed query. Two procedures are written so far. What they need beyond the inventory below:

### Order status (area A)

Steps: read the numbers → look up orders → apply the ETA rules → hand lines without a valid ETA to the ETA follow-up team → draft the reply → a person sends it → follow up.

| Need | Lookup | Fields | Status |
| --- | --- | --- | --- |
| Customer PO → sales orders | customer PO, as written (several spellings), customer | sales order / back order numbers, type, date, status | in inventory |
| Lines, shipments, tracking | sales order | SKU, ordered / shipped / back-ordered qty, cartons, carrier, tracking | in inventory |
| ETA per line | order line / vendor PO line | ETA, status code, source, revisions | in inventory |
| Open vendor PO lines per SKU | SKU + warehouse | all open PO lines, qty, vendor-portal ship date as given | in inventory |
| Deleted orders / lines | order or PO number | deleted flag, when, by whom | **new** |
| Software licence delivery | sales order | licence status, how it is delivered | **new** |

ETA rules the procedure applies (to be confirmed per company; they decide which fields matter): a valid ETA is a scheduled/shipped status with a date after today; a shipped status on a vendor PO means shipped to the distributor, not to the customer; partial receipt → ETA only for the open quantity; a vendor ship date not after today is past due and goes to the follow-up team; lead time is never a firm ETA.

### Order cancellation (area J)

Steps: read what to cancel → acknowledge → find the vendor PO line for each SKU → decide per line (cancellable / return instead) → find the vendor's purchasing contact → draft the cancellation request (no reseller data in it) → wait for the vendor → internal cancellation (vendor PO removed, sales order re-queued) → reply with the result.

| Need | Lookup | Fields | Status |
| --- | --- | --- | --- |
| Customer PO → sales order → vendor PO lines | customer PO + SKU | sales order, vendor PO, vendor, line, qty, status | in inventory |
| Shipment state per vendor PO line | vendor PO line | shipped qty, ship date, tracking | in inventory |
| **Purchasing contact per vendor** | vendor (or SKU) | primary buyer name and email, backup, team mailbox | **new** |
| **Vendor invoice state per vendor PO line** (payables side) | vendor PO + line | invoiced yes/no, vendor invoice number, date | **new** |
| **Vendor PO removal and sales order re-queue** | vendor PO, sales order | removed flag, when, by whom; re-queued flag, when | **new** |
| Cancellation rules | vendor, product class | when a line can still be cancelled, when it must be returned instead, vendor-specific steps | **new** (knowledge article) |

### Sub-line pegging (areas A, G, J)

One customer PO line is often split: part shipped from stock, the rest back-ordered, the back order covered by one or more vendor PO lines arriving in parts. Today ETA, shipment and tracking sit on whole lines, so "when do the remaining 221 units arrive?" has no exact answer. Needed: a **sub-line** record per split quantity, linked end to end:

customer PO line (qty) → sales order / back order sub-line (qty) → vendor PO line (qty) → its own ETA, receipt, shipment, tracking

| Entity | Key fields | Linked to |
| --- | --- | --- |
| Order sub-line | order, line, sub-line no, qty, status, ETA, ship date, carton / tracking | order line, vendor PO line |
| Pegging | customer PO line, order sub-line, vendor PO line, pegged qty, since | all three |

Until source systems keep sub-lines, the flows should return line-level data and say when a line is split, so answers never give one ETA for the whole line.

## Reference lookups and what a test run found (2026-11-20)

The fixed lookups a desk tool runs today for "order details", "is it fulfilled?" and "stock and incoming for a part". Every one is read-only and should be a Spindle flow (or one graph query) with exactly these inputs.

| # | Lookup | Input | Returns |
| --- | --- | --- | --- |
| L1 | Order by number | number, type (sales order, back order, PO) | header (type, kind, status, dates, deleted, customer PO, ship-to, total), customer, ship method, lines (SKU, part, ordered / shipped / received qty, status, price), cartons (tracking, ship date, POD), vendor POs behind it (vendor, status, lines, ETA codes and dates with `updated_at`) |
| L2 | Customer PO by number | PO as written | customer PO (status, date, total, end-user PO), customer, its orders as in L1 |
| L3 | Sales orders carrying a customer PO | PO as written | orders as in L1 (fallback when L2 finds nothing) |
| L4 | Product search | part number, symbols removed, upper case | SKU, vendor part, lifecycle status |
| L5 | Product supply | SKU | vendor and vendor lead time, stock per warehouse (available, on order, back-ordered), product lead time, open vendor PO lines with qty and ETA |
| K1 | Knowledge: vendor PO line next action | — | when to answer the customer and when to ask the vendor first |

An email gives a number without its type, so L1 runs once per type; a customer PO is tried in three spellings (as written, digits only, `PO-<digits>`) in L2 and L3. A typical conversation costs 10–15 lookups; on test data they take 0.3 s together.

What one run on test data showed — each is a requirement for the flows or for the test data:

1. **To-one links come back as one-element lists** (customer, product, vendor, ship method, warehouse). Callers that expect a single object show blanks. Flows should either return a single object for a to-one link or document the list shape.
2. **One customer PO number, two customers.** `239110` (a different customer, three months earlier) and `PO-239110` (the customer asking) both matched. When the conversation's own order numbers fix the customer, results for other customers must be dropped; L2 / L3 should accept a customer filter.
3. **Drop-ship order without ship or invoice date.** The order is closed and its line shows 1 shipped, yet ship date and invoice date are empty, so "not invoiced" is reported. The test data (and the flow) must say where a drop-ship shipment and its invoice are recorded.
4. **A vendor PO number given as the customer's PO.** The number was looked up as a customer PO and not found; it was the vendor PO behind the sales order. L2 / L3 finding nothing for an all-digit number should fall back to L1 with type PO.

## Full data inventory

30 entities. Prepare them together so numbers chain: customer → quote → customer PO → sales order → vendor PO → shipment → invoice → RMA → credit.

| Domain | Entity | Key fields | Linked to |
| --- | --- | --- | --- |
| Master | Customer | number, name, account type, territory, discontinued / restricted, credit status and limit, terms, tax exempt | parent, addresses, contacts, rep |
| Master | Customer address | ship-to / bill-to, state, postcode, default | customer |
| Master | Customer contact | name, email, role (buyer, finance, management) | customer |
| Master | Sales rep / desk | name, email, desk, shared mailbox | customer |
| Master | Vendor | number, name, type, drop-ship capable, discontinued | product |
| Master | Product (SKU) | SKU, our part, vendor part, description, lifecycle, end-of-life date, replacement, stocked, non-cancellable, MOQ, accepts returns, UPC, weight, origin, tariff code | vendor |
| Master | Warranty mapping | hardware SKU, warranty SKU, term, service type | product |
| Master | Warehouse | number, name, time zone, cut-off per service | stock, shipments |
| Master | Ship method | code, carrier, SCAC, service level, tracking URL template | shipments |
| Pricing | Price / contract price | customer, SKU, price type, price, validity | customer, product |
| Pricing | Deal / bid / special price | vendor deal no, our bid no, vendor, customer, end user, validity, parts, authorised and used qty | quote, order |
| Quote | Quote | internal id, external number, status, validity, total, deal, end user | customer, orders |
| Quote | Quote line | SKU, qty, price, converted / back-ordered / shipped qty | quote |
| Order | Customer PO | PO number, status, date, total, ship-to, end-user PO, channel (mail, EDI, portal) | customer, orders |
| Order | Sales order | number, type, role (drop-ship from customer PO, drop-ship from vendor PO, stock), status, dates, total, ship-to, ship method, warehouse, freight account | customer PO, vendor POs, invoices |
| Order | Order line | SKU, ordered / allocated / shipped / back-ordered qty, price, line status, non-cancellable | order, product |
| Order | Hold | order / line, type, reason, set by / at, released by / at | order |
| Order | Change log | order, line, change, reason, who, when | order |
| Purchasing | Vendor PO | number, type, vendor, referenced sales order, vendor's order number, status | sales order, vendor |
| Purchasing | PO line ETA | ETA, status code, source (EDI 855 / 856, manual), revisions, updated at, vendor tracking | vendor PO |
| Purchasing | ETA history | old value, new value, source, time | PO line |
| Supply | Stock | SKU, warehouse, on hand / allocated / available / on order / back ordered | product, warehouse |
| Supply | Lead time | SKU, vendor-to-warehouse days, drop-ship days | product |
| Shipping | Carton | carton no, ship date, carrier, tracking, POD time and signer | order |
| Shipping | Serial number | serial, SKU, carton, order line | carton, RMA |
| Finance | Invoice | number, date, due, amount, freight, tax, status (open, paid, disputed) | order, customer |
| Finance | Invoice line | SKU, qty, price, charge type (goods, freight, expedite, address correction) | invoice |
| Finance | Credit memo | number, invoice, amount, reason, status, date | invoice, RMA |
| Finance | Billing schedule | subscription / multi-year contract, period dates, amounts | order |
| After-sales | RMA request | request no, RMA no, customer ref, order / invoice, lines, serials, reason, status, label / call tag, receipt and inspection, replacement order, credit | order, invoice, serials |

**Preparation rules**
- **Chains:** at least 5 complete chains from quote to invoice; 2 continue to RMA and credit; 1 is drop-ship.
- **Volume:** at least 2 records for every sample case above.
- **Time:** dates over the last 90 days, open and closed records both; state the time zone.
- **Anonymised:** names and addresses may be fake, but number formats must match production (prefixes, suffixes, separators).
- **Code dictionary:** a table of every status, type, hold, and return-reason code with its meaning. It is the main source for graph descriptions and assistant instructions.

## Acceptance questions

| # | Area | Question | Must answer |
| --- | --- | --- | --- |
| 1 | A | "When do the remaining 2 units of PO X arrive?" | the order, shipped vs back-ordered qty, ETA and its source, answer or ask vendor |
| 2 | A | "Which orders came from this customer PO — all shipped?" | every order, its shipments and tracking, POD |
| 3 | B | "Stuck in allocated for days." | hold type and reason, since when, which team releases it |
| 4 | B | "Account is off restriction — will held orders ship?" | credit status, the customer's orders still on hold |
| 5 | C | "Invoice says 4 shipped, we got 2." | invoice lines, serials and tracking per carton, POD, who to contact |
| 6 | C | "When is year two invoiced?" | next billing date and amount |
| 7 | D | "Why no return label for request X?" | RMA status, current step and owner, who pays freight |
| 8 | D | "Is the credit memo done?" | credit found from the RMA or invoice, amount and status |
| 9 | E | "Is quote X still valid? Rush order." | quote status and validity, deal expiry, stock per line |
| 10 | F | "Why freight above the threshold?" | freight lines, the rule and its exceptions |
| 11 | G | "Order 260 of part X today — when do they arrive?" | SKU from the vendor part, available per warehouse, incoming, lead time in one query |
| 12 | H | "Part X is end of life — replacement? new warranty?" | replacement SKU, valid warranties, rule for open orders |

| 13 | A | "Line 1 of PO X: 38 shipped, the rest?" | each split quantity with its own status, ETA or tracking (sub-lines) |
| 14 | J | "Please cancel all of PO X." | every vendor PO line behind it, cancellable or not and why, the vendor's purchasing contact |
| 15 | J | "Has the vendor invoiced line 2?" | vendor invoice state for that vendor PO line |
| 16 | J | "Is the cancellation finished?" | vendor confirmation, vendor PO removed, sales order re-queued, with times |

Pass: answers match the test data, cite the documented rules, and questions 1, 2, 11 and 13 take one graph query.
