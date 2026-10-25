"""Writes a made-up Cashew backup (Cashew's SQLite layout, schema 46) for the emulator walkthrough. No real data."""
import sqlite3, sys, os

path = sys.argv[1]
if os.path.exists(path):
    os.remove(path)
db = sqlite3.connect(path)
c = db.cursor()
c.execute('CREATE TABLE wallets (wallet_pk TEXT PRIMARY KEY, name TEXT, colour TEXT, icon_name TEXT, date_created INTEGER, date_time_modified INTEGER, "order" INTEGER, currency TEXT, currency_format TEXT, decimals INTEGER, home_page_widget_display TEXT)')
c.execute('CREATE TABLE categories (category_pk TEXT PRIMARY KEY, name TEXT, colour TEXT, icon_name TEXT, emoji_icon_name TEXT, date_created INTEGER, date_time_modified INTEGER, "order" INTEGER, income INTEGER, method_added INTEGER, main_category_pk TEXT)')
c.execute('CREATE TABLE transactions (transaction_pk TEXT PRIMARY KEY, paired_transaction_fk TEXT, name TEXT, amount REAL, note TEXT, category_fk TEXT, sub_category_fk TEXT, wallet_fk TEXT, date_created INTEGER, date_time_modified INTEGER, original_date_due INTEGER, income INTEGER, period_length INTEGER, reoccurrence INTEGER, end_date INTEGER, upcoming_transaction_notification INTEGER, type INTEGER, paid INTEGER, created_another_future_transaction INTEGER, skip_paid INTEGER, method_added INTEGER, transaction_owner_email TEXT, transaction_original_owner_email TEXT, shared_key TEXT, shared_old_key TEXT, shared_status INTEGER, shared_date_updated INTEGER, shared_reference_budget_pk TEXT, objective_fk TEXT, objective_loan_fk TEXT, budget_fks_exclude TEXT)')
c.execute('CREATE TABLE objectives (objective_pk TEXT PRIMARY KEY, type INTEGER, name TEXT, amount REAL, "order" INTEGER, colour TEXT, date_created INTEGER, end_date INTEGER, date_time_modified INTEGER, icon_name TEXT, emoji_icon_name TEXT, income INTEGER, pinned INTEGER, archived INTEGER, wallet_fk TEXT)')
c.execute('CREATE TABLE budgets (budget_pk TEXT PRIMARY KEY, name TEXT, amount REAL, colour TEXT, start_date INTEGER, end_date INTEGER, wallet_fks TEXT, category_fks TEXT, category_fks_exclude TEXT, income INTEGER, archived INTEGER, added_transactions_only INTEGER, period_length INTEGER, reoccurrence INTEGER, date_created INTEGER, date_time_modified INTEGER, pinned INTEGER, "order" INTEGER, wallet_fk TEXT, budget_transaction_filters TEXT, member_transaction_filters TEXT, shared_key TEXT, shared_owner_member INTEGER, shared_date_updated INTEGER, shared_members TEXT, shared_all_members_ever TEXT, is_absolute_spending_limit INTEGER)')
c.execute('CREATE TABLE category_budget_limits (category_limit_pk TEXT PRIMARY KEY, category_fk TEXT, budget_fk TEXT, amount REAL, date_time_modified INTEGER, wallet_fk TEXT)')
c.execute('CREATE TABLE app_settings (settings_pk INTEGER PRIMARY KEY, settings_j_s_o_n TEXT, date_updated INTEGER)')
c.execute("INSERT INTO app_settings VALUES (0, '{\"selectedWalletPk\":\"0\"}', 0)")

T0 = 1777593600  # 2026-05-01 UTC, seconds
DAY = 86400
c.executemany('INSERT INTO wallets (wallet_pk, name, "order", currency, decimals) VALUES (?,?,?,?,2)', [
    ("0", "Everyday", 0, "usd"), ("w2", "Rewards card", 1, "usd"), ("w3", "Europe trip", 2, "eur")])
