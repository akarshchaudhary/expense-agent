# Submission checklist — push + host + submit

## 1. Pre-push secret scan (do this first)
```powershell
cd D:\ai\expense-agent
# must print NOTHING (key lives only in git-ignored .env):
Get-ChildItem -Recurse -Include *.java,*.yml,*.yaml,*.md -Exclude target,node_modules | Select-String -Pattern 'AQ\.Ab8\S{10,}|AIza[0-9A-Za-z_-]{20,}|sk-ant-[A-Za-z0-9-]{10,}'
git check-ignore .env   # must print: .env
```

## 2. Push to personal GitHub (new public repo `expense-agent`)
```powershell
cd D:\ai\expense-agent
git init -b main 2>$null; git add .; git status --short | head -30
git commit -m "Expense & Reimbursement Agent - AI Innovation Lab Season 2"
gh repo create expense-agent --public --source=. --push
# Without gh CLI: create empty public repo on github.com, then:
# git remote add origin https://github.com/<you>/expense-agent.git; git push -u origin main
```

## 3. Host (Render free, no GPU needed)
1. Render → New → Blueprint → select repo (`render.yaml`).
2. Dashboard env: `GEMINI_API_KEY=<key>` (+ optional `GEMINI_MODEL=gemini-3.8-flash`).
   Leave unset = fully offline tesseract+rules mode (judges never blocked).
3. Verify: `https://<service>.onrender.com/readyz` → `{"status":"ready"}`.

## 4. Submit packet
- GitHub link (public, no permission asks).
- `DESIGN.md` (in repo) + demo YouTube link (see `DEMO_SCRIPT.md`).
- Hosted link if deployed.
- Note for judges: no keys required — offline path works out of the box;
  `EXTRACTOR_PROVIDER=rule|gemini|glmocr` switch documented in README.
