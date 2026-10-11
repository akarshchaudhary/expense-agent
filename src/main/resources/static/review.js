const $ = (id) => document.getElementById(id);
let openId = null;

async function api(path, opts = {}) {
  const r = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...opts });
  const t = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(t.error || r.statusText);
  return t;
}

async function reload() {
  const st = $('fStatus').value || 'SUBMITTED';
  const list = await api('/api/claims?status=' + st);
  const tb = document.querySelector('#qt tbody');
  tb.innerHTML = list.length ? '' : '<tr><td colspan="5" class="muted">Queue empty 🎉</td></tr>';
  for (const c of list) {
    const tr = document.createElement('tr');
    tr.innerHTML = '<td>' + c.employeeId + '</td><td>' + c.title + '</td><td>' + c.total + ' ' + c.currency + '</td><td>' + c.status + '</td>';
    const td = document.createElement('td');
    const b = document.createElement('button');
    b.textContent = 'Open';
    b.onclick = () => { openId = c.id; draw(); };
    td.appendChild(b); tr.appendChild(td);
    tb.appendChild(tr);
  }
}

async function draw() {
  if (!openId) return;
  const c = await api('/api/claims/' + openId);
  const d = $('detail');
  d.innerHTML = '<h3>' + c.title + ' — ' + c.employeeId + ' — <b>' + c.total + ' ' + c.currency + '</b> [' + c.status + ']' + (c.mockReference ? ' • ' + c.mockReference : '') + '</h3>'
    + (c.rejectReason ? '<p class="bad">Rejected: ' + c.rejectReason + '</p>' : '')
    + '<table><thead><tr><th>Vendor</th><th>Date</th><th>Amount</th><th>Category</th><th>Receipt</th></tr></thead><tbody>'
    + c.items.map(it => '<tr><td>' + (it.vendor || '?') + '</td><td>' + (it.expenseDate || '?') + '</td><td>' + (it.amount ?? '?') + '</td><td>' + (it.category || '?') + '</td><td>'
      + (it.receiptId ? '<a href="/api/claims/' + c.id + '/receipts/' + it.receiptId + '" target="_blank">📄 view</a>' : '<span class="muted">—</span>') + '</td></tr>').join('')
    + '</tbody></table>'
    + '<h4>Policy checks</h4>' + (c.violations.length
      ? c.violations.map(v => '<div class="' + (v.blocking ? 'bad' : 'warn') + '">' + v.code + ': ' + v.message + '</div>').join('')
      : '<span class="ok">Clean.</span>');
  if (c.status === 'SUBMITTED') {
    const row = document.createElement('div');
    row.className = 'row';
    const ok = document.createElement('button');
    ok.textContent = 'Approve ✓'; ok.className = 'primary';
    ok.onclick = async () => { await api('/api/claims/' + openId + '/review', { method: 'POST', body: JSON.stringify({ approved: true }) }); await reload(); await draw(); };
    const no = document.createElement('button');
    no.textContent = 'Reject';
    no.onclick = async () => {
      const reason = prompt('Rejection reason (required — employee will see this):');
      if (reason === null || !reason.trim()) { alert('A reason is required to reject.'); return; }
      await api('/api/claims/' + openId + '/review', { method: 'POST', body: JSON.stringify({ approved: false, reason }) });
      await reload(); await draw();
    };
    row.appendChild(ok); row.appendChild(no);
    d.appendChild(row);
  }
}

$('reload').onclick = reload;
$('fStatus').onchange = reload;
// Auto-refresh: finance never clicks Reload — queue + open detail repoll every 10s.
setInterval(async () => {
  try {
    await reload();
    if (openId) await draw();
  } catch (e) { /* next tick retries */ }
}, 10000);
reload().catch(e => $('detail').textContent = e.message);