c.executemany('INSERT INTO categories (category_pk, name, colour, icon_name, emoji_icon_name, "order", income, main_category_pk) VALUES (?,?,?,?,?,?,?,?)', [
    ("0", "Balance Correction", None, "charts.png", None, 0, 0, None),
    ("food", "Dining", "0xff607d8b", "cutlery.png", None, 1, 0, None),
    ("cafe", "Coffee", None, "coffee-cup.png", None, 2, 0, "food"),
    ("groc", "Groceries", "0xff4caf50", "groceries.png", None, 3, 0, None),
    ("home", "Home", "0xff795548", "house.png", None, 4, 0, None),
    ("subs", "Subscriptions", "0xffe91e63", None, "📺", 5, 0, None),
    ("pay", "Salary", "0xff9575cd", "coin.png", None, 6, 1, None)])
c.executemany('INSERT INTO objectives (objective_pk, type, name, amount, "order", date_created, income, archived) VALUES (?,?,?,?,?,?,?,0)', [
    ("M", 1, "Mortgage", 312000.0, 0, T0, 0), ("S", 1, "Sam", 600.0, 1, T0, 1), ("G", 0, "Holiday fund", 2000.0, 2, T0, 0)])
rows = []
n = 0
def tx(amount, wallet, cat, t, name="", sub=None, paired=None, paid=1, typ=None, re=None, period=None, loan=None, pk=None):
    global n
    n += 1
    rows.append((pk or f"t{n}", paired, name, amount, "", cat, sub, wallet, t, 1 if amount > 0 else 0, period, re, typ, paid, 0, loan))
tx(1500.0, "0", "0", T0, "Opening balance")
for m in range(6):
    base = T0 + m * 30 * DAY
    tx(2480.0, "0", "pay", base + 1 * DAY, "Payroll")
    tx(2480.0, "0", "pay", base + 15 * DAY, "Payroll")
    tx(-1850.0, "0", "home", base + 2 * DAY, "Mortgage payment", loan="M")
    tx(-15.49, "w2", "subs", base + 14 * DAY, "Streaming", typ=1, re=3, period=1)
    for d in range(0, 28, 3):
        tx(-round(40 + (d * 7 + m * 13) % 90 + 0.37, 2), "w2", "groc", base + d * DAY + 3600, "Corner market")
        tx(-4.75, "0", "food", base + d * DAY + 7200, "Latte", sub="cafe")
    tx(-62.10, "w2", "food", base + 20 * DAY, "Noodle house")
    pay = f"p{m}"
    tx(-900.0, "0", "0", base + 25 * DAY, "Card payment", pk=pay + "o")
    tx(900.0, "w2", "0", base + 25 * DAY, "Card payment", paired=pay + "o", pk=pay + "i")
tx(-600.0, "0", "food", T0 + 10 * DAY, "Lent to Sam", loan="S")
tx(200.0, "0", "pay", T0 + 40 * DAY, "Sam paid back", loan="S")
tx(-400.0, "0", "0", T0 + 60 * DAY, "Euros for the trip", pk="eo")
tx(370.0, "w3", "0", T0 + 60 * DAY, "Euros for the trip", paired="eo", pk="ei")
tx(-38.0, "w3", "food", T0 + 65 * DAY, "Café in Lisbon")
tx(55.0, "w2", "groc", T0 + 70 * DAY, "Refund, damaged item")
tx(-15.49, "w2", "subs", T0 + 194 * DAY, "Streaming", paid=0, typ=1, re=3, period=1)
tx(-99.0, "w2", "subs", T0 + 200 * DAY, "Yearly app", paid=0, typ=1, re=4, period=1)
tx(-120.0, "0", "home", T0 + 190 * DAY, "Plumber", paid=0, typ=0)
c.executemany('INSERT INTO transactions (transaction_pk, paired_transaction_fk, name, amount, note, category_fk, sub_category_fk, wallet_fk, date_created, income, period_length, reoccurrence, type, paid, skip_paid, objective_loan_fk) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)', rows)
c.execute('INSERT INTO budgets (budget_pk, name, amount, archived, period_length, reoccurrence, "order", is_absolute_spending_limit, start_date, end_date) VALUES ("b", "Monthly", 3000.0, 0, 1, 3, 0, 1, 0, 0)')
c.executemany('INSERT INTO category_budget_limits (category_limit_pk, category_fk, budget_fk, amount) VALUES (?,?,?,?)', [("l1", "groc", "b", 650.0), ("l2", "food", "b", 250.0)])
db.commit()
db.close()
print(f"{path}: {len(rows)} transactions")
