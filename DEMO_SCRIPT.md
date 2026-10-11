# Demo video script — Expense Agent (3 min, single take + captions)

Target: YouTube unlisted/link, 1080p, live UI at :8081 + voiceover. Total ~3:00.

## 0:00–0:20 — Hook (upload page `/`)
- "Six crumpled phone photos of Indian restaurant bills. Watch them become a finance-ready claim."
- Show 6 files in picker. Click Upload & extract. Loader counts bill 1..6.

## 0:20–1:10 — Extraction (one-by-one + loader)
- Narrate providers while it runs: "Offline GLM-OCR vision transcribes each photo; deterministic rules structure it; arithmetic reconciles every total against subtotal plus tax."
- Point at a result: vendor, date, decimal-exact amount.

## 1:10–1:50 — Review page (`Review & submit →`)
- Summary table + pre-filled category dropdowns. Change one category.
- Answer 1 confirm question ("We read 514.50…") to show human-in-the-loop.
- Attach one more bill to show claims keep growing.
- Policy checks clean → Submit → `MOCK-...` reference.

## 1:50–2:30 — Finance (`/review.html`)
- Queue shows SUBMITTED only. Open claim, click receipt preview side-by-side.
- Approve → queue empties. Show a rejection with mandatory reason on second claim.

## 2:30–3:00 — Engineering proof (Swagger + tests)
- `/swagger-ui.html`: fire one API call live.
- Terminal: `mvn test` → 59 tests green (pre-record, show tail). Mention 79% coverage.
- Close: "Two vision routes, zero regex guessing, every amount reconciled. Links in description."

## Shot list (record in order)
1. `http://localhost:8081/` fresh claim + 6 uploads.
2. `submit.html` edit + confirm + submit.
3. `review.html` approve + reject-with-reason.
4. Swagger try-it + terminal test tail.
