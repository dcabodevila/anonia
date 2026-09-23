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
  sourceFilename: null,
  text: '',
  detections: [],
  entities: new Map(),   // entityKey -> {type, sample, ids:[], count}
  rejected: new Set(),   // entityKeys descartadas
  markdown: '', warningMarkdown: '', warningDownloadEligible: false,
  resultStatus: 'pending', resultStatusTitle: 'Resultado pendiente', resultStatusMessage: '', resultFindings: [],
  types: [], edits: [], revision: 0, analysisRequest: 0, applyRequest: 0, copyRequest: 0,
  activeTab: 'document', comparing: false,
  entitySearch: '', entityTypeFilter: '', activeEntityKey: null, activeOccurrenceId: null,
  lastNavigationWrapped: false, focusTarget: null, manualEntityDraft: false
};

const el = (id) => document.getElementById(id);

// ------------------------------------------------------------------ carga

function init() {
  const dropzone = el('dropzone');
  const fileInput = el('file');

  el('brand-home').addEventListener('click', goHome);
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
  el('download').addEventListener('click', download);
  el('warning-download').addEventListener('click', downloadWithWarnings);
  el('copy').addEventListener('click', copyMarkdown);

  el('compare').addEventListener('click', toggleCompare);
  el('entity-search').addEventListener('input', () => {
    setEntityFilters(el('entity-search').value, el('entity-type-filter').value);
    renderEntities();
    updateLocationControls();
  });
  el('entity-type-filter').addEventListener('change', () => {
    setEntityFilters(el('entity-search').value, el('entity-type-filter').value);
    renderEntities();
    updateLocationControls();
  });
  document.querySelectorAll('.tab').forEach((tab) => {
    tab.addEventListener('click', () => selectTab(tab.dataset.tab));
    tab.addEventListener('keydown', handleTabKeydown);
  });
}

async function analyze(file) {
  const request = ++state.analysisRequest;
  state.sourceFilename = file && typeof file.name === 'string' ? file.name : null;
  invalidateResult();
  state.jobId = null;
  resetDropzone();
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
    state.types = [...new Set([...data.types, 'TEXTO'])];
    state.edits = [];
    state.rejected = new Set();
    state.entitySearch = '';
    state.entityTypeFilter = '';
    state.activeEntityKey = null;
    state.activeOccurrenceId = null;
    state.lastNavigationWrapped = false;
    state.manualEntityDraft = false;
    state.markdown = '';
    groupEntities();

    renderStats(data);
    renderDocument();
    renderEntities();
    updateLocationControls();
    resetResultPanels();
    compactDropzone(file);

    el('workspace').classList.remove('hidden');
    selectTab('document');
  } catch (err) {
    if (request === state.analysisRequest) showError('No se pudo contactar con el servidor.');
  } finally {
    if (request === state.analysisRequest) el('loading').classList.add('hidden');
  }
}

function goHome() {
  ++state.analysisRequest;
  invalidateResult();
  state.jobId = null;
  state.sourceFilename = null;
  state.text = '';
  state.detections = [];
  state.entities = new Map();
  state.rejected = new Set();
  state.types = [];
  state.edits = [];
  state.entitySearch = '';
  state.entityTypeFilter = '';
  state.activeEntityKey = null;
  state.activeOccurrenceId = null;
  state.manualEntityDraft = false;
  state.focusTarget = null;
  el('file').value = '';
  el('doctext').textContent = '';
  el('entities').textContent = '';
  el('stats').textContent = '';
  el('entity-search').value = '';
  el('entity-type-filter').value = '';
  el('toast-region').textContent = '';
  el('workspace').classList.add('hidden');
  el('loading').classList.add('hidden');
  showError(null);
  resetDropzone();
}

function resetDropzone() {
  el('dropzone').classList.remove('compact');
  el('drop-title').textContent = 'Arrastra un PDF aquí o ';
  el('drop-sub').textContent = 'PDF con capa de texto, o fotos JPEG/PNG leídas localmente con Tesseract en español.';
}

