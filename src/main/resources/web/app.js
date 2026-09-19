'use strict';

/**
 * Interfaz de revision.
 *
 * La decision de diseno que importa: el documento se muestra como TEXTO con las
 * detecciones resaltadas en su sitio, no como una lista aparte. Una lista solo ensena lo
 * que el detector encontro; leyendo el texto corrido, el revisor puede ver lo que se
 * escapo. Los falsos negativos son invisibles en cualquier interfaz que solo muestre
 * aciertos, y son el modo de fallo que de verdad importa.
 */

const state = {
  jobId: null,
  text: '',
  detections: [],
  entities: new Map(),   // entityKey -> {type, sample, ids:[], count}
  rejected: new Set(),   // entityKeys descartadas
  markdown: '', warningMarkdown: '', warningDownloadEligible: false,
  types: [], edits: [], revision: 0, analysisRequest: 0, applyRequest: 0,
  activeTab: 'document', comparing: false
};

const el = (id) => document.getElementById(id);

// ------------------------------------------------------------------ carga

function init() {
  const dropzone = el('dropzone');
  const fileInput = el('file');

  el('browse').addEventListener('click', () => fileInput.click());
  fileInput.addEventListener('change', () => {
    if (fileInput.files.length) analyze(fileInput.files[0]);
  });

  ['dragenter', 'dragover'].forEach((evt) =>
    dropzone.addEventListener(evt, (e) => {
      e.preventDefault();
      dropzone.classList.add('dragging');
    }));

  ['dragleave', 'drop'].forEach((evt) =>
    dropzone.addEventListener(evt, (e) => {
      e.preventDefault();
      dropzone.classList.remove('dragging');
    }));

  dropzone.addEventListener('drop', (e) => {
    if (e.dataTransfer.files.length) analyze(e.dataTransfer.files[0]);
  });

  el('apply').addEventListener('click', apply);
  el('add-entity').addEventListener('click', startManualEntity);
  el('download').addEventListener('click', download);
  el('warning-download').addEventListener('click', downloadWithWarnings);
  el('copy').addEventListener('click', copyMarkdown);

  el('compare').addEventListener('click', toggleCompare);
  document.querySelectorAll('.tab').forEach((tab) => {
    tab.addEventListener('click', () => selectTab(tab.dataset.tab));
    tab.addEventListener('keydown', handleTabKeydown);
  });
}

async function analyze(file) {
  const request = ++state.analysisRequest;
  invalidateResult();
  state.jobId = null;
  showError(null);
  el('workspace').classList.add('hidden');
  el('loading').classList.remove('hidden');

  try {
    const response = await fetch('/api/analyze', { method: 'POST', body: file });
    const data = await response.json();
    if (request !== state.analysisRequest) return;

    if (!response.ok) {
      showError(data.error || 'No se pudo procesar el documento.');
      return;
    }

    state.jobId = data.jobId;
    state.text = data.text;
    state.detections = data.detections;
    state.types = data.types;
    state.edits = [];
    state.rejected = new Set();
    state.markdown = '';
    groupEntities();

    renderStats(data);
    renderDocument();
    renderEntities();
    resetResultPanels();

    el('workspace').classList.remove('hidden');
    selectTab('document');
  } catch (err) {
    if (request === state.analysisRequest) showError('No se pudo contactar con el servidor.');
  } finally {
    if (request === state.analysisRequest) el('loading').classList.add('hidden');
  }
}

// ---------------------------------------------------------------- entidades

function groupEntities() {
  state.originals = new Map(state.detections.map(d => [d.id, Object.freeze({ start: d.start, end: d.end })]));
  state.editedOrder = new Map();
  state.editSequence = 0;
  rebuildEntities();
}

function rebuildEntities() {
  state.entities = new Map();
  for (const detection of state.detections) {
    const value = state.text.slice(detection.start, detection.end);
    let entity = state.entities.get(detection.entityKey);
    if (!entity) {
      entity = { type: detection.type, sample: value, ids: [], spans: new Set(), count: 0, best: value, bestId: detection.id };
      state.entities.set(detection.entityKey, entity);
    }
    // Se muestra la forma mas larga: identifica mejor a la entidad de un vistazo.
    if (normalize(value).length > normalize(entity.best).length) {
      entity.best = value;
      entity.bestId = detection.id;
    }
    entity.ids.push(detection.id);
    entity.spans.add(detection.start + ':' + detection.end);
    entity.count = entity.spans.size;
  }
}

