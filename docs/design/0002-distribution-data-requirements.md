# 0002 — Data requirements for a distributor's order desk

Status: draft (2026-11-19). Purpose: the data a test environment should hold, and the business knowledge to write down, so that Spindle flows and a semantic graph on top of them can answer what customers ask an IT distributor's order desk by email.

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

Pass: answers match the test data, cite the documented rules, and questions 1, 2 and 11 take one graph query.