function compactDropzone(file) {
  const filename = file && typeof file.name === 'string' && file.name ? file.name : 'documento seleccionado';
  el('dropzone').classList.add('compact');
  el('drop-title').textContent = 'Documento seleccionado: ' + filename + '. Arrastra otro PDF o ';
  el('drop-sub').textContent = 'Puedes reemplazarlo sin perder la vista del documento actual.';
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

function sortedEntities() {
  return [...state.entities.entries()]
    .sort((a, b) => a[1].type.localeCompare(b[1].type) || b[1].count - a[1].count);
}

function filteredEntities() {
  const search = normalize(state.entitySearch || '').toLocaleLowerCase();
  return sortedEntities().filter(([, entity]) =>
    (!search || normalize(entity.best).toLocaleLowerCase().includes(search))
    && (!state.entityTypeFilter || entity.type === state.entityTypeFilter));
}

function setEntityFilters(search, type) {
  state.entitySearch = search || '';
  state.entityTypeFilter = type || '';
}

function occurrenceIds(entityKey) {
  const entity = state.entities.get(entityKey);
  if (!entity) return [];
  const seenRanges = new Set();
  return entity.ids.slice().sort((a, b) => {
    const first = state.detections.find(detection => detection.id === a);
    const second = state.detections.find(detection => detection.id === b);
    return first.start - second.start || first.end - second.end || a.localeCompare(b);
  }).filter(id => {
    const detection = state.detections.find(candidate => candidate.id === id);
    const range = detection.start + ':' + detection.end;
    if (seenRanges.has(range)) return false;
    seenRanges.add(range);
    return true;
  });
}

function selectEntityLocation(entityKey, index = 0) {
  const ids = occurrenceIds(entityKey);
  if (!ids.length) return null;
  const resolved = ((index % ids.length) + ids.length) % ids.length;
  state.activeEntityKey = entityKey;
  state.activeOccurrenceId = ids[resolved];
  state.lastNavigationWrapped = false;
  return { id: ids[resolved], index: resolved, count: ids.length, wrapped: false };
}

function navigateEntityOccurrence(direction) {
  const ids = occurrenceIds(state.activeEntityKey);
  if (!ids.length) return null;
  const current = Math.max(0, ids.indexOf(state.activeOccurrenceId));
  const requested = current + (direction < 0 ? -1 : 1);
  const index = (requested + ids.length) % ids.length;
  const wrapped = requested < 0 || requested >= ids.length;
  state.activeOccurrenceId = ids[index];
  state.lastNavigationWrapped = wrapped;
  return { id: ids[index], index, count: ids.length, wrapped };
}

function renderEntityFilters() {
  const filter = el('entity-type-filter');
  if (!filter) return;
  filter.innerHTML = '<option value="">Todos los tipos</option>';
  for (const type of state.types) {
    const option = document.createElement('option');
    option.value = type;
    option.textContent = type;
    option.selected = type === state.entityTypeFilter;
    filter.append(option);
  }
  el('entity-search').value = state.entitySearch;
}

function createManualEntityAddButton() {
  const addButton = document.createElement('button');
  addButton.className = 'add-entity';
  addButton.type = 'button';
  addButton.textContent = 'Añadir entidad anonimizar';
  addButton.onclick = startManualEntity;
  addButton.setAttribute('aria-label', 'Añadir entidad anonimizar');
  addButton.setAttribute('title', 'Añadir entidad anonimizar');
  const icon = document.createElement('svg');
  icon.setAttribute('viewBox', '0 0 24 24');
  icon.setAttribute('aria-hidden', 'true');
  const path = document.createElement('path');
  path.setAttribute('d', 'M12 5v14M5 12h14');
  icon.append(path);
  addButton.append(icon);
  return addButton;
}

function renderManualEntityAddRow() {
  const row = document.createElement('div');
  row.className = 'entity add-entity-row' + (state.manualEntityDraft ? ' active' : '');

  if (!state.manualEntityDraft) {
    const addButton = createManualEntityAddButton();
    row.append(addButton);
    return row;
  }

  const main = document.createElement('div');
  main.className = 'entity-main';
  main.style.gridColumn = '1 / -1';
  const input = document.createElement('input');
  input.className = 'entity-editor';
  input.placeholder = 'Texto a anonimizar';
  input.setAttribute('aria-label', 'Texto a anonimizar; Enter guarda, Escape cancela');
  const meta = document.createElement('div');
  meta.className = 'entity-meta';
  meta.textContent = 'TEXTO. Se añadirán todas las apariciones exactas.';
  const save = document.createElement('button');
  save.type = 'button';
  save.className = 'primary';
  save.textContent = 'Guardar';
  save.setAttribute('aria-label', 'Guardar entidad manual');
  main.append(input, meta, save);
  row.append(main);

  let finished = false;
  const finish = saveDraft => {
    if (finished) return;
    if (!saveDraft) {
      finished = true;
      state.manualEntityDraft = false;
      renderEntities();
      return;
    }
    const editCount = state.edits.length;
    if (!addManualEntity(input.value)) {
      if (normalize(input.value)) showError('Ningún elemento encontrado');
      return;
    }
    finished = true;
    state.manualEntityDraft = false;
    const add = state.edits.length > editCount ? state.edits.at(-1) : state.edits
      .find(([action, , value]) => action === 'add' && value === normalize(input.value));
    state.focusTarget = add ? 'edit:' + add[1] : null;
    showError(null);
    refreshReview();
  };
  save.addEventListener('click', () => finish(true));
  input.addEventListener('keydown', event => {
    if (event.isComposing) return;
    if (event.key === 'Enter' || event.key === 'Escape') {
      event.preventDefault();
      finish(event.key === 'Enter');
    }
  });
  input.focus();
  return row;
}

function renderEntities() {
  renderEntityFilters();
  const container = el('entities');
  container.innerHTML = '';
  container.append(renderManualEntityAddRow());
  const sorted = filteredEntities();

  for (const [entityKey, entity] of sorted) {
    const off = state.rejected.has(entityKey);
    const active = state.activeEntityKey === entityKey;
    const row = document.createElement('div');
    row.className = 'entity' + (off ? ' off' : '') + (active ? ' active' : '');
    row.dataset.entityKey = entityKey;

    const groupScope = entity.count === 1 ? '1 aparición' : entity.count + ' apariciones';
    const entityName = normalize(entity.best);
    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.checked = !off;
    checkbox.dataset.focusTarget = 'checkbox:' + entityKey;
    checkbox.setAttribute('aria-label', off
      ? 'Se conserva ' + entityName + ' en el texto; grupo de ' + groupScope + '. Marcar para anonimizar.'
      : 'Anonimizar ' + entityName + '; grupo de ' + groupScope + '. Desmarcar para conservar el texto.');
    checkbox.addEventListener('change', () => {
      state.focusTarget = checkbox.dataset.focusTarget;
      toggleEntity(entityKey);
    });

    const main = document.createElement('div');
    main.className = 'entity-main';
    const value = document.createElement('button');
    value.type = 'button';
    value.className = 'entity-value has-icon';
    value.dataset.focusTarget = 'edit:' + entityKey;
    value.textContent = entity.best.replace(/\s+/g, ' ');
    value.title = 'Editar texto: ' + entity.best;
    value.addEventListener('click', () => editInline(value, entityKey));

    const chip = document.createElement('select');
    chip.className = 'chip';
    chip.dataset.focusTarget = 'type:' + entityKey;
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
      state.focusTarget = chip.dataset.focusTarget;
      changeType(entityKey, chip.value);
      refreshReview();
    });

    const meta = document.createElement('div');
    meta.className = 'entity-meta';
    const occurrenceCount = occurrenceIds(entityKey).length;
    meta.textContent = occurrenceCount === 1 ? '1 aparición' : occurrenceCount + ' apariciones';
    const discarded = off ? document.createElement('div') : null;
    if (discarded) {
      discarded.className = 'entity-state';
      discarded.textContent = 'Se conserva en el texto';
    }

    const locate = document.createElement('button');
    locate.type = 'button';
    locate.className = 'entity-locate';
    locate.dataset.focusTarget = 'locate:' + entityKey;
    locate.textContent = 'Ubicar';
    locate.setAttribute('aria-label', 'Ubicar ' + normalize(entity.best));
    locate.addEventListener('click', () => {
      state.focusTarget = locate.dataset.focusTarget;
      selectEntityLocation(entityKey);
      refreshLocation();
    });
    main.append(value, chip, meta);
    if (discarded) main.append(discarded);
    main.append(locate);
    if (active && occurrenceCount > 1) {
      const occurrenceActions = document.createElement('div');
      occurrenceActions.className = 'entity-occurrence-actions';
      for (const [direction, label, target] of [
        [-1, 'Anterior', 'previous:' + entityKey], [1, 'Siguiente', 'next:' + entityKey]
      ]) {
        const action = document.createElement('button');
        action.type = 'button';
        action.className = 'entity-occurrence-button';
        action.dataset.focusTarget = target;
        action.textContent = label;
        action.addEventListener('click', () => {
          state.focusTarget = action.dataset.focusTarget;
          navigateLocation(direction);
        });
        occurrenceActions.append(action);
      }
      main.append(occurrenceActions);
    }

    row.append(checkbox, main);
    container.append(row);
  }

}

