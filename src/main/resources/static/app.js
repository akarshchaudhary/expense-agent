let claimId = null;
const $ = (id) => document.getElementById(id);

async function api(path, opts = {}) {
  const r = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...opts });
  const t = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(t.error || r.statusText);
  return t;
}

function currentClaim() {
  return claimId || new URLSearchParams(location.search).get('claimId');
}

async function newClaim() {
  const c = await api('/api/claims', { method: 'POST', body: JSON.stringify({ employeeId: $('emp').value, title: $('title').value }) });
  claimId = c.id;
  await loadClaims();
  await draw();
}

async function loadClaims() {
  const emp = $('emp').value;
  const list = await api('/api/claims?employeeId=' + encodeURIComponent(emp));
  const sel = $('claimSel');
  sel.innerHTML = '';
  for (const c of list) {
    const o = document.createElement('option');
    o.value = c.id; o.textContent = c.title + ' [' + c.status + '] ' + c.total;
    sel.appendChild(o);
  }
  const q = currentClaim();
  if (q) claimId = q;
  else if (list.length && !claimId) claimId = list[0].id;
  if (claimId) sel.value = claimId;
}

async function draw() {
  if (!claimId) return;
  const c = await api('/api/claims/' + claimId);
  $('status').textContent = c.status + ' • total ' + c.total + ' ' + c.currency + (c.mockReference ? ' • ' + c.mockReference : '');
}

$('newClaim').onclick = () => newClaim().catch(e => alert(e.message));
$('refresh').onclick = async () => { await loadClaims(); claimId = $('claimSel').value; await draw(); };
$('claimSel').onchange = async (e) => { claimId = e.target.value; await draw(); };
async function uploadSelected() {
  const fs = $('files').files;
  if (!claimId) { alert('create a claim first'); return false; }
  if (!fs.length) return true;
  const loader = $('loader'), msg = $('loadMsg'), upMsg = $('upMsg');
  loader.hidden = false;
  let created = 0, skipped = [], provider = '', failed = 0;
  try {
    // One request per file: vision takes ~10-40s each; a batch in one request
    // risks browser timeouts and one bad file killing the rest.
    for (let i = 0; i < fs.length; i++) {
      const f = fs[i];
      msg.textContent = 'Extracting bill ' + (i + 1) + ' of ' + fs.length + ': ' + f.name + '…';
      const fd = new FormData();
      fd.append('files', f);
      try {
        const r = await fetch('/api/claims/' + claimId + '/receipts', { method: 'POST', body: fd });
        const t = await r.json();
        if (!r.ok) { failed++; upMsg.textContent = 'error on ' + f.name + ': ' + (t.error || r.statusText); continue; }
        created += t.created;
        provider = t.extractor;
        skipped = skipped.concat(t.skipped || []);
      } catch (e) {
        failed++;
        upMsg.textContent = 'error on ' + f.name + ': ' + e.message;
      }
    }
    upMsg.textContent = 'done: ' + created + ' extracted via ' + provider
      + (skipped.length ? ' • skipped: ' + skipped.join('; ') : '')
      + (failed ? ' • ' + failed + ' failed' : '');
    $('files').value = '';
    await draw();
    return failed === 0;
  } finally {
    loader.hidden = true;
  }
}

$('upload').onclick = () => uploadSelected().catch(e => { $('upMsg').textContent = 'error: ' + e.message; });
$('goSubmit').onclick = async () => {
  if (!claimId) { alert('create a claim first'); return; }
  // Files chosen but not yet uploaded? Extract first, then go.
  if ($('files').files.length) {
    const ok = await uploadSelected().catch(e => { $('upMsg').textContent = 'error: ' + e.message; return false; });
    if (!ok) return;
  }
  location.href = '/submit.html?claimId=' + claimId;
};

loadClaims().then(draw).catch(() => {});
