# The .kassa backup format (version 1)

One format for every Kassa app. A backup made on Android opens on iOS and the other way round. The Android reader and
writer are the reference (`app/data/backup`); this page is what a second implementation needs.

## File

| Bytes | Meaning |
|---|---|
| 0-5 | `KASSA0` (no password) or `KASSA1` (password), ASCII |

**`KASSA0`**: the rest of the file is the JSON, UTF-8, gzip-compressed.

**`KASSA1`**: bytes 6-21 are a random 16-byte salt, 22-33 a random 12-byte IV, and the rest is AES-256-GCM over the same
gzip bytes, with the 16-byte tag at the very end (ciphertext then tag, no additional data).
Key = PBKDF2-HMAC-SHA256(password as UTF-8, salt, 210,000 iterations, 32 bytes).

Reading rules (both apps):
- Anything else at the start, or a file cut short, is "not a Kassa backup".
- A `KASSA1` file with no password asks for one. A wrong password, a changed byte or a missing tag is "wrong password",
  and nothing is parsed until the tag has checked.
- The unpacked JSON may be at most 256 MB; more is refused.
- A new backup uses a fresh random salt and IV every time. An empty password means `KASSA0`.

## JSON

One object. Field names are the format: never rename one, add optional ones instead. Unknown keys are skipped by every
reader. `"version"` is 1; a file with a higher number is refused with "This backup is from a newer version of Kassa. Update
Kassa to open it."

Top level: `version`, `createdAt` (epoch ms), `monthStartDay`, optional `currency` (code) and `accountArrangement`
(string), then these tables, each an array of objects (a missing table reads as empty): `groups`, `accounts`,
`categories`, `transactions`, `budgets`, `people`, `loans`, `recurring`, `holdings`, `trades`, `loanTerms`, `loanChanges`,
`loanPayments`, `investmentTransfers`; and `icons`, an object of file name to base64 bytes.

Rows have the fields of the matching writer in `BackupContents.kt` (`BackupWriter`). **A field the writer writes with `opt`
is optional (leave it out when null); every other field is required, and a row missing one makes the file "not a Kassa
backup".** A writer must therefore send every required field of every row, even when it is 0, false or "".
Rules that matter on every platform:
- Money is an integer in minor units (cents), never a decimal. Shares are millionths (`microShares`). Loan rates are
  1/10000 of a percent. Dates are epoch-day integers (`startDay`, `closedDay`, ...) or epoch milliseconds
  (`epochMillis`, `createdAt`).
- A budget's `month` is `yyyymm` as an integer (202610), and 0 is the budget for every month.
- Kinds and types are the stored text (`EXPENSE`, `INCOME`, `TRANSFER`, `ADJUST_IN`, `ADJUST_OUT`, ...).
- Ids are integers that link rows within the file; a restore replaces everything and keeps them.

## Keeping it in step

- A new table or column that holds user data gets an optional JSON field here and in `BackupContents`, plus a line in the
  round-trip tests. Older files must still read.
- `app/src/test/resources/backup-golden/` holds files written by `tools/make_golden_backups.py` from this page alone (no
  shared code): `plain.kassa`, `protected.kassa` (password `golden test password`) and `newer.kassa`. `BackupGoldenTest`
  opens them on Android; the iOS app's tests must open the same files and read the same values.
- iOS must also write files Android opens: its test writes a backup, and CI feeds it to `BackupGoldenTest`'s reader
  (see the iOS plan).