function renderEntities() {
  const container = el('entities');
  container.innerHTML = '';

  const sorted = [...state.entities.entries()]
    .sort((a, b) => a[1].type.localeCompare(b[1].type) || b[1].count - a[1].count);

  for (const [entityKey, entity] of sorted) {
    const off = state.rejected.has(entityKey);

    const row = document.createElement('div');
    row.className = 'entity' + (off ? ' off' : '');

    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.checked = !off;
    checkbox.addEventListener('change', () => toggleEntity(entityKey));

    const main = document.createElement('div');
    main.className = 'entity-main';

    checkbox.setAttribute('aria-label', 'Anonimizar ' + normalize(entity.best));
    const value = document.createElement('button');
    value.type = 'button';
    value.className = 'entity-value has-icon';
    value.textContent = entity.best.replace(/\s+/g, ' ');
    value.title = 'Editar texto: ' + entity.best;
    value.addEventListener('click', () => editInline(value, entityKey));

    const meta = document.createElement('div');
    meta.className = 'entity-meta';
    const edited = state.editedOrder.has(entity.bestId);
    const position = state.originals.get(entity.bestId).start + 1;
    const occurrences = entity.count === 1 ? '1 aparicion' : entity.count + ' apariciones';
    meta.textContent = edited
      ? 'Seleccion editada · posicion original ' + position + ' · ' + occurrences
      : entity.count === 1 ? occurrences
        : occurrences + ' exactas; el texto las edita juntas';

    main.append(value, meta);

    const chip = document.createElement('select');
    chip.className = 'chip';
    chip.setAttribute('aria-label', 'Tipo de ' + normalize(entity.best));
    for (const type of state.types) {
      const option = document.createElement('option');
      option.value = type;
      option.textContent = type;
      option.selected = type === entity.type;
      chip.append(option);
    }
    chip.style.color = colorOf(entity.type);
    chip.addEventListener('change', () => {
      changeType(entityKey, chip.value);
      refreshReview();
    });

    row.append(checkbox, main, chip);
    container.append(row);
  }

  if (sorted.length === 0) {
    container.innerHTML = '<p class="entity-meta">No se detecto ningun dato personal.</p>';
  }
}

function toggleEntity(entityKey) {
  const rejected = !state.rejected.has(entityKey);
  recordEdit('reject', entityKey, String(rejected));
  if (rejected) state.rejected.add(entityKey);
  else state.rejected.delete(entityKey);
  refreshReview();
}

function acceptAll() {
  for (const key of state.rejected) recordEdit('reject', key, 'false');
  state.rejected.clear();
  refreshReview();
}

function normalize(value) { return value.replace(/\s+/gu, ' ').trim(); }

// Exact contiguous normalized source fragments, with original UTF-16 offsets.
function narrow(source, selected) {
  return sourceMatches(source, selected)[0] || null;
}

function sourceMatches(source, selected) {
  const { value, offsets } = normalizeSource(source);
  const found = [];
  for (let index = value.indexOf(selected); index >= 0; index = value.indexOf(selected, index + 1)) {
    const end = index + selected.length;
    // Do not return ranges that would split a supplementary character's UTF-16 pair.
    if (offsets[index] !== undefined && offsets[end] !== undefined) {
      found.push([offsets[index], offsets[end]]);
    }
  }
  return found;
}

// Match ExactOccurrences: a selected number must not be a fragment of a longer number.
function isDigitAt(source, index) {
  return /\p{Nd}/u.test(String.fromCodePoint(source.codePointAt(index)));
}

function isDigitBefore(source, index) {
  const previous = source.charCodeAt(index - 1);
  const start = previous >= 0xDC00 && previous <= 0xDFFF ? index - 2 : index - 1;
  return isDigitAt(source, start);
}

function eligibleSourceMatches(source, selected) {
  return sourceMatches(source, selected).filter(([start, end]) =>
    !(start > 0 && isDigitBefore(source, start))
    && !(end < source.length && isDigitAt(source, end)));
}