function activeEntityIsFiltered() {
  return filteredEntities().some(([key]) => key === state.activeEntityKey);
}

function updateLocationControls() {
  const status = el('location-status');
  if (!status) return;
  const ids = occurrenceIds(state.activeEntityKey);
  if (!ids.length) {
    status.textContent = 'Selecciona Ubicar para recorrer sus apariciones.';
    return;
  }
  const index = Math.max(0, ids.indexOf(state.activeOccurrenceId));
  const entity = state.entities.get(state.activeEntityKey);
  const hiddenByFilter = !activeEntityIsFiltered();
  status.textContent = normalize(entity.best) + ': aparición ' + (index + 1) + ' de ' + ids.length
    + (state.lastNavigationWrapped ? '. Vuelta al ' + (index === 0 ? 'inicio.' : 'final.') : '.')
    + (hiddenByFilter ? ' La fila no coincide con el filtro actual; ajusta o limpia los filtros.' : '');
}

function restoreFocus() {
  if (!state.focusTarget || typeof document === 'undefined') return;
  const target = [...document.querySelectorAll('[data-focus-target]')]
    .find(control => control.dataset.focusTarget === state.focusTarget);
  state.focusTarget = null;
  if (target && typeof target.focus === 'function') {
    target.focus();
    return;
  }
  const filter = el('entity-search');
  if (filter && typeof filter.focus === 'function') filter.focus();
}

