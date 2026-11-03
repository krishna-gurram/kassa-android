#!/usr/bin/env python3
"""Writes the golden .kassa files in app/src/test/resources/backup-golden from docs/BACKUP_FORMAT.md alone.

It shares no code with the Android writer, so a file it makes that Android opens proves the written format is complete.
All data is synthetic. Salt and IV are fixed here only so the files are reproducible; real backups use random ones.
"""
import gzip, hashlib, json, os, sys
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

PASSWORD = "golden test password"
SALT = bytes(range(16))
IV = bytes(range(100, 112))

DOC = {
    "version": 1, "createdAt": 1767225600000, "monthStartDay": 1, "currency": "USD", "accountArrangement": "G:cash,card",
    "groups": [{"id": 1, "name": "Everyday", "sortOrder": 0}],
    "accounts": [
        {"id": 1, "name": "Checking", "type": "CHECKING", "groupId": 1, "openingBalance": 150000, "archived": False, "sortOrder": 0},
        {"id": 2, "name": "Card", "type": "CREDIT_CARD", "openingBalance": 0, "statementDay": 5, "dueDay": 25, "payFromId": 1, "creditLimit": 500000, "archived": False, "sortOrder": 1, "last4": "0000"},
        {"id": 3, "name": "Car loan", "type": "LOAN", "openingBalance": -1200000, "archived": False, "sortOrder": 2, "closedDay": 20500},
    ],
    "categories": [
        {"id": 1, "name": "Café ☕", "kind": "EXPENSE", "icon": "coffee", "color": 3, "countsTowardDaily": True, "hidden": False, "sortOrder": 0},
        {"id": 2, "name": "Salary", "kind": "INCOME", "icon": "work", "color": 5, "countsTowardDaily": False, "hidden": False, "sortOrder": 1},
    ],
    "transactions": [
        {"id": 1, "kind": "EXPENSE", "amount": 450, "accountId": 1, "categoryId": 1, "epochMillis": 1767139200000, "note": "Latte ₹1,00,000"},
        {"id": 2, "kind": "INCOME", "amount": 99999999, "accountId": 1, "categoryId": 2, "epochMillis": 1767052800000, "note": ""},
        {"id": 3, "kind": "TRANSFER", "amount": 25000, "accountId": 1, "toAccountId": 3, "epochMillis": 1766966400000, "note": "Loan principal"},
    ],
    "budgets": [{"categoryId": 1, "month": "2026-01", "amount": 20000}],
    "people": [{"id": 1, "name": "Sam", "archived": False}],
    "loans": [], "recurring": [], "holdings": [], "trades": [],
    "loanTerms": [{"accountId": 3, "type": "REDUCING", "profit": False, "rate": 61250, "payment": 25000, "termMonths": 60,
                   "nextPaymentDay": 20560, "dueDay": 15, "escrow": 0, "fee": 0, "earlyCharge": 0, "countAllAsSpending": False}],
    "loanChanges": [], "loanPayments": [], "investmentTransfers": [],
    "icons": {"photo.png": "iVBORw0KGgo="},
    "someFutureField": {"ignored": [1, 2, 3]},
}

out = os.path.join(os.path.dirname(__file__), "..", "app", "src", "test", "resources", "backup-golden")
os.makedirs(out, exist_ok=True)
body = gzip.compress(json.dumps(DOC, ensure_ascii=False, separators=(",", ":")).encode("utf-8"), mtime=0)
open(os.path.join(out, "plain.kassa"), "wb").write(b"KASSA0" + body)
key = hashlib.pbkdf2_hmac("sha256", PASSWORD.encode("utf-8"), SALT, 210_000, 32)
open(os.path.join(out, "protected.kassa"), "wb").write(b"KASSA1" + SALT + IV + AESGCM(key).encrypt(IV, body, None))
newer = dict(DOC, version=2)
open(os.path.join(out, "newer.kassa"), "wb").write(b"KASSA0" + gzip.compress(json.dumps(newer).encode(), mtime=0))
print("ok")