function hasPartialOverlap(first, second) {
  if (first[0] >= second[1] || second[0] >= first[1]) return false;
  const contains = first[0] <= second[0] && first[1] >= second[1];
  const contained = second[0] <= first[0] && second[1] >= first[1];
  return !contains && !contained;
}

function normalizeSource(source) {
  let value = '';
  const offsets = [];
  let whitespaceStart = -1;
  let whitespaceEnd = -1;
  let index = 0;
  for (const character of source) {
    const end = index + character.length;
    if (/\s/u.test(character)) {
      if (whitespaceStart < 0) whitespaceStart = index;
      whitespaceEnd = end;
    } else {
      if (whitespaceStart >= 0 && value.length > 0) {
        appendMapped(value, offsets, ' ', whitespaceStart, whitespaceEnd);
        value += ' ';
      }
      whitespaceStart = -1;
      appendMapped(value, offsets, character, index, end);
      value += character;
    }
    index = end;
  }
  return { value, offsets };
}

function appendMapped(value, offsets, fragment, sourceStart, sourceEnd) {
  setOffset(offsets, value.length, sourceStart);
  setOffset(offsets, value.length + fragment.length, sourceEnd);
}

function setOffset(offsets, index, value) {
  while (offsets.length <= index) offsets.push(undefined);
  offsets[index] = value;
}

// Never search relative to the last edit: narrowing must not lose the original anchor.
function anchoredRange(original, selected) {
  const inside = narrow(state.text.slice(original.start, original.end), selected);
  if (inside) return inside.map(offset => original.start + offset);
  const enclosing = sourceMatches(state.text, selected)
    .filter(([start, end]) => start <= original.start && end >= original.end);
  return enclosing.length === 1 ? enclosing[0] : null;
}

function recordEdit(action, key, value) {
  state.edits.push([action, state.entities.get(key).ids[0], value]);
}

function changeType(key, type) {
  if (!state.types.includes(type)) return false;
  recordEdit('type', key, type);
  state.entities.get(key).type = type;
  for (const d of state.detections) if (d.entityKey === key) d.type = type;
  return true;
}

function changeText(key, input) {
  state.editError = 'Selecciona un fragmento contiguo del documento que incluya la deteccion original o este dentro de ella. No se guardo el cambio.';
  const selected = normalize(input);
  if (!selected) return false;
  const entity = state.entities.get(key);
  const id = entity.bestId;
  const selectedDetection = state.detections.find(d => d.id === id);
  const oldValue = normalize(state.text.slice(selectedDetection.start, selectedDetection.end));
  const changes = entity.ids.map(member => {
    const current = state.detections.find(d => d.id === member);
    if (normalize(state.text.slice(current.start, current.end)) !== oldValue) return null;
    const range = anchoredRange(state.originals.get(member), selected);
    if (!range) return null;
    for (const other of state.detections) {
      if (other.id === member || range[0] >= other.end || other.start >= range[1]) continue;
      const contains = range[0] <= other.start && range[1] >= other.end;
      const contained = other.start <= range[0] && other.end >= range[1];
      if (!contains && !contained) {
        if (member === id) state.editError = 'La seleccion solapa parcialmente otra deteccion. Incluyela completa o reduce la seleccion. No se guardo el cambio.';
        return null;
      }
    }
    return { id: member, range };
  }).filter(Boolean);
  if (!changes.some(change => change.id === id)) return false;

  // Keep variants whose source context cannot accept this expansion unchanged.
  for (const change of changes) {
    const changedDetection = state.detections.find(d => d.id === change.id);
    [changedDetection.start, changedDetection.end] = change.range;
    state.editedOrder.set(change.id, ++state.editSequence);
  }
  state.edits.push(['text', id, selected]);
  rebuildEntities();
  state.editError = null;
  return true;
}

function nextManualId() {
  let sequence = 1;
  while (state.detections.some(detection => detection.id === 'manual:' + sequence
    || detection.entityKey === 'manual:' + sequence)) sequence++;
  return 'manual:' + sequence;
}