function refreshLocation({ focusEntity = false } = {}) {
  if (!state.comparing && state.activeTab !== 'document') selectTab('document');
  renderEntities();
  renderDocument({ scrollActiveLocation: !focusEntity });
  updateLocationControls();
  if (focusEntity) scheduleActiveEntityRowFocus(state.activeEntityKey, state.activeOccurrenceId);
  else restoreFocus();
}

function scheduleActiveEntityRowFocus(entityKey, occurrenceId) {
  const finalize = () => {
    // A later navigation supersedes this deferred marker activation.
    if (state.activeEntityKey === entityKey && state.activeOccurrenceId === occurrenceId) {
      focusActiveEntityRow(entityKey);
    }
  };
  // Let click default handling complete before moving focus and the entity scroll region.
  if (typeof requestAnimationFrame === 'function') requestAnimationFrame(finalize);
  else Promise.resolve().then(finalize);
}

function focusActiveEntityRow(entityKey = state.activeEntityKey) {
  if (typeof document === 'undefined' || !activeEntityIsFiltered()) return false;
  const controls = [...document.querySelectorAll('[data-focus-target]')];
  const locate = controls.find(control => control.dataset.focusTarget === 'locate:' + entityKey);
  if (!locate) return false;

  // Focus first without moving the page; the appropriate scroll region moves immediately below.
  locate.focus({ preventScroll: true });
  const row = [...document.querySelectorAll('[data-entity-key]')]
    .find(candidate => candidate.dataset.entityKey === entityKey);
  if (!row) return true;

  const narrowLayout = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    && window.matchMedia('(max-width: 1100px)').matches;
  const entityContainer = el('entities');
  if (!narrowLayout && entityContainer && typeof entityContainer.scrollTo === 'function'
      && typeof row.getBoundingClientRect === 'function' && typeof entityContainer.getBoundingClientRect === 'function') {
    const rowBounds = row.getBoundingClientRect();
    const containerBounds = entityContainer.getBoundingClientRect();
    entityContainer.scrollTo({
      top: Math.max(0, entityContainer.scrollTop + rowBounds.top - containerBounds.top
        - (containerBounds.height - rowBounds.height) / 2),
      behavior: 'auto'
    });
  } else if (typeof row.scrollIntoView === 'function') {
    row.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'auto' });
  }
  return true;
}

