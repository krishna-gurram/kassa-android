#!/usr/bin/env python3
"""Writes a realistic, entirely synthetic Kassa backup for trying the apps and for tests.

Output (app/src/test/resources/backup-demo/): demo.kassa (no password), demo-protected.kassa (password "demo password")
and expected.json, the figures worked out here in plain Python from the entries (no Kassa code), which the tests compare
against Android's reader and the shared rules. Nothing here is anyone's real data. Run it again to regenerate; it is
deterministic.
"""
import datetime as dt, gzip, hashlib, json, os, random
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

rnd = random.Random(2026)
EPOCH = dt.date(1970, 1, 1)
def eday(d): return (d - EPOCH).days
def millis(d): return eday(d) * 86_400_000 + 12 * 3_600_000       # noon UTC: the same date in every Americas/Europe zone
def half_up(n, d): return (2 * n + d) // (2 * d)                    # integers only, like Kassa
TODAY = dt.date(2026, 11, 3)

# ---------- categories ----------
cats = [
    (1, "Groceries", "EXPENSE", "cart", 3), (2, "Dining", "EXPENSE", "restaurant", 5), (3, "Home", "EXPENSE", "home", 1),
    (4, "Bills", "EXPENSE", "bills", 7), (5, "Transport", "EXPENSE", "bus", 9), (6, "Subscriptions", "EXPENSE", "subscriptions", 11),
    (7, "Salary", "INCOME", "payments", 2), (8, "Interest", "INCOME", "savings", 4), (9, "Loan interest", "EXPENSE", "bills", 0),
    (10, "Health", "EXPENSE", "medical", 6),
]
categories = [{"id": i, "name": n, "kind": k, "icon": ic, "color": c, "countsTowardDaily": k == "EXPENSE", "hidden": False, "sortOrder": i}
              for (i, n, k, ic, c) in cats]

# ---------- accounts ----------
CHK, SAV, CASH, CARD, LOAN, BROK, OLD = 1, 2, 3, 4, 5, 6, 7
accounts = [
    {"id": CHK, "name": "Everyday checking", "type": "CHECKING", "openingBalance": 250_000, "archived": False, "sortOrder": 0},
    {"id": SAV, "name": "Rainy day savings", "type": "SAVINGS", "openingBalance": 1_500_000, "archived": False, "sortOrder": 1},
    {"id": CASH, "name": "Wallet", "type": "CASH", "openingBalance": 20_000, "archived": False, "sortOrder": 2},
    {"id": CARD, "name": "Travel card", "type": "CREDIT_CARD", "openingBalance": 0, "statementDay": 5, "dueDay": 25, "payFromId": CHK,
     "creditLimit": 800_000, "last4": "4242", "archived": False, "sortOrder": 3},
    {"id": LOAN, "name": "Car loan", "type": "LOAN", "openingBalance": -1_800_000, "archived": False, "sortOrder": 4},
    {"id": BROK, "name": "Brokerage", "type": "INVESTMENT", "openingBalance": 0, "archived": False, "sortOrder": 5},
    {"id": OLD, "name": "Old savings", "type": "SAVINGS", "openingBalance": 0, "archived": True, "sortOrder": 6, "closedDay": eday(dt.date(2026, 6, 30))},
]

tx, nid = [], [0]
def add(kind, amount, d, acct, cat=None, to=None, note="", person=None):
    nid[0] += 1
    row = {"id": nid[0], "kind": kind, "amount": amount, "epochMillis": millis(d), "note": note, "accountId": acct}
    if cat is not None: row["categoryId"] = cat
    if to is not None: row["toAccountId"] = to
    if person is not None: row["personId"] = person
    tx.append(row)
    return nid[0]