function addManualEntity(input) {
  const selected = normalize(input);
  if (!selected) return false;
  if (state.edits.some(([action, , value]) => action === 'add' && value === selected)) return true;

  const ranges = eligibleSourceMatches(state.text, selected);
  if (!ranges.length || ranges.some((range, index) =>
    state.detections.some(other => hasPartialOverlap(range, [other.start, other.end]))
    || ranges.slice(0, index).some(other => hasPartialOverlap(range, other)))) return false;
  const id = nextManualId();
  ranges.forEach(([start, end], index) => {
    const detectionId = index === 0 ? id : id + ':' + start + ':' + end;
    state.detections.push({
      id: detectionId, type: 'CODIGO', start, end, entityKey: id,
      provenance: 'MANUAL', confidence: 1
    });
    state.originals.set(detectionId, Object.freeze({ start, end }));
  });
  state.edits.push(['add', id, selected]);
  rebuildEntities();
  return true;
}

function startManualEntity() {
  const container = el('entities');
  const row = document.createElement('div');
  row.className = 'entity';
  const main = document.createElement('div');
  main.className = 'entity-main';
  const input = document.createElement('input');
  input.className = 'entity-editor';
  input.placeholder = 'Texto a anonimizar';
  input.setAttribute('aria-label', 'Texto a anonimizar; Enter guarda, Escape cancela');
  const meta = document.createElement('div');
  meta.className = 'entity-meta';
  meta.textContent = 'Se añadirán todas las apariciones exactas.';
  main.append(input, meta);
  const type = document.createElement('span');
  type.className = 'chip';
  type.textContent = 'CODIGO';
  type.setAttribute('aria-label', 'Tipo predeterminado: CODIGO');
  type.style.color = colorOf('CODIGO');
  row.append(main, type);
  container.append(row);

  let finished = false;
  const finish = save => {
    if (finished) return;
    finished = true;
    if (save && addManualEntity(input.value)) {
      showError(null);
      refreshReview();
      return;
    }
    if (save && normalize(input.value)) showError('Ningún elemento encontrado');
    renderEntities();
  };
  input.addEventListener('blur', () => finish(true));
  input.addEventListener('keydown', event => {
    if (event.isComposing) return;
    if (event.key === 'Enter' || event.key === 'Escape') {
      event.preventDefault();
      finish(event.key === 'Enter');
    }
  });
  input.focus();
}

function editInline(button, key) {
  const input = document.createElement('input');
  input.className = 'entity-editor';
  input.value = normalize(state.entities.get(key).best);
  input.setAttribute('aria-label', 'Texto a anonimizar; Enter guarda, Escape cancela');
  let finished = false;
  const finish = (save) => {
    if (finished) return;
    finished = true;
    if (save && input.value !== normalize(state.entities.get(key).best)) {
      if (changeText(key, input.value)) {
        showError(null);
        refreshReview();
        return;
      }
      showError(state.editError);
    }
    input.replaceWith(button);
  };
  input.addEventListener('blur', () => finish(true));
  input.addEventListener('keydown', event => {
    if (event.isComposing) return;
    if (event.key === 'Enter' || event.key === 'Escape') {
      event.preventDefault();
      finish(event.key === 'Enter');
      if (button.isConnected) button.focus();
    }
  });
  button.replaceWith(input);
  input.focus();
  input.select();
}

function invalidateResult() {
  state.revision++;
  state.markdown = '';
  state.warningMarkdown = '';
  state.warningDownloadEligible = false;
  resetResultPanels();
}

function refreshReview() {
  invalidateResult();
  renderEntities();
  renderDocument();
}

function reviewBody() {
  return 'review-v1\n' + state.edits.map(([action, id, value]) =>
    [action, encodeURIComponent(id), encodeURIComponent(value)].join('\t')).join('\n');
}

// ---------------------------------------------------------------- documento

// Pure projection; rejected outer tags must not hide accepted inner selections.
// Stable analysis order breaks unedited ties, matching ReviewEdits on the server.
function effectiveDetections() {
  const ordered = state.detections.filter(d => !state.rejected.has(d.entityKey))
    .sort((a, b) => a.start - b.start || b.end - a.end
      || (state.editedOrder.get(b.id) || 0) - (state.editedOrder.get(a.id) || 0));
  const effective = [];
  let end = -1;
  for (const detection of ordered) {
    if (detection.start >= end) {
      effective.push(detection);
      end = detection.end;
    }
  }
  return effective;
}