function navigateLocation(direction) {
  if (!navigateEntityOccurrence(direction)) return;
  refreshLocation();
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
      id: detectionId, type: 'TEXTO', start, end, entityKey: id,
      provenance: 'MANUAL', confidence: 1
    });
    state.originals.set(detectionId, Object.freeze({ start, end }));
  });
  state.edits.push(['add', id, selected]);
  rebuildEntities();
  return true;
}

function startManualEntity() {
  if (state.manualEntityDraft) return;
  state.manualEntityDraft = true;
  renderEntities();
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
        state.focusTarget = 'edit:' + key;
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
  state.resultFindings = [];
  resetResultPanels();
  setResultStatus('pending', 'Resultado pendiente', 'Revisa las entidades y pulsa «Anonimizar».');
}

function refreshReview() {
  invalidateResult();
  renderEntities();
  renderDocument();
  updateLocationControls();
  restoreFocus();
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

function isHighlightActivation(event) {
  return event.key === 'Enter' || event.key === ' ' || event.key === 'Spacebar';
}

function activateHighlightLocation(detection) {
  // Document markers only locate an occurrence. Selection remains an explicit checkbox action.
  state.activeEntityKey = detection.entityKey;
  state.activeOccurrenceId = detection.id;
  state.lastNavigationWrapped = false;
  refreshLocation({ focusEntity: true });
}

function makeHighlight(text, detection, active) {
  const mark = document.createElement('span');
  mark.className = 'hl' + (active ? ' active-location' : '');
  mark.style.color = colorOf(detection.type);
  mark.textContent = text;
  mark.title = detection.type + ' - ' + detection.provenance
    + ' - confianza ' + detection.confidence;
  mark.tabIndex = 0;
  mark.dataset.focusTarget = 'highlight:' + detection.id;
  mark.setAttribute('role', 'button');
  mark.setAttribute('aria-label', 'Ubicar y revisar ' + normalize(text));
  // Do not let the pointer's default focus retake the rerendered marker after locating.
  mark.addEventListener('mousedown', event => event.preventDefault());
  mark.addEventListener('click', event => {
    event.preventDefault();
    activateHighlightLocation(detection);
  });
  mark.addEventListener('keydown', event => {
    if (!isHighlightActivation(event)) return;
    event.preventDefault();
    activateHighlightLocation(detection);
  });
  return mark;
}

function scrollToActiveLocation(container) {
  if (!state.activeOccurrenceId || !container.querySelector) return;
  const location = container.querySelector('[data-occurrence-id="' + state.activeOccurrenceId + '"]');
  if (location && typeof location.scrollIntoView === 'function') {
    location.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' });
  }
}

// Effective tags remain the substitution projection. A location-only marker exposes
// rejected or nested occurrences without turning them into accepted substitutions.
function renderDocument({ scrollActiveLocation = true } = {}) {
  const container = el('doctext');
  container.innerHTML = '';
  const ordered = effectiveDetections();
  const active = state.detections.find(detection => detection.id === state.activeOccurrenceId);

  if (!active) {
    let cursor = 0;
    for (const detection of ordered) {
      if (detection.start > cursor) container.append(document.createTextNode(state.text.slice(cursor, detection.start)));
      container.append(makeHighlight(state.text.slice(detection.start, detection.end), detection, false));
      cursor = detection.end;
    }
    container.append(document.createTextNode(state.text.slice(cursor)));
    return;
  }

  const boundaries = new Set([0, state.text.length, active.start, active.end]);
  ordered.forEach(detection => {
    boundaries.add(detection.start);
    boundaries.add(detection.end);
  });
  const points = [...boundaries].sort((first, second) => first - second);
  for (let index = 0; index < points.length - 1; index++) {
    const start = points[index];
    const end = points[index + 1];
    if (start === end) continue;
    const visible = ordered.find(detection => detection.start <= start && detection.end >= end);
    const activeHere = active.start < end && active.end > start;
    const text = state.text.slice(start, end);
    if (visible) {
      const mark = makeHighlight(text, visible, activeHere);
      if (activeHere) mark.dataset.occurrenceId = active.id;
      container.append(mark);
    } else if (activeHere) {
      const marker = document.createElement('span');
      marker.className = 'location-marker active-location';
      marker.dataset.occurrenceId = active.id;
      marker.dataset.focusTarget = 'highlight:' + active.id;
      marker.tabIndex = -1;
      marker.setAttribute('role', 'status');
      marker.setAttribute('aria-label', 'Ubicación de ' + normalize(text) + '. Esta coincidencia está descartada.');
      marker.textContent = text;
      marker.title = 'Ubicación de ' + normalize(text) + '. Esta coincidencia no cambia la selección.';
      container.append(marker);
    } else {
      container.append(document.createTextNode(text));
    }
  }
  if (scrollActiveLocation) scrollToActiveLocation(container);
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
  setResultStatus('working', 'Generando resultado', 'Aplicando tus decisiones y verificando que el documento se pueda entregar.');
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
      console.error('Apply request failed:', data.error || data);
      setResultStatus('error', 'No se pudo completar la anonimización',
        'El servidor no pudo completar la anonimización. Revisa las entidades y vuelve a intentarlo.');
      return;
    }

    const hasResult = typeof data.markdown === 'string' && data.markdown.trim().length > 0;
    state.markdown = data.deliverable && hasResult ? data.markdown : '';
    state.warningMarkdown = !data.deliverable && hasResult ? data.markdown : '';
    state.warningDownloadEligible = !data.deliverable && hasResult;
    state.resultFindings = Array.isArray(data.findings) ? data.findings : [];
    renderResult(data, hasResult);
    renderChecks(data, hasResult);
    selectTab('result');
  } catch (err) {
    console.error('Apply request failed:', err);
    if (current()) {
      setResultStatus('error', 'No se pudo completar la anonimización',
        'No se pudo contactar con el servidor. Comprueba que la aplicación siga abierta y vuelve a intentarlo.');
    }
  } finally {
    if (request === state.applyRequest) el('apply').disabled = false;
  }
}

