# Expense Agent (Spring Boot) — receipts to ready-to-submit

AI Innovation Lab Season 2 — Track 1: Expense & Reimbursement Agent.

Three pages: `http://localhost:8081/` (create + upload) → `/submit.html` (summary + edit + submit) → `/review.html` (finance approve/reject). Swagger at `/swagger-ui.html`.

Two vision routes: offline GLM-OCR via Ollama (`vision` profile, free, no key) or free Gemini Flash (`GEMINI_API_KEY`).

## Run
```bash
cd expense-agent
.\mvnw.cmd spring-boot:run
# UI http://localhost:8081/  health /healthz  ready /readyz  swagger /swagger-ui.html
.\mvnw.cmd clean test   # 59 tests + JaCoCo report (target/site/jacoco/), all offline
docker compose up --build  # Postgres + tesseract, :8081
```

## Deploy A — offline (no keys, no internet needed for extraction)
```bash
docker compose --profile vision up --build -d   # app + Postgres + Ollama
docker compose exec ollama ollama pull glm-ocr  # one-time model download (~2GB)
# .env: EXTRACTOR_PROVIDER=glmocr
```
Needs ~8GB RAM; a 4GB NVIDIA GPU makes it ~8s/bill (CPU works, slower).
Tesseract + rules still cover anything vision misses, so it never hard-fails.

## Deploy B — Gemini cloud (fastest, free tier)
1. Get a free key: open **Google AI Studio** (aistudio.google.com) → sign in
   with any Google account → **Get API key** → **Create API key** (no billing,
   free tier is enough for this project) → copy the key.
2. Put it in git-ignored `.env` (never commit it):
   `GEMINI_API_KEY=<your-key>` and `EXTRACTOR_PROVIDER=gemini` (or `auto`).
3. `docker compose up --build -d` (Ollama not needed).
4. On Render/Railway hosting, set `GEMINI_API_KEY` (+ optional
   `GEMINI_MODEL`, default `gemini-3.8-flash`) in the dashboard env vars.

## API
- `POST /api/claims {employeeId,title}` → `201`
- `POST /api/claims/{id}/receipts` multipart `files` → `{created,skipped,extractor}`
- `GET /api/claims/{id}` → `{items,questions,violations,total,status}`
- `POST /api/claims/{id}/items/{itemId}/answers {field,value}`
- `POST /api/claims/{id}/submit` → `{mockReference}` or `409`
- `POST /api/claims/{id}/review {approved,reason}` → finance approve/reject
- `GET /api/claims/{id}/receipts/{receiptId}` → original file bytes
- `GET /api/claims/categories` → fixed category set

## Env
`PORT, DATABASE_URL, EXTRACTOR_PROVIDER=auto|gemini|glmocr, GEMINI_API_KEY, GEMINI_MODEL, OLLAMA_BASE_URL, GLMOCR_MODEL, POLICY_MAX_TOTAL, POLICY_RECEIPT_ABOVE, POLICY_MAX_AGE_DAYS`

See `DESIGN.md` for full design doc, diagrams, AI choices, deployment (Render/Railway).