// Render only effective spans; all retained tags stay controllable in the sidebar.
function renderDocument() {
  const container = el('doctext');
  container.innerHTML = '';

  const ordered = effectiveDetections();
  let cursor = 0;

  for (const detection of ordered) {
    if (detection.start < cursor) continue;

    if (detection.start > cursor) {
      container.append(document.createTextNode(
        state.text.slice(cursor, detection.start)));
    }

    const off = state.rejected.has(detection.entityKey);
    const mark = document.createElement('span');
    mark.className = 'hl' + (off ? ' off' : '');
    mark.style.color = colorOf(detection.type);
    mark.textContent = state.text.slice(detection.start, detection.end);
    mark.title = detection.type + ' - ' + detection.provenance
      + ' - confianza ' + detection.confidence;
    mark.addEventListener('click', () => toggleEntity(detection.entityKey));
    container.append(mark);

    cursor = detection.end;
  }

  container.append(document.createTextNode(state.text.slice(cursor)));
}

function colorOf(type) {
  const key = '--t-' + type.toLowerCase();
  const value = getComputedStyle(document.documentElement).getPropertyValue(key);
  return value.trim() || '#8b97a8';
}

// ----------------------------------------------------------------- aplicar

async function apply() {
  if (!state.jobId) return;
  invalidateResult();
  const revision = state.revision;
  const request = ++state.applyRequest;
  const current = () => revision === state.revision && request === state.applyRequest;
  el('apply').disabled = true;
  try {
    const response = await fetch('/api/apply', {
      method: 'POST',
      headers: { 'X-Job-Id': state.jobId, 'Content-Type': 'text/plain' },
      body: reviewBody()
    });
    const data = await response.json();
    if (!current()) return;

    if (!response.ok) {
      showError(data.error || 'No se pudo generar el resultado.');
      return;
    }

    const hasResult = typeof data.markdown === 'string' && data.markdown.trim().length > 0;
    state.markdown = data.deliverable && hasResult ? data.markdown : '';
    state.warningMarkdown = !data.deliverable && hasResult ? data.markdown : '';
    state.warningDownloadEligible = !data.deliverable && hasResult;
    renderResult(data, hasResult);
    renderChecks(data);
    selectTab('result');
  } catch (err) {
    if (current()) showError('No se pudo contactar con el servidor.');
  } finally {
    if (request === state.applyRequest) el('apply').disabled = false;
  }
}

function renderResult(data, hasResult) {
  const deliverable = data.deliverable && hasResult;
  el('markdown').textContent = deliverable
    ? data.markdown
    : 'ANONIMIZACIÓN BLOQUEADA: el resultado no se entrega.\n\n'
      + 'Revisa la notificación para conocer el control que falló.';
  if (!deliverable && data.findings && data.findings.length) {
    el('warning-note').textContent = 'Controles bloqueantes: '
      + data.findings.map(finding => finding.control + ': ' + finding.detail).join(' ');
  } else {
    el('warning-note').textContent = 'Este archivo puede contener datos personales residuales. Descárgalo solo si aceptas ese riesgo.';
  }

  el('download').disabled = !deliverable;
  el('copy').disabled = !deliverable;
  el('warning-download').disabled = !state.warningDownloadEligible;
  el('warning-download').classList.toggle('hidden', !state.warningDownloadEligible);
  el('warning-note').classList.toggle('hidden', !state.warningDownloadEligible);
}

function renderChecks(data) {
  if (data.deliverable) {
    showToast(
      'Anonimización completada',
      data.substitutions + ' sustituciones en ' + data.entities + ' entidades. El Markdown está listo.',
      'success'
    );
    return;
  }

  const detail = data.findings && data.findings.length
    ? data.findings.map(finding => finding.control + ': ' + finding.detail).join(' ')
    : 'El documento no se entrega porque la verificación encontró un riesgo.';
  showToast('Anonimización bloqueada', detail, 'warning');
}

function showToast(title, message, tone) {
  const region = el('toast-region');
  if (!region) return;

  const toast = document.createElement('div');
  toast.className = 'toast ' + tone;
  toast.setAttribute('role', tone === 'warning' ? 'alert' : 'status');

  const heading = document.createElement('strong');
  heading.className = 'toast-title';
  heading.textContent = title;
  const body = document.createElement('span');
  body.className = 'toast-message';
  body.textContent = message;
  toast.append(heading, body);
  region.append(toast);

  setTimeout(() => {
    if (typeof toast.remove === 'function') toast.remove();
  }, 6500);
}