const GENERATED_METADATA_COMMENT = /^<!--\r?\ndoc-anonymizer [^\r\n]+\r?\ngenerado: [^\r\n]+\r?\norigen sha-256: [a-f0-9]{16,}\r?\npaginas: \d+\r?\nsustituciones: [^\r\n]+\r?\n\r?\nDOCUMENTO DESIDENTIFICADO, NO ANONIMO\. Se han sustituido identificadores\r?\ndirectos detectados\. Fechas, importes, cargos, localidades y hechos\r?\nsingulares permanecen y pueden permitir reidentificar\. Requiere revision\r?\nhumana antes de compartir\.\r?\n-->\r?\n(?:[ \t]*\r?\n)*/;

function previewMarkdown(markdown) {
  return typeof markdown === 'string' ? markdown.replace(GENERATED_METADATA_COMMENT, '') : markdown;
}

function renderResult(data, hasResult) {
  const deliverable = data.deliverable && hasResult;
  el('markdown').textContent = hasResult
    ? previewMarkdown(data.markdown)
    : 'ANONIMIZACIÓN BLOQUEADA: el resultado no se entrega.\n\n'
      + 'Revisa el estado del resultado para conocer el control que falló.';
  el('warning-note').textContent = 'Este resultado puede contener datos personales residuales. Descargar o copiar implica aceptar ese riesgo; no es seguro para entregar.';
  el('download').disabled = !deliverable;
  el('copy').disabled = !deliverable && !state.warningDownloadEligible;
  el('copy-label').textContent = 'Copiar';
  el('warning-download').disabled = !state.warningDownloadEligible;
  el('warning-download').classList.toggle('hidden', !state.warningDownloadEligible);
  el('warning-note').classList.toggle('hidden', !state.warningDownloadEligible);
}