# ---------- twelve months and a bit ----------
months = [(2025, 11), (2025, 12)] + [(2026, m) for m in range(1, 12)]
loan_owed = 1_800_000
loan_payments, rate, payment = [], 61_250, 35_000
for (y, m) in months:
    def day(n):
        return dt.date(y, m, n)
    last = (m == 11 and y == 2026)                                    # November 2026: only the first two days exist
    add("EXPENSE", 180_000, day(1), CHK, 3, note="Rent")
    add("INCOME", 260_000, day(1), CHK, 7, note="Payroll")
    add("EXPENSE", 1_599, day(2), CARD, 6, note="Streamly")
    if last: continue
    add("INCOME", 260_000, day(15), CHK, 7, note="Payroll")
    add("EXPENSE", 4_500, day(8), CARD, 6, note="Gym membership")
    add("EXPENSE", rnd.randrange(11_000, 19_000), day(5), CHK, 4, note="Power and water")
    add("TRANSFER", 50_000, day(10), CHK, to=SAV, note="To savings")
    add("INCOME", rnd.randrange(280, 340), day(28), SAV, 8, note="Interest")
    for i, n in enumerate((2, 6, 11, 16, 21, 26)):
        add("EXPENSE", rnd.randrange(4_000, 13_000), day(n), CARD if i % 2 else CHK, 1, note="Grocery run")
    for n in (7, 13, 19, 24):
        add("EXPENSE", rnd.randrange(1_500, 6_800), day(n), CARD, 2, note="Dinner out")
    add("TRANSFER", 10_000, day(9), CHK, to=CASH, note="Cash for the wallet")
    for n in (12, 14, 18, 22):
        add("EXPENSE", rnd.randrange(450, 650), day(n), CASH, 2, note="Coffee")
    add("EXPENSE", rnd.randrange(6_000, 8_000), day(17), CARD, 5, note="Fuel")
    # card payment on the 25th: last month's card spending
    prev = (y, m - 1) if m > 1 else (y - 1, 12)
    spent = sum(t["amount"] for t in tx if t["kind"] == "EXPENSE" and t["accountId"] == CARD
                and (dt.date.fromordinal(t["epochMillis"] // 86_400_000 + EPOCH.toordinal()).year,
                     dt.date.fromordinal(t["epochMillis"] // 86_400_000 + EPOCH.toordinal()).month) == prev)
    if spent: add("TRANSFER", spent, day(25), CHK, to=CARD, note="Card payment")
    # loan payment on the 15th: interest on what is owed, the rest is principal
    interest = half_up(loan_owed * rate, 12 * 1_000_000)
    principal = payment - interest
    pid = add("TRANSFER", principal, day(15), CHK, to=LOAN, note="Loan payment")
    iid = add("EXPENSE", interest, day(15), CHK, 9, note="Loan payment")
    loan_payments.append({"id": len(loan_payments) + 1, "accountId": LOAN, "day": eday(day(15)), "amount": payment, "principalEntryId": pid,
                          "interestEntryId": iid, "escrow": 0, "charge": 0, "edited": False, "estimate": interest, "scheduled": True})
    loan_owed -= principal

# one-off things
SAM = 1
add("INCOME", 5_000, dt.date(2026, 5, 1), OLD, 7, note="Refund")
add("TRANSFER", 5_000, dt.date(2026, 6, 30), OLD, to=SAV, note="Closing the account")
add("PERSON_OUT", 10_000, dt.date(2026, 3, 12), CHK, person=SAM, note="Lent for a deposit")
add("PERSON_IN", 3_000, dt.date(2026, 5, 20), CHK, person=SAM, note="Paid back some")
add("TRANSFER", 300_000, dt.date(2026, 2, 2), SAV, to=BROK, note="To brokerage")
buy = add("BUY", 200_000, dt.date(2026, 2, 3), BROK, note="ABC")
add("ADJUST_OUT", 150, dt.date(2026, 9, 30), CASH, note="Balance correction")
add("EXPENSE", 23_000, dt.date(2026, 4, 9), CHK, 10, note="Dentist")
tx.sort(key=lambda t: (t["epochMillis"], t["id"]))

doc = {
    "version": 1, "createdAt": millis(TODAY), "monthStartDay": 1, "currency": "USD",
    "groups": [], "accounts": accounts, "categories": categories, "transactions": tx,
    "budgets": [{"categoryId": 1, "month": 0, "amount": 60_000}, {"categoryId": 2, "month": 0, "amount": 25_000},
                {"categoryId": 2, "month": 202610, "amount": 30_000}],
    "people": [{"id": SAM, "name": "Sam", "archived": False}],
    "loans": [{"id": 1, "personId": SAM, "amount": 4_500, "epochMillis": millis(dt.date(2026, 1, 10)), "note": "Dinner split"}],
    "recurring": [], "holdings": [{"id": 1, "accountId": BROK, "ticker": "ABC", "microShares": 12_500_000, "price": 18_000,
                                   "priceMillis": millis(TODAY), "avgCost": 16_000}],
    "trades": [{"id": 1, "transactionId": buy, "ticker": "ABC", "microShares": 12_500_000, "price": 16_000}],
    "loanTerms": [{"accountId": LOAN, "type": "REDUCING", "profit": False, "rate": rate, "payment": payment, "termMonths": 60,
                   "originalAmount": 2_000_000, "startDay": eday(dt.date(2024, 1, 15)), "nextPaymentDay": eday(dt.date(2026, 12, 15)),
                   "dueDay": 15, "escrow": 0, "fee": 0, "earlyCharge": 0, "interestCategoryId": 9, "payFromId": CHK, "countAllAsSpending": False}],
    "loanChanges": [], "loanPayments": loan_payments, "investmentTransfers": [], "icons": {},
}

# ---------- the expected figures, from the entries alone ----------
bal = {a["id"]: a["openingBalance"] for a in accounts}
for t in tx:
    up = t["kind"] in ("INCOME", "PERSON_IN", "SELL", "ADJUST_IN")
    bal[t["accountId"]] += t["amount"] if up else -t["amount"]
    if t["kind"] == "TRANSFER": bal[t["toAccountId"]] += t["amount"]
holdings_value = half_up(12_500_000 * 18_000, 1_000_000)
sam = 4_500 + 10_000 - 3_000
open_ids = [a["id"] for a in accounts if "closedDay" not in a]
assets = sum(bal[i] for i in open_ids if accounts[i - 1]["type"] not in ("CREDIT_CARD", "LOAN")) + holdings_value + sam
liabilities = -(bal[CARD] + bal[LOAN])
def month_totals(y, m):
    spend = inc = 0
    for t in tx:
        d = dt.date.fromordinal(t["epochMillis"] // 86_400_000 + EPOCH.toordinal())
        if (d.year, d.month) != (y, m): continue
        if t["kind"] == "EXPENSE": spend += t["amount"]
        if t["kind"] == "INCOME": inc += t["amount"]
    return {"spending": spend, "income": inc}
expected = {
    "todayEpochDay": eday(TODAY), "entries": len(tx), "accounts": len(accounts), "categories": len(categories),
    "balances": {str(k): v for k, v in bal.items()}, "holdingsValue": holdings_value, "samOwesYou": sam,
    "assets": assets, "liabilities": liabilities, "netWorth": assets - liabilities,
    "loanOwed": -bal[LOAN], "cardOwed": -bal[CARD],
    "months": {"2026-10": month_totals(2026, 10), "2026-04": month_totals(2026, 4), "2025-12": month_totals(2025, 12)},
}

out = os.path.join(os.path.dirname(__file__), "..", "app", "src", "test", "resources", "backup-demo")
body = gzip.compress(json.dumps(doc, ensure_ascii=False, separators=(",", ":")).encode(), mtime=0)
open(os.path.join(out, "demo.kassa"), "wb").write(b"KASSA0" + body)
salt, iv = hashlib.sha256(b"kassa demo salt").digest()[:16], hashlib.sha256(b"kassa demo iv").digest()[:12]
key = hashlib.pbkdf2_hmac("sha256", b"demo password", salt, 210_000, 32)
open(os.path.join(out, "demo-protected.kassa"), "wb").write(b"KASSA1" + salt + iv + AESGCM(key).encrypt(iv, body, None))
json.dump(expected, open(os.path.join(out, "expected.json"), "w"), indent=1)
print(len(tx), "entries;", json.dumps({k: expected[k] for k in ("assets", "liabilities", "netWorth", "loanOwed", "cardOwed")}))