function resetResultPanels() {
  el('markdown').textContent = 'Pulsa «Anonimizar» para generar el resultado.'
  el('download').disabled = true;
  el('warning-download').disabled = true;
  el('warning-download').classList.add('hidden');
  el('warning-note').classList.add('hidden');
  el('copy').disabled = true;
}

// -------------------------------------------------------------------- misc

function renderStats(data) {
  el('stats').innerHTML = '';
  const stats = [
    ['Paginas', data.pageCount],
    ['Candidatos', data.detections.length],
    ['Entidades', state.entities.size],
    ['Analisis', data.elapsedMs + ' ms']
  ];
  for (const [label, value] of stats) {
    const box = document.createElement('div');
    box.className = 'stat';
    box.innerHTML = '<b></b><span></span>';
    box.querySelector('b').textContent = value;
    box.querySelector('span').textContent = label;
    el('stats').append(box);
  }
}

function selectTab(name) {
  if (name !== 'document' && name !== 'result') return;
  state.activeTab = name;
  state.comparing = false;
  renderMainView();
}

function toggleCompare() {
  state.comparing = !state.comparing;
  renderMainView();
}

function renderMainView() {
  const selectedPanel = 'tab-' + state.activeTab;
  document.querySelectorAll('.tab').forEach((tab) => {
    const active = tab.dataset.tab === state.activeTab;
    tab.classList.toggle('active', active);
    tab.setAttribute('aria-selected', String(active));
    tab.tabIndex = active ? 0 : -1;
  });
  document.querySelectorAll('.main-tabpanels > [role="tabpanel"]').forEach((panel) =>
    panel.classList.toggle('hidden', !state.comparing && panel.id !== selectedPanel));

  setComparisonVisibility('entity-controls', state.comparing);
  el('workspace').classList.toggle('comparing', state.comparing);
  el('pane-side').classList.toggle('comparing', state.comparing);
  el('pane-main').classList.toggle('comparing', state.comparing);
  el('compare').setAttribute('aria-pressed', String(state.comparing));
  el('compare-label').textContent = state.comparing ? 'Salir de comparación' : 'Comparar';
}

function setComparisonVisibility(id, hidden) {
  const target = el(id);
  target.hidden = hidden;
  target.inert = hidden;
  target.setAttribute('aria-hidden', String(hidden));
}

function handleTabKeydown(event) {
  const tabs = [...document.querySelectorAll('.tab')];
  const index = tabs.indexOf(event.currentTarget);
  if (index < 0) return;
  let next = index;
  if (event.key === 'ArrowRight' || event.key === 'ArrowDown') next = (index + 1) % tabs.length;
  else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') next = (index - 1 + tabs.length) % tabs.length;
  else if (event.key === 'Home') next = 0;
  else if (event.key === 'End') next = tabs.length - 1;
  else return;
  event.preventDefault();
  tabs[next].focus();
  selectTab(tabs[next].dataset.tab);
}

function showError(message) {
  const box = el('error');
  if (!message) {
    box.classList.add('hidden');
    return;
  }
  box.textContent = message;
  box.classList.remove('hidden');
}

function saveMarkdown(markdown) {
  const blob = new Blob([markdown], { type: 'text/markdown;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = 'documento.anon.md';
  link.click();
  URL.revokeObjectURL(url);
}

function download() {
  if (!state.markdown) return false;
  saveMarkdown(state.markdown);
  return true;
}

function downloadWithWarnings() {
  if (!state.warningDownloadEligible || !state.warningMarkdown) return false;
  saveMarkdown(state.warningMarkdown);
  return true;
}

async function copyMarkdown() {
  if (!state.markdown) return;
  try {
    await navigator.clipboard.writeText(state.markdown);
    el('copy').textContent = 'Copiado';
    setTimeout(() => { el('copy').textContent = 'Copiar'; }, 1500);
  } catch (err) {
    showError('El navegador bloqueo el acceso al portapapeles.');
  }
}

if (typeof document !== 'undefined') init();
if (typeof module !== 'undefined') module.exports = {
  state, normalize, narrow, groupEntities, changeText, changeType, reviewBody,
  addManualEntity, startManualEntity, editInline, invalidateResult, apply, analyze, effectiveDetections, renderDocument,
  selectTab, toggleCompare, download, downloadWithWarnings
};