function renderChecks(data, hasResult) {
  const detail = state.resultFindings.length
    ? state.resultFindings.map(finding => finding.control + ': ' + finding.detail).join(' ')
    : 'La verificación encontró un riesgo. Revisa las entidades y vuelve a anonimizar.';
  if (data.deliverable && hasResult) {
    const message = data.substitutions + ' sustituciones en ' + data.entities + ' entidades. El Markdown está listo para descargar o copiar.';
    setResultStatus('success', 'Anonimización completada', message);
    showToast('Anonimización completada', message, 'success');
    return;
  }

  setResultStatus('blocked', 'Anonimización bloqueada', detail);
  showToast('Anonimización bloqueada', detail, 'warning');
}

function setResultStatus(status, title, message) {
  state.resultStatus = status;
  state.resultStatusTitle = title;
  state.resultStatusMessage = message;
  const panel = el('result-status');
  if (!panel) return;
  panel.className = 'result-status ' + status;
  panel.textContent = title + ': ' + message;
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
  el('copy-label').textContent = 'Copiar';
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

  setComparisonVisibility('result-actions', !state.comparing && state.activeTab !== 'result');
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

function markdownDownloadFilename(sourceFilename) {
  const filename = typeof sourceFilename === 'string' ? sourceFilename.trim() : '';
  if (!filename || /[\\/\u0000-\u001F]/u.test(filename)) return 'documento-anonimused.md';
  const dot = filename.lastIndexOf('.');
  const stem = dot > 0 ? filename.slice(0, dot) : filename;
  return stem && stem !== '.' && stem !== '..'
    ? stem + '-anonimused.md'
    : 'documento-anonimused.md';
}

function saveMarkdown(markdown) {
  const blob = new Blob([markdown], { type: 'text/markdown;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = markdownDownloadFilename(state.sourceFilename);
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
  const warning = state.warningDownloadEligible && !!state.warningMarkdown;
  const markdown = warning ? state.warningMarkdown : state.markdown;
  if (!markdown) return;
  const revision = state.revision;
  const request = ++state.copyRequest;
  const current = () => revision === state.revision
    && markdown === (warning ? state.warningMarkdown : state.markdown)
    && warning === (state.warningDownloadEligible && !!state.warningMarkdown)
    && request === state.copyRequest;
  const label = 'Copiar';
  try {
    await navigator.clipboard.writeText(markdown);
    if (!current()) return;
    el('copy-label').textContent = 'Copiado';
    setTimeout(() => {
      if (current()) el('copy-label').textContent = label;
    }, 1500);
  } catch (err) {
    if (current()) {
      setResultStatus('error', 'No se pudo copiar el resultado',
        warning ? 'El navegador bloqueó el portapapeles. El resultado sigue bloqueado y puede contener datos personales.'
          : 'El navegador bloqueó el acceso al portapapeles. Usa «Descargar .md» para guardar el resultado.');
    }
  }
}

if (typeof document !== 'undefined') init();
if (typeof module !== 'undefined') module.exports = {
  state, normalize, narrow, groupEntities, changeText, changeType, reviewBody,
  addManualEntity, startManualEntity, editInline, invalidateResult, apply, analyze, effectiveDetections, renderDocument,
  renderEntities,
  selectTab, toggleCompare, download, downloadWithWarnings, filteredEntities, setEntityFilters,
  selectEntityLocation, navigateEntityOccurrence, occurrenceIds, isHighlightActivation, restoreFocus,
  copyMarkdown, previewMarkdown
};
