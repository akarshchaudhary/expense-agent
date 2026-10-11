const $ = (id) => document.getElementById(id);
const claimId = new URLSearchParams(location.search).get('claimId');
let CATS = ['Food', 'Travel', 'Stay', 'Office', 'Medical', 'Entertainment', 'Other'];

async function api(path, opts = {}) {
  const r = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...opts });
  const t = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(t.error || r.statusText);
  return t;
}

function catSelect(it) {
  const s = document.createElement('select');
  const cur = it.category || '';
  if (!cur) {
    const o = document.createElement('option');
    o.value = ''; o.textContent = '? pick one'; s.appendChild(o);
  }
  for (const c of CATS) {
    const o = document.createElement('option');
    o.value = c; o.textContent = c;
    if (c === cur) o.selected = true;
    s.appendChild(o);
  }
  s.onchange = async () => {
    await api('/api/claims/' + claimId + '/items/' + it.id + '/answers',
      { method: 'POST', body: JSON.stringify({ field: 'category', value: s.value }) });
    await draw();
  };
  return s;
}

function editCell(it, field, value, width) {
  const td = document.createElement('td');
  const inp = document.createElement('input');
  inp.value = value ?? '';
  inp.placeholder = field;
  inp.style.width = width || '90px';
  inp.onchange = async () => {
    if (inp.value !== String(value ?? '')) {
      await api('/api/claims/' + claimId + '/items/' + it.id + '/answers',
        { method: 'POST', body: JSON.stringify({ field, value: inp.value }) });
      await draw();
    }
  };
  td.appendChild(inp);
  return td;
}

async function draw() {
  if (!claimId) { document.body.innerHTML = '<p>No claim selected. <a href="/">Create one →</a></p>'; return; }
  const c = await api('/api/claims/' + claimId);
  $('claimName').textContent = c.title + ' • ' + c.status;
  $('status').textContent = 'Total ' + c.total + ' ' + c.currency + ' • receipts: ' + c.receiptCount + (c.mockReference ? ' • ref ' + c.mockReference : '');
  $('total').innerHTML = '<b>Total:</b> ' + c.total + ' ' + c.currency;
  const tb = document.querySelector('#tbl tbody');
  tb.innerHTML = '';
  for (const it of c.items) {
    const tr = document.createElement('tr');
    tr.appendChild(editCell(it, 'vendor', it.vendor, '150px'));
    tr.appendChild(editCell(it, 'expenseDate', it.expenseDate));
    tr.appendChild(editCell(it, 'amount', it.amount));
    const ctd = document.createElement('td');
    ctd.appendChild(catSelect(it));
    tr.appendChild(ctd);
    const flags = [];
    if (it.needsReview) flags.push('needs-review');
    if (it.duplicateOf) flags.push('dup→' + String(it.duplicateOf).slice(0, 6));
    const ftd = document.createElement('td');
    ftd.textContent = flags.join(',');
    tr.appendChild(ftd);
    const atd = document.createElement('td');
    if (it.receiptId) {
      const a = document.createElement('a');
      a.href = '/api/claims/' + claimId + '/receipts/' + it.receiptId;
      a.target = '_blank';
      a.textContent = '📄 ' + (it.sourceFile || 'view');
      atd.appendChild(a);
    } else {
      atd.className = 'muted';
      atd.textContent = it.sourceFile || '';
    }
    tr.appendChild(atd);
    tb.appendChild(tr);
  }
  const qd = $('qs'); qd.innerHTML = c.questions.length ? '' : '<span class="ok">Nothing missing — ready.</span>';
  for (const q of c.questions) {
    const d = document.createElement('div');
    d.className = 'q';
    d.innerHTML = '<div>' + q.prompt + '</div>';
    let inp;
    if (q.field === 'category') {
      inp = document.createElement('select');
      for (const cat of CATS) {
        const o = document.createElement('option');
        o.value = cat; o.textContent = cat; inp.appendChild(o);
      }
    } else {
      inp = document.createElement('input');
      inp.placeholder = q.field;
    }
    const btn = document.createElement('button');
    btn.textContent = 'Answer';
    btn.onclick = async () => {
      await api('/api/claims/' + claimId + '/items/' + q.itemId + '/answers', { method: 'POST', body: JSON.stringify({ field: q.field, value: inp.value }) });
      await draw();
    };
    d.appendChild(inp); d.appendChild(btn);
    qd.appendChild(d);
  }
  const vd = $('vs'); vd.innerHTML = c.violations.length ? '' : '<span class="ok">No violations.</span>';
  for (const v of c.violations) {
    const d = document.createElement('div');
    d.className = v.blocking ? 'bad' : 'warn';
    d.textContent = (v.blocking ? '[BLOCK] ' : '[WARN] ') + v.code + ': ' + v.message;
    vd.appendChild(d);
  }
}

$('upload').onclick = async () => {
  const fs = $('files').files;
  if (!fs.length) { alert('choose files first'); return; }
  const loader = $('loader'), msg = $('loadMsg');
  loader.hidden = false;
  let created = 0;
  try {
    // One request per file so a slow/failed bill can't sink the batch.
    for (let i = 0; i < fs.length; i++) {
      const f = fs[i];
      msg.textContent = 'Extracting bill ' + (i + 1) + ' of ' + fs.length + ': ' + f.name + '…';
      const fd = new FormData();
      fd.append('files', f);
      try {
        const r = await fetch('/api/claims/' + claimId + '/receipts', { method: 'POST', body: fd });
        const t = await r.json();
        if (!r.ok) { $('upMsg').textContent = 'error on ' + f.name + ': ' + (t.error || r.statusText); continue; }
        created += t.created;
        $('upMsg').textContent = 'done so far: ' + created + ' via ' + t.extractor + ((t.skipped || []).length ? ' • skipped: ' + t.skipped.join('; ') : '');
      } catch (e) {
        $('upMsg').textContent = 'error on ' + f.name + ': ' + e.message;
      }
    }
    $('files').value = '';
    await draw();
  } finally {
    loader.hidden = true;
  }
};
$('submit').onclick = async () => {
  try {
    const c = await api('/api/claims/' + claimId + '/submit', { method: 'POST' });
    $('subMsg').innerHTML = '<span class="ok">Submitted! Mock ref: ' + c.mockReference + '</span>';
    await draw();
  } catch (e) { $('subMsg').innerHTML = '<span class="bad">' + e.message + '</span>'; }
};

api('/api/claims/categories').then(c => { if (c.length) CATS = c; }).finally(draw).catch(() => draw());
