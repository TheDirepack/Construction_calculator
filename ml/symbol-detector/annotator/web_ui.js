// Browser-only annotation editor. Pure geometry helpers are exported for tests.
export function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}
export function screenToImage(x, y, view) {
  return [(x - view.x) / view.scale, (y - view.y) / view.scale];
}
export function imageToScreen(x, y, view) {
  return [x * view.scale + view.x, y * view.scale + view.y];
}
export function normalizedBox(a, b, width, height) {
  const x0 = clamp(Math.min(a[0], b[0]), 0, width);
  const y0 = clamp(Math.min(a[1], b[1]), 0, height);
  const x1 = clamp(Math.max(a[0], b[0]), 0, width);
  const y1 = clamp(Math.max(a[1], b[1]), 0, height);
  return [x0, y0, x1, y1];
}
export function rotatedAabb(cx, cy, w, h, angle) {
  const a = angle * Math.PI / 180, c = Math.cos(a), s = Math.sin(a);
  const dx = Math.abs(w * c) / 2 + Math.abs(h * s) / 2;
  const dy = Math.abs(w * s) / 2 + Math.abs(h * c) / 2;
  return [cx - dx, cy - dy, cx + dx, cy + dy];
}
export function rotatedCorners(rot) {
  const a = rot.angle_deg * Math.PI / 180;
  const c = Math.cos(a), s = Math.sin(a);
  const hw = rot.w / 2, hh = rot.h / 2;
  return [
    [rot.cx - hw * c + hh * s, rot.cy - hw * s - hh * c],
    [rot.cx + hw * c + hh * s, rot.cy + hw * s - hh * c],
    [rot.cx + hw * c - hh * s, rot.cy + hw * s + hh * c],
    [rot.cx - hw * c - hh * s, rot.cy - hw * s + hh * c],
  ];
}
export function rotatedHandlePoint(rot, scale) {
  const a = rot.angle_deg * Math.PI / 180;
  const dist = rot.h / 2 + 24 / scale;
  return [rot.cx + dist * Math.sin(a), rot.cy - dist * Math.cos(a)];
}
export function pointInRotatedBox(rot, x, y) {
  const a = -rot.angle_deg * Math.PI / 180;
  const dx = x - rot.cx, dy = y - rot.cy;
  const lx = dx * Math.cos(a) - dy * Math.sin(a);
  const ly = dx * Math.sin(a) + dy * Math.cos(a);
  return Math.abs(lx) <= rot.w / 2 && Math.abs(ly) <= rot.h / 2;
}
export function boxIoU(a, b) {
  const ix0 = Math.max(a[0], b[0]), iy0 = Math.max(a[1], b[1]);
  const ix1 = Math.min(a[2], b[2]), iy1 = Math.min(a[3], b[3]);
  const iw = Math.max(0, ix1 - ix0), ih = Math.max(0, iy1 - iy0);
  const inter = iw * ih;
  if (inter <= 0) return 0;
  const areaA = Math.max(0, a[2] - a[0]) * Math.max(0, a[3] - a[1]);
  const areaB = Math.max(0, b[2] - b[0]) * Math.max(0, b[3] - b[1]);
  return inter / Math.max(1e-9, areaA + areaB - inter);
}
export function hitBox(box, x, y, scale, includeCorners, rotated = null) {
  const tolerance = 8 / scale;
  if (rotated) {
    if (includeCorners) {
      const [rx, ry] = rotatedHandlePoint(rotated, scale);
      if (Math.hypot(rx - x, ry - y) <= 9 / scale) return "rotate";
      const [nw, ne, se, sw] = rotatedCorners(rotated);
      for (const [name, pt] of [["rot-nw", nw], ["rot-ne", ne], ["rot-se", se], ["rot-sw", sw]]) {
        if (Math.hypot(pt[0] - x, pt[1] - y) <= tolerance) return name;
      }
    }
    if (pointInRotatedBox(rotated, x, y)) return "move";
    return null;
  }
  const [x0, y0, x1, y1] = box;
  if (includeCorners) {
    const rx = (x0 + x1) / 2;
    const ry = y0 - 24 / scale;
    if (Math.hypot(rx - x, ry - y) <= 9 / scale) return "rotate";
    for (const [name, cx, cy] of [
      ["nw", x0, y0], ["ne", x1, y0],
      ["sw", x0, y1], ["se", x1, y1],
    ]) {
      if (Math.abs(cx - x) <= tolerance && Math.abs(cy - y) <= tolerance) return name;
    }
  }
  if (x >= x0 && x <= x1 && y >= y0 && y <= y1) return "move";
  return null;
}

const ID_REGEX = /^[a-zA-Z0-9_.:-]{1,120}$/;
function generateId() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    try {
      return crypto.randomUUID().slice(0, 8);
    } catch (_) {}
  }
  return Math.random().toString(36).slice(2, 10);
}
const QUICK_FAMILIES = [
  "receptacle", "switch", "luminaire", "fire_alarm_device", "data_outlet",
  "junction_box", "sensor", "security_device", "disconnect",
];
const VIEW_FILTERS = [
  { name: "Normal", css: "none" },
  { name: "Dim", css: "brightness(0.72) contrast(1.08)" },
  { name: "Invert", css: "invert(0.92) hue-rotate(180deg) contrast(1.1)" },
];
const $ = (id) => document.getElementById(id);

const state = {
  token: null, page: 0, pageCount: 1, revision: null, doc: null,
  loadingPage: null, pendingPage: null, lastLoadError: "",
  image: null, imageCache: new Map(), imagePreloads: new Map(),
  preloadTimer: null, preloadRetryTimer: null, reviewLoading: false,
  mode: "pan", selectedId: null,
  selectedIds: new Set(),
  pageCache: {},
  conflictPage: null,
  scale: 1, x: 0, y: 0, space: false, drag: null, dirty: false,
  undo: [], redo: [], lastFamily: "receptacle", lastVariantByFamily: {}, customVariantsByFamily: {}, busy: false,
  showLabels: true, showLoupe: true, showWalls: true,
  viewFilterIdx: 0,
  clipboard: null,
  qualityWarnings: [],
  warningCursor: 0,
  activeAnchorName: "auto",
  anchorTargetId: null,
  pointerImg: null, pointerScreen: null,
  orientedDraft: null,
  snapPreview: null,
  activeAnchorSnap: null,
  snapSensitivity: "high",
  walls: [],
  familyDisciplines: {},
  variantsByFamily: {},
  attributeSchema: { schemas: {}, family_map: {}, numeric: [], string: [], flags: [], list_fields: [] },
  modeKind: "pdf",
  jsonFilename: "",
  jsonPath: "",
  siblingFiles: [],
  documents: [],
  currentDocPath: "",
  currentDocName: "",
  pdfMenuFilter: "all",
  pdfMenuQuery: "",
  deviceCatalog: { categories: [], devices: [], summary: {} },
  deviceCatalogQuery: "",
  deviceCatalogCategory: "all",
  deviceModalQuery: "",
  deviceModalCategory: "all",
  detectorInfo: null,
  evalConfidenceManual: false,
  evalConfidenceRecommended: false,
};

// Read by the browser tests (tests/test_annotator_long_documents.py).
if (typeof window !== "undefined") window.__labelerState = state;

const elements = typeof document === "undefined" ? null : {
  canvas: $("canvas"), viewport: $("viewport"), items: $("items"),
};

function draftStorageKey() {
  return `blueprint_labeler_draft::${state.jsonPath || state.jsonFilename || ("page_" + state.page)}`;
}
function draftCompatible(parsed) {
  return Boolean(state.doc && parsed && Array.isArray(parsed.instances) &&
    Array.isArray(parsed.rejected_proposals) && parsed.revision === state.revision &&
    parsed.image_sha256 === state.doc.image_sha256 &&
    parsed.source_pdf_sha256 === (state.doc.source_pdf_sha256 || null) &&
    parsed.image_width === state.doc.image_width &&
    parsed.image_height === state.doc.image_height &&
    parsed.coordinate_system === state.doc.coordinate_system);
}
function saveLocalDraft() {
  if (!state.doc || !state.dirty) return;
  try {
    window.localStorage?.setItem(draftStorageKey(), JSON.stringify({
      revision: state.revision,
      image_sha256: state.doc.image_sha256,
      source_pdf_sha256: state.doc.source_pdf_sha256 || null,
      image_width: state.doc.image_width,
      image_height: state.doc.image_height,
      coordinate_system: state.doc.coordinate_system,
      instances: state.doc.instances,
      rejected_proposals: state.doc.rejected_proposals || [],
      verified_no_symbols: state.doc.verified_no_symbols === true,
      savedAt: Date.now(),
    }));
  } catch (_) {}
}
function clearLocalDraft() {
  try { window.localStorage?.removeItem(draftStorageKey()); } catch (_) {}
  if ($("draftBanner")) $("draftBanner").hidden = true;
}
function checkLocalDraft() {
  try {
    const raw = window.localStorage?.getItem(draftStorageKey());
    if (!raw) { $("draftBanner").hidden = true; return; }
    const parsed = JSON.parse(raw);
    // The saved JSON revision can be null for a new PDF page. Never restore
    // across a raster/source change; retain incompatible draft bytes as backup.
    $("draftBanner").hidden = !draftCompatible(parsed);
  } catch (_) {
    $("draftBanner").hidden = true;
  }
}

function notify(message, kind = "") {
  const target = $("message");
  target.textContent = message;
  target.className = kind;
}

// Native window.confirm() can be suppressed by the browser (mobile Chrome
// offers "prevent additional dialogs"), after which it silently returns false.
// Every confirmation goes through this in-page dialog instead.
let pendingConfirm = null;
function confirmAction(message, options = {}) {
  const dialog = $("confirmDialog");
  if (!dialog || typeof dialog.showModal !== "function") {
    return Promise.resolve(window.confirm(message));
  }
  $("confirmTitle").textContent = options.title || "Confirm";
  $("confirmMessage").textContent = message;
  $("confirmOkBtn").textContent = options.confirmLabel || "Confirm";
  $("confirmCancelBtn").textContent = options.cancelLabel || "Cancel";
  dialog.showModal();
  return new Promise((resolve) => { pendingConfirm = resolve; });
}
function settleConfirm(value) {
  const dialog = $("confirmDialog");
  if (dialog && dialog.open) dialog.close();
  const resolve = pendingConfirm;
  pendingConfirm = null;
  if (resolve) resolve(value);
}
function updateDirtyIndicator() {
  const dirtyPages = new Set();
  for (const [pStr, c] of Object.entries(state.pageCache || {})) {
    if (c.dirty) dirtyPages.add(Number(pStr) + 1);
  }
  if (state.dirty) dirtyPages.add(state.page + 1);

  const dirtyEl = $("dirty");
  if (!dirtyEl) return;
  if (dirtyPages.size > 0) {
    const sorted = [...dirtyPages].sort((a, b) => a - b);
    dirtyEl.textContent = `Unsaved (p.${sorted.join(", ")})`;
    dirtyEl.className = "warning";
    $("save").disabled = false;
  } else {
    dirtyEl.textContent = "Saved";
    dirtyEl.className = "saved";
    $("save").disabled = state.revision !== null;
  }
}

function editState() {
  state.dirty = true;
  if (state.doc) {
    state.pageCache[state.page] = {
      doc: JSON.parse(JSON.stringify(state.doc)),
      dirty: true,
      revision: state.revision,
      undo: [...state.undo],
      redo: [...state.redo],
      selectedIds: new Set(state.selectedIds),
      selectedId: state.selectedId,
    };
  }
  updateDirtyIndicator();
  saveLocalDraft();
}

function savedState() {
  state.dirty = false;
  if (state.pageCache[state.page]) {
    state.pageCache[state.page].dirty = false;
  }
  updateDirtyIndicator();
  $("conflictBanner").hidden = true;
  clearLocalDraft();
}

function snapshot() {
  return JSON.stringify({
    instances: state.doc.instances,
    rejected_proposals: state.doc.rejected_proposals || [],
    verified_no_symbols: state.doc.verified_no_symbols === true,
    selectedId: state.selectedId,
    selectedIds: Array.from(state.selectedIds || []),
    meta: {
      source_id: state.doc.source_id || "",
      project_family_id: state.doc.project_family_id || "",
      document_id: state.doc.document_id || "",
      notes: state.doc.notes || "",
    },
  });
}

function remember() {
  state.undo.push(snapshot());
  if (state.undo.length > 80) state.undo.shift();
  state.redo.length = 0;
}

function restore(snapshotText) {
  const data = JSON.parse(snapshotText);
  state.doc.instances = data.instances;
  state.doc.rejected_proposals = data.rejected_proposals;
  state.doc.verified_no_symbols = data.verified_no_symbols === true;
  state.selectedId = data.selectedId;
  state.selectedIds = new Set(data.selectedIds || (data.selectedId ? [data.selectedId] : []));
  if (data.meta) Object.assign(state.doc, data.meta);
  editState();
  refresh();
}

function undo() {
  if (state.busy || !state.undo.length) return;
  state.redo.push(snapshot());
  restore(state.undo.pop());
}

function redo() {
  if (state.busy || !state.redo.length) return;
  state.undo.push(snapshot());
  restore(state.redo.pop());
}

function chosen() {
  return state.doc?.instances.find((x) => x.instance_id === state.selectedId) ?? null;
}

function chosenList() {
  if (!state.doc?.instances) return [];
  if (state.selectedIds && state.selectedIds.size > 0) {
    return state.doc.instances.filter(x => state.selectedIds.has(x.instance_id));
  }
  const single = chosen();
  return single ? [single] : [];
}
function coordinate(event) {
  const r = elements.canvas.getBoundingClientRect();
  return [event.clientX - r.left, event.clientY - r.top];
}
function viewport() {
  return { scale: state.scale, x: state.x, y: state.y };
}
function disciplineFor(family) {
  return state.familyDisciplines[family] || "unmapped";
}
function itemCenter(item) {
  if (item.anchors?.circle_center?.xy) return [...item.anchors.circle_center.xy];
  if (item.anchors?.visual_center?.xy) return [...item.anchors.visual_center.xy];
  if (item.bbox_rotated) return [item.bbox_rotated.cx, item.bbox_rotated.cy];
  const b = item.bbox_xyxy;
  return [(b[0] + b[2]) / 2, (b[1] + b[3]) / 2];
}

function getBoxSnapPoints(item) {
  if (!item) return [];
  const points = [];
  if (item.bbox_rotated && Math.abs(item.bbox_rotated.angle_deg || 0) > 1e-4) {
    const { cx, cy, w, h, angle_deg } = item.bbox_rotated;
    const a = (angle_deg * Math.PI) / 180;
    const cosA = Math.cos(a), sinA = Math.sin(a);
    const u = [cosA, sinA];
    const n = [-sinA, cosA];
    points.push({ name: "Center", key: "center", xy: [cx, cy] });
    points.push({ name: "Top Edge", key: "top", xy: [cx - n[0] * (h / 2), cy - n[1] * (h / 2)] });
    points.push({ name: "Bottom Edge", key: "bottom", xy: [cx + n[0] * (h / 2), cy + n[1] * (h / 2)] });
    points.push({ name: "Left Edge", key: "left", xy: [cx - u[0] * (w / 2), cy - u[1] * (w / 2)] });
    points.push({ name: "Right Edge", key: "right", xy: [cx + u[0] * (w / 2), cy + u[1] * (w / 2)] });
  } else {
    const [x0, y0, x1, y1] = item.bbox_xyxy;
    const midX = (x0 + x1) / 2;
    const midY = (y0 + y1) / 2;
    points.push({ name: "Center", key: "center", xy: [midX, midY] });
    points.push({ name: "Top Edge", key: "top", xy: [midX, y0] });
    points.push({ name: "Bottom Edge", key: "bottom", xy: [midX, y1] });
    points.push({ name: "Left Edge", key: "left", xy: [x0, midY] });
    points.push({ name: "Right Edge", key: "right", xy: [x1, midY] });
  }
  return points;
}
function computeQualityWarnings() {
  if (!state.doc) return [];
  const warnings = [];
  const list = state.doc.instances;
  for (let i = 0; i < list.length; i++) {
    const a = list[i];
    const w = a.bbox_xyxy[2] - a.bbox_xyxy[0];
    const h = a.bbox_xyxy[3] - a.bbox_xyxy[1];
    if (w < 6 || h < 6) {
      warnings.push({ id: a.instance_id, reason: `Tiny box (${w.toFixed(1)}×${h.toFixed(1)} px)` });
    }
    if (a.review_status === "accepted" && incompleteReason(a)) {
      // Saving refuses verified boxes that do not name a specific device.
      warnings.push({ id: a.instance_id, reason: "Verified without a device type: pick it, or Unverify" });
    }
    for (let j = i + 1; j < list.length; j++) {
      const b = list[j];
      if (boxIoU(a.bbox_xyxy, b.bbox_xyxy) > 0.85) {
        warnings.push({ id: b.instance_id, reason: `High overlap (>85% IoU) with ${a.instance_id}` });
      }
    }
  }
  return warnings;
}

function fit() {
  if (!state.image) return;
  const w = elements.canvas.clientWidth, h = elements.canvas.clientHeight;
  state.scale = Math.min((w - 40) / state.image.naturalWidth, (h - 40) / state.image.naturalHeight);
  state.scale = Math.max(state.scale, 0.015);
  state.x = (w - state.image.naturalWidth * state.scale) / 2;
  state.y = (h - state.image.naturalHeight * state.scale) / 2;
  draw();
}
function zoomAt(x, y, ratio) {
  if (!state.image) return;
  const next = clamp(state.scale * ratio, 0.02, 24);
  const [px, py] = screenToImage(x, y, viewport());
  state.scale = next;
  state.x = x - px * next;
  state.y = y - py * next;
  draw();
}
function centerBox(box) {
  if (!state.image) return;
  const cx = (box[0] + box[2]) / 2, cy = (box[1] + box[3]) / 2;
  state.x = elements.canvas.clientWidth / 2 - cx * state.scale;
  state.y = elements.canvas.clientHeight / 2 - cy * state.scale;
  draw();
}
function labelColor(item) {
  if (item.instance_id === state.selectedId) return "#33a7ff";
  if (item.review_status === "accepted") return "#25b880";
  if (item.uncertain || item.review_status === "unreviewed") return "#e6a745";
  return "#5ec2d8";
}

function drawArrow(ctx, x0, y0, x1, y1, color, lineWidth = 2) {
  const dx = x1 - x0, dy = y1 - y0;
  const len = Math.hypot(dx, dy);
  if (len < 2) return;
  const ux = dx / len, uy = dy / len;
  ctx.save();
  ctx.strokeStyle = color;
  ctx.fillStyle = color;
  ctx.lineWidth = lineWidth;
  ctx.beginPath();
  ctx.moveTo(x0, y0);
  ctx.lineTo(x1, y1);
  ctx.stroke();
  const head = 8;
  ctx.beginPath();
  ctx.moveTo(x1, y1);
  ctx.lineTo(x1 - head * ux - 4 * uy, y1 - head * uy + 4 * ux);
  ctx.lineTo(x1 - head * ux + 4 * uy, y1 - head * uy - 4 * ux);
  ctx.closePath();
  ctx.fill();
  ctx.restore();
}

function drawBadge(ctx, x, y, text, bg = "rgba(14,21,28,.9)", fg = "#e8f0f4") {
  ctx.save();
  ctx.font = "11.5px system-ui, sans-serif";
  const tw = ctx.measureText(text).width + 10;
  ctx.fillStyle = bg;
  ctx.fillRect(x, y, tw, 19);
  ctx.strokeStyle = "#486375";
  ctx.lineWidth = 1;
  ctx.strokeRect(x, y, tw, 19);
  ctx.fillStyle = fg;
  ctx.fillText(text, x + 5, y + 13);
  ctx.restore();
}

// The loupe magnifies what is being placed or dragged: the anchor snap point,
// the corner or edge handle of a box being resized, the far end of a box being
// drawn. Only with nothing more specific does it fall back to the pointer.
function loupeFocus() {
  const d = state.drag;
  if (state.mode === "anchor" && state.activeAnchorSnap) return state.activeAnchorSnap.xy;
  if (d && d.current && (d.type === "draw" || d.type === "oriented" || d.type === "snap" ||
                         d.type === "oriented-axis")) {
    return d.current;
  }
  if (d && d.selected) {
    const item = state.doc?.instances.find(x => x.instance_id === d.selected);
    if (item) {
      if (d.type.startsWith("rot-") && item.bbox_rotated) {
        const corners = rotatedCorners(item.bbox_rotated);
        const idx = { "rot-nw": 0, "rot-ne": 1, "rot-se": 2, "rot-sw": 3 }[d.type];
        if (idx !== undefined) return corners[idx];
      }
      const [x0, y0, x1, y1] = item.bbox_xyxy;
      const handle = { nw: [x0, y0], ne: [x1, y0], sw: [x0, y1], se: [x1, y1] }[d.type];
      if (handle) return handle;
    }
  }
  return state.pointerImg;
}

function drawLoupe(ctx, canvasW, canvasH) {
  if (!state.showLoupe || !state.image || !state.pointerImg) return;
  const activePrecision =
    state.mode === "draw" || state.mode === "oriented" || state.mode === "anchor" ||
    state.mode === "snap" || Boolean(state.drag && state.drag.type !== "pan");
  if (!activePrecision) return;

  const size = 148;
  const margin = 14;
  const lx = canvasW - size - margin;
  const ly = canvasH - size - margin;
  if (lx < 160 || ly < 80) return;

  const zoom = Math.max(4, state.scale * 2.5);
  const [ix, iy] = loupeFocus();
  const srcHalf = (size / 2) / zoom;

  ctx.save();
  ctx.beginPath();
  ctx.rect(lx, ly, size, size);
  ctx.clip();
  ctx.fillStyle = "#0b1218";
  ctx.fillRect(lx, ly, size, size);
  ctx.filter = VIEW_FILTERS[state.viewFilterIdx].css;
  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(
    state.image,
    ix - srcHalf, iy - srcHalf, srcHalf * 2, srcHalf * 2,
    lx, ly, size, size
  );
  ctx.filter = "none";
  ctx.strokeStyle = "rgba(86, 180, 219, 0.85)";
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(lx + size / 2, ly); ctx.lineTo(lx + size / 2, ly + size);
  ctx.moveTo(lx, ly + size / 2); ctx.lineTo(lx + size, ly + size / 2);
  ctx.stroke();
  ctx.restore();

  ctx.save();
  ctx.strokeStyle = "#56b4db";
  ctx.lineWidth = 1.5;
  ctx.strokeRect(lx, ly, size, size);
  drawBadge(ctx, lx + 4, ly + size - 23, `${ix.toFixed(1)}, ${iy.toFixed(1)}`);
  ctx.restore();
}

function draw() {
  const canvas = elements.canvas;
  if (!canvas || !state.doc) return;
  const w = canvas.clientWidth, h = canvas.clientHeight;
  if (!w || !h) return;
  if (state.drag?.type === "pan") {
    canvas.style.cursor = "grabbing";
  } else if (state.mode === "pan" || state.space) {
    canvas.style.cursor = "grab";
  } else if (state.mode === "draw" || state.mode === "oriented" || state.mode === "anchor" || state.mode === "snap") {
    canvas.style.cursor = "crosshair";
  } else {
    canvas.style.cursor = "default";
  }
  const dpr = window.devicePixelRatio || 1;
  if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
  }
  const ctx = canvas.getContext("2d");
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);
  ctx.fillStyle = "#0c1319";
  ctx.fillRect(0, 0, w, h);
  if (state.image) {
    ctx.save();
    ctx.filter = VIEW_FILTERS[state.viewFilterIdx].css;
    ctx.imageSmoothingEnabled = state.scale < 1;
    ctx.drawImage(
      state.image, state.x, state.y,
      state.image.naturalWidth * state.scale,
      state.image.naturalHeight * state.scale
    );
    ctx.restore();
  }

  if (state.showWalls && state.walls.length) {
    ctx.save();
    ctx.strokeStyle = "rgba(45, 212, 191, 0.5)";
    ctx.lineWidth = 1.5;
    ctx.setLineDash([6, 4]);
    for (const wall of state.walls) {
      const [x0, y0] = imageToScreen(wall.start[0], wall.start[1], viewport());
      const [x1, y1] = imageToScreen(wall.end[0], wall.end[1], viewport());
      ctx.beginPath();
      ctx.moveTo(x0, y0); ctx.lineTo(x1, y1);
      ctx.stroke();
    }
    ctx.restore();
  }

  if ($("filter").value === "rejected") {
    for (const rej of state.doc.rejected_proposals || []) {
      const box = rej.bbox_xyxy;
      const [sx0, sy0] = imageToScreen(box[0], box[1], viewport());
      const bw = (box[2] - box[0]) * state.scale;
      const bh = (box[3] - box[1]) * state.scale;
      ctx.save();
      ctx.strokeStyle = "#d95b68";
      ctx.lineWidth = 1.5;
      ctx.setLineDash([4, 4]);
      ctx.strokeRect(sx0, sy0, bw, bh);
      ctx.restore();
    }
  }

  for (const item of state.doc.instances) {
    const box = item.bbox_xyxy;
    const [sx0, sy0] = imageToScreen(box[0], box[1], viewport());
    const bw = (box[2] - box[0]) * state.scale;
    const bh = (box[3] - box[1]) * state.scale;
    const color = labelColor(item);
    const isSel = Boolean(state.selectedIds?.has(item.instance_id) || item.instance_id === state.selectedId);

    if (item.bbox_rotated) {
      ctx.save();
      ctx.strokeStyle = isSel ? "rgba(51, 167, 255, 0.35)" : "rgba(180, 200, 215, 0.25)";
      ctx.lineWidth = 1;
      ctx.setLineDash([3, 3]);
      ctx.strokeRect(sx0, sy0, bw, bh);
      ctx.restore();

      const rot = item.bbox_rotated;
      const corners = rotatedCorners(rot).map(pt => imageToScreen(pt[0], pt[1], viewport()));
      ctx.save();
      ctx.beginPath();
      ctx.moveTo(corners[0][0], corners[0][1]);
      for (let i = 1; i < 4; i++) ctx.lineTo(corners[i][0], corners[i][1]);
      ctx.closePath();
      if (isSel) {
        ctx.fillStyle = "rgba(51, 167, 255, 0.11)";
        ctx.fill();
      }
      ctx.strokeStyle = isSel ? "#67c6ff" : color;
      ctx.lineWidth = isSel ? 2.2 : 1.7;
      ctx.stroke();
      ctx.restore();

      if (isSel) {
        const [cx, cy] = imageToScreen(rot.cx, rot.cy, viewport());
        const a = rot.angle_deg * Math.PI / 180;
        const axisLen = Math.max(14, (rot.w * state.scale) * 0.45);
        drawArrow(ctx, cx, cy, cx + Math.cos(a) * axisLen, cy + Math.sin(a) * axisLen, "#7dd3fc", 1.5);
      }
    } else {
      ctx.lineWidth = isSel ? 2.2 : 1.5;
      ctx.strokeStyle = color;
      if (isSel) {
        ctx.fillStyle = "rgba(51, 167, 255, 0.08)";
        ctx.fillRect(sx0, sy0, bw, bh);
      }
      ctx.strokeRect(sx0, sy0, bw, bh);
    }

    if (item.anchors && (isSel || state.scale >= 0.4)) {
      for (const [name, anchor] of Object.entries(item.anchors)) {
        if (!anchor || !Array.isArray(anchor.xy)) continue;
        const [ax, ay] = imageToScreen(anchor.xy[0], anchor.xy[1], viewport());
        ctx.save();
        if (name === "circle_center") {
          ctx.strokeStyle = "#38e8ff";
          ctx.fillStyle = "#38e8ff";
          ctx.lineWidth = 1.8;
          ctx.beginPath();
          ctx.arc(ax, ay, 5.5, 0, Math.PI * 2);
          ctx.stroke();
          ctx.beginPath();
          ctx.moveTo(ax - 7, ay); ctx.lineTo(ax + 7, ay);
          ctx.moveTo(ax, ay - 7); ctx.lineTo(ax, ay + 7);
          ctx.stroke();
        } else if (name === "wall_attachment") {
          ctx.strokeStyle = "#ff9f43";
          ctx.fillStyle = "#ff9f43";
          ctx.lineWidth = 1.8;
          ctx.beginPath();
          ctx.moveTo(ax, ay - 6); ctx.lineTo(ax + 6, ay);
          ctx.lineTo(ax, ay + 6); ctx.lineTo(ax - 6, ay);
          ctx.closePath();
          ctx.stroke();
        } else {
          ctx.strokeStyle = "#f7d046";
          ctx.fillStyle = "#f7d046";
          ctx.lineWidth = 1.8;
          ctx.beginPath();
          ctx.moveTo(ax - 6, ay); ctx.lineTo(ax + 6, ay);
          ctx.moveTo(ax, ay - 6); ctx.lineTo(ax, ay + 6);
          ctx.stroke();
        }
        if (isSel) {
          ctx.font = "11px system-ui, sans-serif";
          ctx.fillText(name, ax + 8, ay + 4);
        }
        ctx.restore();
      }
    }

    if (item.snap_vector && Array.isArray(item.snap_vector.origin) && Array.isArray(item.snap_vector.direction)) {
      const [ox, oy] = imageToScreen(item.snap_vector.origin[0], item.snap_vector.origin[1], viewport());
      const [dx, dy] = item.snap_vector.direction;
      const len = Math.hypot(dx, dy) || 1;
      const arrowPx = Math.max(26, Math.min(64, 34 * state.scale));
      drawArrow(ctx, ox, oy, ox + (dx / len) * arrowPx, oy + (dy / len) * arrowPx, "#ff7eb6", 2.2);
      ctx.save();
      ctx.fillStyle = "#ff7eb6";
      ctx.beginPath();
      ctx.arc(ox, oy, 3.5, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
    }

    if (state.showLabels && bw > 24 && bh > 9) {
      const rotTag = item.bbox_rotated ? ` ${Math.round(item.bbox_rotated.angle_deg)}°` : "";
      const text = item.symbol_family +
        (item.symbol_variant !== "unspecified" ? "/" + item.symbol_variant : "") + rotTag;
      ctx.font = "12px system-ui, sans-serif";
      const tw = Math.min(260, ctx.measureText(text).width + 9);
      ctx.fillStyle = color;
      ctx.fillRect(sx0, Math.max(0, sy0 - 19), tw, 18);
      ctx.fillStyle = "#08131b";
      ctx.fillText(text.slice(0, 42), sx0 + 4, Math.max(12, sy0 - 5));
    }

    if (isSel && (!state.selectedIds || state.selectedIds.size <= 1)) {
      ctx.fillStyle = "#fff"; ctx.strokeStyle = color; ctx.lineWidth = 2;
      if (item.bbox_rotated) {
        const corners = rotatedCorners(item.bbox_rotated).map(pt => imageToScreen(pt[0], pt[1], viewport()));
        for (const [cx, cy] of corners) {
          ctx.fillRect(cx - 4, cy - 4, 8, 8);
          ctx.strokeRect(cx - 4, cy - 4, 8, 8);
        }
        const topMid = [(corners[0][0] + corners[1][0]) / 2, (corners[0][1] + corners[1][1]) / 2];
        const [rx, ry] = imageToScreen(...rotatedHandlePoint(item.bbox_rotated, state.scale), viewport());
        ctx.beginPath();
        ctx.moveTo(topMid[0], topMid[1]);
        ctx.lineTo(rx, ry);
        ctx.stroke();
        ctx.beginPath();
        ctx.arc(rx, ry, 5, 0, Math.PI * 2);
        ctx.fill();
        ctx.stroke();
      } else {
        for (const [cx, cy] of [[sx0, sy0], [sx0 + bw, sy0],
                                [sx0, sy0 + bh], [sx0 + bw, sy0 + bh]]) {
          ctx.fillRect(cx - 4, cy - 4, 8, 8);
          ctx.strokeRect(cx - 4, cy - 4, 8, 8);
        }
        const rx = sx0 + bw / 2, ry = sy0 - 24;
        ctx.beginPath();
        ctx.moveTo(rx, sy0);
        ctx.lineTo(rx, ry + 5);
        ctx.stroke();
        ctx.beginPath();
        ctx.arc(rx, ry, 5, 0, Math.PI * 2);
        ctx.fill();
        ctx.stroke();
      }
    }
  }

  if (state.snapPreview?.point) {
    const [px, py] = imageToScreen(state.snapPreview.point[0], state.snapPreview.point[1], viewport());
    ctx.save();
    ctx.strokeStyle = "#2dd4bf";
    ctx.lineWidth = 2.2;
    ctx.beginPath();
    ctx.arc(px, py, 8, 0, Math.PI * 2);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(px - 10, py); ctx.lineTo(px + 10, py);
    ctx.moveTo(px, py - 10); ctx.lineTo(px, py + 10);
    ctx.stroke();
    drawBadge(ctx, px + 10, py - 10, `snap: ${state.snapPreview.kind}`);
    ctx.restore();
  }

  if (state.drag?.type === "marquee") {
    const box = normalizedBox(state.drag.start, state.drag.current,
                              state.doc.image_width, state.doc.image_height);
    const [x, y] = imageToScreen(box[0], box[1], viewport());
    const bw = (box[2] - box[0]) * state.scale;
    const bh = (box[3] - box[1]) * state.scale;
    ctx.save();
    ctx.strokeStyle = "#56b4db";
    ctx.lineWidth = 1.5;
    ctx.setLineDash([4, 4]);
    ctx.fillStyle = "rgba(86, 180, 219, 0.18)";
    ctx.fillRect(x, y, bw, bh);
    ctx.strokeRect(x, y, bw, bh);
    const insideCount = state.doc.instances.filter(it =>
      !(it.bbox_xyxy[2] < box[0] || it.bbox_xyxy[0] > box[2] || it.bbox_xyxy[3] < box[1] || it.bbox_xyxy[1] > box[3])
    ).length;
    drawBadge(ctx, x + 6, Math.max(4, y - 22), `Area select: ${insideCount} symbol${insideCount === 1 ? "" : "s"}`);
    ctx.restore();
  } else if (state.drag?.type === "draw") {
    const box = normalizedBox(state.drag.start, state.drag.current,
                              state.doc.image_width, state.doc.image_height);
    const [x, y] = imageToScreen(box[0], box[1], viewport());
    ctx.strokeStyle = "#33a7ff"; ctx.lineWidth = 2; ctx.setLineDash([5, 3]);
    ctx.strokeRect(x, y, (box[2] - box[0]) * state.scale, (box[3] - box[1]) * state.scale);
    ctx.setLineDash([]);
    drawBadge(ctx, x, Math.max(4, y - 24), `${(box[2] - box[0]).toFixed(1)} × ${(box[3] - box[1]).toFixed(1)} px`);
  } else if (state.drag?.type === "oriented") {
    const rot = computeOrientedFromDrag(state.drag.start, state.drag.current);
    const corners = rotatedCorners(rot).map(pt => imageToScreen(pt[0], pt[1], viewport()));
    ctx.save();
    ctx.beginPath();
    ctx.moveTo(corners[0][0], corners[0][1]);
    for (let i = 1; i < 4; i++) ctx.lineTo(corners[i][0], corners[i][1]);
    ctx.closePath();
    ctx.fillStyle = "rgba(103, 198, 255, 0.16)";
    ctx.fill();
    ctx.strokeStyle = "#33a7ff";
    ctx.lineWidth = 2;
    ctx.setLineDash([5, 3]);
    ctx.stroke();
    ctx.restore();
    drawBadge(ctx, corners[1][0] + 8, corners[1][1] - 10,
              `${rot.w.toFixed(1)} × ${rot.h.toFixed(1)} px · ${rot.angle_deg.toFixed(1)}°`);
  } else if (state.drag?.type === "oriented-axis") {
    const [x0, y0] = imageToScreen(state.drag.start[0], state.drag.start[1], viewport());
    const [x1, y1] = imageToScreen(state.drag.current[0], state.drag.current[1], viewport());
    drawArrow(ctx, x0, y0, x1, y1, "#67c6ff", 2);
    const ang = Math.atan2(state.drag.current[1] - state.drag.start[1],
                           state.drag.current[0] - state.drag.start[0]) * 180 / Math.PI;
    const len = Math.hypot(state.drag.current[0] - state.drag.start[0],
                           state.drag.current[1] - state.drag.start[1]);
    drawBadge(ctx, x1 + 10, y1 - 10, `${len.toFixed(1)} px · ${ang.toFixed(1)}°`);
  } else if (state.orientedDraft && state.pointerImg) {
    const rot = buildOrientedFromDraft(state.orientedDraft, state.pointerImg);
    const corners = rotatedCorners(rot).map(pt => imageToScreen(pt[0], pt[1], viewport()));
    ctx.save();
    ctx.beginPath();
    ctx.moveTo(corners[0][0], corners[0][1]);
    for (let i = 1; i < 4; i++) ctx.lineTo(corners[i][0], corners[i][1]);
    ctx.closePath();
    ctx.fillStyle = "rgba(103, 198, 255, 0.14)";
    ctx.fill();
    ctx.strokeStyle = "#67c6ff";
    ctx.lineWidth = 2;
    ctx.setLineDash([5, 3]);
    ctx.stroke();
    ctx.restore();
    drawBadge(ctx, corners[1][0] + 8, corners[1][1] - 10,
              `${rot.w.toFixed(1)} × ${rot.h.toFixed(1)} px · ${rot.angle_deg.toFixed(1)}°`);
  } else if (state.drag?.type === "snap") {
    const [x0, y0] = imageToScreen(state.drag.start[0], state.drag.start[1], viewport());
    const [x1, y1] = imageToScreen(state.drag.current[0], state.drag.current[1], viewport());
    drawArrow(ctx, x0, y0, x1, y1, "#ff7eb6", 2.4);
    const ang = Math.atan2(state.drag.current[1] - state.drag.start[1],
                           state.drag.current[0] - state.drag.start[0]) * 180 / Math.PI;
    drawBadge(ctx, x1 + 10, y1 - 10, `snap vec ${ang.toFixed(1)}°`);
  }

  if (state.pointerImg && (state.mode === "draw" || state.mode === "oriented" ||
                           state.mode === "anchor" || state.mode === "snap")) {
    const [px, py] = imageToScreen(state.pointerImg[0], state.pointerImg[1], viewport());
    ctx.save();
    ctx.strokeStyle = "rgba(86, 180, 219, 0.35)";
    ctx.lineWidth = 1;
    ctx.setLineDash([4, 4]);
    ctx.beginPath();
    ctx.moveTo(px, 0); ctx.lineTo(px, h);
    ctx.moveTo(0, py); ctx.lineTo(w, py);
    ctx.stroke();
    ctx.restore();
  }

  if (state.mode === "anchor") {
    const item = anchorTargetItem();
    if (item) {
      const [hx0, hy0] = imageToScreen(item.bbox_xyxy[0], item.bbox_xyxy[1], viewport());
      const [hx1, hy1] = imageToScreen(item.bbox_xyxy[2], item.bbox_xyxy[3], viewport());
      ctx.save();
      ctx.strokeStyle = "rgba(255, 159, 67, 0.9)";
      ctx.lineWidth = 2;
      ctx.setLineDash([6, 3]);
      ctx.strokeRect(hx0 - 3, hy0 - 3, hx1 - hx0 + 6, hy1 - hy0 + 6);
      ctx.restore();
      const boxPts = getBoxSnapPoints(item);
      ctx.save();
      for (const bp of boxPts) {
        const [sx, sy] = imageToScreen(bp.xy[0], bp.xy[1], viewport());
        ctx.strokeStyle = "rgba(56, 232, 255, 0.7)";
        ctx.fillStyle = "rgba(56, 232, 255, 0.25)";
        ctx.lineWidth = 1.4;
        ctx.beginPath();
        ctx.arc(sx, sy, 4.5, 0, Math.PI * 2);
        ctx.fill();
        ctx.stroke();
      }
      ctx.restore();
    }
    if (state.activeAnchorSnap) {
      if (state.activeAnchorSnap.rays && state.activeAnchorSnap.rays.length) {
        ctx.save();
        ctx.strokeStyle = "rgba(255, 159, 67, 0.3)";
        ctx.lineWidth = 1;
        for (const [r0, r1] of state.activeAnchorSnap.rays) {
          const [s0x, s0y] = imageToScreen(r0[0], r0[1], viewport());
          const [s1x, s1y] = imageToScreen(r1[0], r1[1], viewport());
          ctx.beginPath();
          ctx.moveTo(s0x, s0y);
          ctx.lineTo(s1x, s1y);
          ctx.stroke();
        }
        ctx.restore();
      }
      const [sx, sy] = imageToScreen(state.activeAnchorSnap.xy[0], state.activeAnchorSnap.xy[1], viewport());
      const [cx, cy] = state.pointerScreen || [sx, sy];
      ctx.save();
      ctx.strokeStyle = "rgba(255, 159, 67, 0.85)";
      ctx.lineWidth = 1.5;
      ctx.setLineDash([3, 3]);
      ctx.beginPath();
      ctx.moveTo(cx, cy);
      ctx.lineTo(sx, sy);
      ctx.stroke();
      ctx.setLineDash([]);

      ctx.strokeStyle = "#ff9f43";
      ctx.fillStyle = "rgba(255, 159, 67, 0.35)";
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(sx, sy, 7.5, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();

      ctx.beginPath();
      ctx.moveTo(sx - 4.5, sy); ctx.lineTo(sx, sy - 4.5);
      ctx.lineTo(sx + 4.5, sy); ctx.lineTo(sx, sy + 4.5);
      ctx.closePath();
      ctx.stroke();
      ctx.restore();

      drawBadge(ctx, sx + 10, sy - 10, `Snap: ${state.activeAnchorSnap.label}`);
    }
  }

  drawLoupe(ctx, w, h);
  $("zoomLabel").textContent = Math.round(state.scale * 100) + "%";
}

function buildOrientedFromDraft(draft, cursorPt) {
  const { p1, p2, angleDeg, w } = draft;
  const cx0 = (p1[0] + p2[0]) / 2;
  const cy0 = (p1[1] + p2[1]) / 2;
  const a = angleDeg * Math.PI / 180;
  // Unit normal perpendicular to baseline
  const nx = -Math.sin(a), ny = Math.cos(a);
  const signedDist = (cursorPt[0] - cx0) * nx + (cursorPt[1] - cy0) * ny;
  const h = Math.max(4, Math.abs(signedDist));
  // Displace center along normal so baseline (p1, p2) remains stationary as one edge of the box
  const shift = signedDist / 2;
  const cx = cx0 + nx * shift;
  const cy = cy0 + ny * shift;
  return { cx, cy, w: Math.max(4, w), h, angle_deg: angleDeg };
}

function snapAngleToWallsOrCardinals(rawAng, startPt) {
  if (state.walls && state.walls.length) {
    let bestDiff = 22;
    let bestSnap = null;
    for (const w of state.walls) {
      const sx = w.start[0], sy = w.start[1];
      const ex = w.end[0], ey = w.end[1];
      const wx = ex - sx, wy = ey - sy;
      const wlen2 = wx * wx + wy * wy;
      if (wlen2 < 1e-6) continue;
      const u = clamp(((startPt[0] - sx) * wx + (startPt[1] - sy) * wy) / wlen2, 0, 1);
      const px = sx + u * wx, py = sy + u * wy;
      const dist = Math.hypot(startPt[0] - px, startPt[1] - py);
      if (dist <= 80) {
        const wallAng = Math.atan2(wy, wx) * 180 / Math.PI;
        for (const offset of [0, 90, 180, -90]) {
          const candidate = ((wallAng + offset + 180) % 360 + 360) % 360 - 180;
          const diff = Math.abs(((rawAng - candidate + 180) % 360 + 360) % 360 - 180);
          if (diff < bestDiff) {
            bestDiff = diff;
            bestSnap = candidate;
          }
        }
      }
    }
    if (bestSnap !== null) return Math.round(bestSnap * 10) / 10;
  }
  for (const card of [0, 45, 90, 135, 180, -45, -90, -135]) {
    const diff = Math.abs(((rawAng - card + 180) % 360 + 360) % 360 - 180);
    if (diff <= 10) return card;
  }
  return rawAng;
}

function findNearestWallAngle(pt, maxDist = 80) {
  if (!state.walls || !state.walls.length) return null;
  let minDist = maxDist;
  let nearestAng = null;
  for (let i = 0; i < state.walls.length; i++) {
    const w = state.walls[i];
    const sx = w.start[0], sy = w.start[1], ex = w.end[0], ey = w.end[1];
    const wx = ex - sx, wy = ey - sy;
    const wlen2 = wx * wx + wy * wy;
    if (wlen2 < 1e-6) continue;
    const u = clamp(((pt[0] - sx) * wx + (pt[1] - sy) * wy) / wlen2, 0, 1);
    const px = sx + u * wx, py = sy + u * wy;
    const d = Math.hypot(pt[0] - px, pt[1] - py);
    if (d < minDist) {
      minDist = d;
      nearestAng = Math.atan2(wy, wx) * 180 / Math.PI;
    }
  }
  return nearestAng;
}

function computeOrientedFromDrag(p0, p1) {
  const dx = p1[0] - p0[0];
  const dy = p1[1] - p0[1];
  const dist = Math.hypot(dx, dy);

  if (dist < 3) {
    const wallAng = findNearestWallAngle(p0, 80);
    const angle_deg = wallAng !== null ? Math.round(wallAng * 10) / 10 : 0;
    return {
      cx: p0[0],
      cy: p0[1],
      w: 28,
      h: 28,
      angle_deg,
    };
  }

  const rawAng = Math.atan2(dy, dx) * 180 / Math.PI;
  const snappedAng = snapAngleToWallsOrCardinals(rawAng, p0);
  const rad = snappedAng * Math.PI / 180;
  const ux = Math.cos(rad), uy = Math.sin(rad);
  const nx = -Math.sin(rad), ny = Math.cos(rad);

  const projU = dx * ux + dy * uy;
  const projN = dx * nx + dy * ny;

  const w = Math.max(4, Math.abs(projU));
  const h = Math.max(4, Math.abs(projN));

  const cx = p0[0] + ux * (projU / 2) + nx * (projN / 2);
  const cy = p0[1] + uy * (projU / 2) + ny * (projN / 2);

  return {
    cx: Math.round(cx * 1000) / 1000,
    cy: Math.round(cy * 1000) / 1000,
    w: Math.round(w * 1000) / 1000,
    h: Math.round(h * 1000) / 1000,
    angle_deg: Math.round(snappedAng * 1000) / 1000,
  };
}

let edgeSnapCanvas = null;
let edgeSnapCtx = null;
function getEdgeSnapCtx() {
  if (!edgeSnapCanvas) {
    edgeSnapCanvas = document.createElement("canvas");
    edgeSnapCtx = edgeSnapCanvas.getContext("2d", { willReadFrequently: true });
  }
  return edgeSnapCtx;
}

function getSnapThreshold(maxContrast, sensitivity = state.snapSensitivity) {
  if (sensitivity === "max") {
    return Math.max(6, Math.min(16, Math.round(maxContrast * 0.15)));
  } else if (sensitivity === "normal") {
    return Math.max(16, Math.min(30, Math.round(maxContrast * 0.32)));
  } else {
    // "high" (default)
    return Math.max(10, Math.min(22, Math.round(maxContrast * 0.22)));
  }
}

function setSnapSensitivity(val) {
  state.snapSensitivity = val;
  const sel = $("snapSensitivity");
  if (sel) sel.value = val;
  const labels = {
    normal: "Normal (standard contrast)",
    high: "High (ceiling grid & faint lines)",
    max: "Max Edge (ultra-sensitive outer contour)",
  };
  notify(`Snap sensitivity: ${labels[val] || val}`, "info");
}

function cycleSnapSensitivity() {
  const current = state.snapSensitivity || "high";
  const order = ["normal", "high", "max"];
  const next = order[(order.indexOf(current) + 1) % order.length];
  setSnapSensitivity(next);
}

function snapBoxToContrastEdges(box, rotated = null) {
  try {
    if (!state.image || !state.doc) return { box, rotated };
    const [x0, y0, x1, y1] = box;
    const w = x1 - x0, h = y1 - y0;
    if (w < 6 || h < 6) return { box, rotated };

    const ctx = getEdgeSnapCtx();
    const density = (state.snapSensitivity === "max") ? 2.5 : (state.snapSensitivity === "high" ? 2.0 : 1.5);

    if (!rotated || Math.abs(rotated.angle_deg || 0) < 1e-4) {
      // Axis-aligned box: spawn vectors from the 4 outer sides of the drawn box
      // and march inward toward the center to shrink in all directions. Never expand.
      const sx0 = Math.max(0, Math.floor(x0));
      const sy0 = Math.max(0, Math.floor(y0));
      const sx1 = Math.min(state.doc.image_width, Math.ceil(x1));
      const sy1 = Math.min(state.doc.image_height, Math.ceil(y1));
      const sw = sx1 - sx0, sh = sy1 - sy0;
      if (sw < 6 || sh < 6) return { box, rotated };

      edgeSnapCanvas.width = sw;
      edgeSnapCanvas.height = sh;
      ctx.drawImage(state.image, sx0, sy0, sw, sh, 0, 0, sw, sh);
      const imgData = ctx.getImageData(0, 0, sw, sh);
      const d = imgData.data;

      // Sample border pixels to estimate local background luminance
      let borderLSum = 0, borderCount = 0;
      for (let x = 0; x < sw; x += 2) {
        const iTop = x * 4, iBot = ((sh - 1) * sw + x) * 4;
        borderLSum += 0.299 * d[iTop] + 0.587 * d[iTop + 1] + 0.114 * d[iTop + 2];
        borderLSum += 0.299 * d[iBot] + 0.587 * d[iBot + 1] + 0.114 * d[iBot + 2];
        borderCount += 2;
      }
      for (let y = 0; y < sh; y += 2) {
        const iLeft = y * sw * 4, iRight = (y * sw + (sw - 1)) * 4;
        borderLSum += 0.299 * d[iLeft] + 0.587 * d[iLeft + 1] + 0.114 * d[iLeft + 2];
        borderLSum += 0.299 * d[iRight] + 0.587 * d[iRight + 1] + 0.114 * d[iRight + 2];
        borderCount += 2;
      }
      const bgL = borderCount ? borderLSum / borderCount : 255;
      const isLightBg = bgL >= 128;

      let maxContrast = 0;
      for (let y = 0; y < sh; y += 2) {
        const row = y * sw * 4;
        for (let x = 0; x < sw; x += 2) {
          const idx = row + x * 4;
          const lum = 0.299 * d[idx] + 0.587 * d[idx + 1] + 0.114 * d[idx + 2];
          const c = isLightBg ? (bgL - lum) : (lum - bgL);
          if (c > maxContrast) maxContrast = c;
        }
      }

      const contrastThresh = getSnapThreshold(maxContrast, state.snapSensitivity);
      const fallbackThresh = Math.max(6, Math.round(contrastThresh * 0.65));

      function isInkAt(px, py, thresh = contrastThresh) {
        if (px < 0 || px >= sw || py < 0 || py >= sh) return false;
        const idx = (py * sw + px) * 4;
        const lum = 0.299 * d[idx] + 0.587 * d[idx + 1] + 0.114 * d[idx + 2];
        const contrast = isLightBg ? (bgL - lum) : (lum - bgL);
        return contrast >= thresh;
      }

      // Ray counts are strictly dependent on each side's pixel size
      const numRaysX = Math.max(32, Math.min(240, Math.round(sw * density)));
      const numRaysY = Math.max(32, Math.min(240, Math.round(sh * density)));
      const maxTravelX = Math.floor(Math.min(sw * 0.46, (sw - 4) / 2));
      const maxTravelY = Math.floor(Math.min(sh * 0.46, (sh - 4) / 2));

      function computeSideHits(numRays, maxTravel, testCoord) {
        let hits = [];
        for (let i = 0; i < numRays; i++) {
          const t = (numRays === 1) ? 0.5 : (0.01 + 0.98 * (i / (numRays - 1)));
          for (let dist = 0; dist <= maxTravel; dist++) {
            if (testCoord(t, dist, contrastThresh)) { hits.push(dist); break; }
          }
        }
        const minExpected = Math.max(2, Math.min(6, Math.round(numRays * 0.04)));
        if (hits.length < minExpected && fallbackThresh < contrastThresh) {
          const retryHits = [];
          for (let i = 0; i < numRays; i++) {
            const t = (numRays === 1) ? 0.5 : (0.01 + 0.98 * (i / (numRays - 1)));
            for (let dist = 0; dist <= maxTravel; dist++) {
              if (testCoord(t, dist, fallbackThresh)) { retryHits.push(dist); break; }
            }
          }
          if (retryHits.length >= minExpected) hits = retryHits;
        }
        return hits;
      }

      function computeSideShrink(hits, numRays) {
        const minExpected = Math.max(2, Math.min(6, Math.round(numRays * 0.04)));
        if (hits.length < minExpected) return 0;
        hits.sort((a, b) => a - b);
        const clusterSize = Math.max(2, Math.min(5, Math.round(numRays * 0.035)));
        for (let i = 0; i <= hits.length - clusterSize; i++) {
          if (hits[i + clusterSize - 1] - hits[i] <= 2.2) {
            return hits[i];
          }
        }
        const idx = Math.min(Math.floor(hits.length * 0.05), hits.length - 1);
        return hits[idx];
      }

      // Left side: vectors from x = 0 marching rightward
      const hitsLeft = computeSideHits(numRaysY, maxTravelX, (t, dist, thresh) => {
        const py = Math.floor(t * (sh - 1));
        return isInkAt(dist, py, thresh);
      });

      // Right side: vectors from x = sw - 1 marching leftward
      const hitsRight = computeSideHits(numRaysY, maxTravelX, (t, dist, thresh) => {
        const py = Math.floor(t * (sh - 1));
        return isInkAt(sw - 1 - dist, py, thresh);
      });

      // Top side: vectors from y = 0 marching downward
      const hitsTop = computeSideHits(numRaysX, maxTravelY, (t, dist, thresh) => {
        const px = Math.floor(t * (sw - 1));
        return isInkAt(px, dist, thresh);
      });

      // Bottom side: vectors from y = sh - 1 marching upward
      const hitsBottom = computeSideHits(numRaysX, maxTravelY, (t, dist, thresh) => {
        const px = Math.floor(t * (sw - 1));
        return isInkAt(px, sh - 1 - dist, thresh);
      });

      const sLeft = computeSideShrink(hitsLeft, numRaysY);
      const sRight = computeSideShrink(hitsRight, numRaysY);
      const sTop = computeSideShrink(hitsTop, numRaysX);
      const sBottom = computeSideShrink(hitsBottom, numRaysX);

      const newX0 = x0 + sLeft;
      const newX1 = x1 - sRight;
      const newY0 = y0 + sTop;
      const newY1 = y1 - sBottom;

      if (newX1 - newX0 >= 4 && newY1 - newY0 >= 4) {
        return {
          box: [
            Math.round(newX0 * 10) / 10,
            Math.round(newY0 * 10) / 10,
            Math.round(newX1 * 10) / 10,
            Math.round(newY1 * 10) / 10,
          ],
          rotated: null,
        };
      }
      return { box, rotated };
    }

    // Rotated box: spawn vectors from -U, +U, -V, +V sides of the drawn box
    // and march inward toward the center to shrink in all directions. Never expand.
    const { cx, cy, angle_deg } = rotated;
    const rad = angle_deg * Math.PI / 180;
    const cosA = Math.cos(rad), sinA = Math.sin(rad);

    const radius = Math.ceil(Math.hypot(rotated.w, rotated.h) / 2) + 2;
    const sx0 = Math.max(0, Math.floor(cx - radius));
    const sy0 = Math.max(0, Math.floor(cy - radius));
    const sx1 = Math.min(state.doc.image_width, Math.ceil(cx + radius));
    const sy1 = Math.min(state.doc.image_height, Math.ceil(cy + radius));
    const sw = sx1 - sx0, sh = sy1 - sy0;
    if (sw < 6 || sh < 6) return { box, rotated };

    edgeSnapCanvas.width = sw;
    edgeSnapCanvas.height = sh;
    ctx.drawImage(state.image, sx0, sy0, sw, sh, 0, 0, sw, sh);
    const imgData = ctx.getImageData(0, 0, sw, sh);
    const d = imgData.data;

    let borderLSum = 0, borderCount = 0;
    for (let x = 0; x < sw; x += 2) {
      const t = x * 4, b = ((sh - 1) * sw + x) * 4;
      borderLSum += 0.299 * d[t] + 0.587 * d[t + 1] + 0.114 * d[t + 2];
      borderLSum += 0.299 * d[b] + 0.587 * d[b + 1] + 0.114 * d[b + 2];
      borderCount += 2;
    }
    for (let y = 0; y < sh; y += 2) {
      const l = y * sw * 4, r = (y * sw + (sw - 1)) * 4;
      borderLSum += 0.299 * d[l] + 0.587 * d[l + 1] + 0.114 * d[l + 2];
      borderLSum += 0.299 * d[r] + 0.587 * d[r + 1] + 0.114 * d[r + 2];
      borderCount += 2;
    }
    const bgL = borderCount ? borderLSum / borderCount : 255;
    const isLightBg = bgL >= 128;

    let maxContrast = 0;
    for (let y = 0; y < sh; y += 2) {
      const row = y * sw * 4;
      for (let x = 0; x < sw; x += 2) {
        const idx = row + x * 4;
        const lum = 0.299 * d[idx] + 0.587 * d[idx + 1] + 0.114 * d[idx + 2];
        const c = isLightBg ? (bgL - lum) : (lum - bgL);
        if (c > maxContrast) maxContrast = c;
      }
    }
    const contrastThresh = getSnapThreshold(maxContrast, state.snapSensitivity);
    const fallbackThresh = Math.max(6, Math.round(contrastThresh * 0.65));

    function isInkLocal(u, v, thresh = contrastThresh) {
      const imgX = cx + u * cosA - v * sinA;
      const imgY = cy + u * sinA + v * cosA;
      const px = Math.round(imgX - sx0);
      const py = Math.round(imgY - sy0);
      if (px < 0 || px >= sw || py < 0 || py >= sh) return false;
      const idx = (py * sw + px) * 4;
      const lum = 0.299 * d[idx] + 0.587 * d[idx + 1] + 0.114 * d[idx + 2];
      const contrast = isLightBg ? (bgL - lum) : (lum - bgL);
      return contrast >= thresh;
    }

    const rotW = rotated.w;
    const rotH = rotated.h;
    const numRaysU = Math.max(32, Math.min(240, Math.round(rotW * density)));
    const numRaysV = Math.max(32, Math.min(240, Math.round(rotH * density)));
    const maxTravelU = Math.floor(Math.min(rotW * 0.46, (rotW - 4) / 2));
    const maxTravelV = Math.floor(Math.min(rotH * 0.46, (rotH - 4) / 2));

    function computeSideHitsRot(numRays, maxTravel, testCoord) {
      let hits = [];
      for (let i = 0; i < numRays; i++) {
        const t = (numRays === 1) ? 0.5 : (0.01 + 0.98 * (i / (numRays - 1)));
        for (let dist = 0; dist <= maxTravel; dist++) {
          if (testCoord(t, dist, contrastThresh)) { hits.push(dist); break; }
        }
      }
      const minExpected = Math.max(2, Math.min(6, Math.round(numRays * 0.04)));
      if (hits.length < minExpected && fallbackThresh < contrastThresh) {
        const retryHits = [];
        for (let i = 0; i < numRays; i++) {
          const t = (numRays === 1) ? 0.5 : (0.01 + 0.98 * (i / (numRays - 1)));
          for (let dist = 0; dist <= maxTravel; dist++) {
            if (testCoord(t, dist, fallbackThresh)) { retryHits.push(dist); break; }
          }
        }
        if (retryHits.length >= minExpected) hits = retryHits;
      }
      return hits;
    }

    function computeSideShrinkRot(hits, numRays) {
      const minExpected = Math.max(2, Math.min(6, Math.round(numRays * 0.04)));
      if (hits.length < minExpected) return 0;
      hits.sort((a, b) => a - b);
      const clusterSize = Math.max(2, Math.min(5, Math.round(numRays * 0.035)));
      for (let i = 0; i <= hits.length - clusterSize; i++) {
        if (hits[i + clusterSize - 1] - hits[i] <= 2.2) {
          return hits[i];
        }
      }
      const idx = Math.min(Math.floor(hits.length * 0.05), hits.length - 1);
      return hits[idx];
    }

    // -U side: rays starting at u = -rotW/2 marching along +u (spanning rotH -> numRaysV)
    const hitsMinU = computeSideHitsRot(numRaysV, maxTravelU, (t, dist, thresh) => {
      const v = -rotH / 2 + t * rotH;
      return isInkLocal(-rotW / 2 + dist, v, thresh);
    });

    // +U side: rays starting at u = +rotW/2 marching along -u (spanning rotH -> numRaysV)
    const hitsMaxU = computeSideHitsRot(numRaysV, maxTravelU, (t, dist, thresh) => {
      const v = -rotH / 2 + t * rotH;
      return isInkLocal(rotW / 2 - dist, v, thresh);
    });

    // -V side: rays starting at v = -rotH/2 marching along +v (spanning rotW -> numRaysU)
    const hitsMinV = computeSideHitsRot(numRaysU, maxTravelV, (t, dist, thresh) => {
      const u = -rotW / 2 + t * rotW;
      return isInkLocal(u, -rotH / 2 + dist, thresh);
    });

    // +V side: rays starting at v = +rotH/2 marching along -v (spanning rotW -> numRaysU)
    const hitsMaxV = computeSideHitsRot(numRaysU, maxTravelV, (t, dist, thresh) => {
      const u = -rotW / 2 + t * rotW;
      return isInkLocal(u, rotH / 2 - dist, thresh);
    });

    const sMinU = computeSideShrinkRot(hitsMinU, numRaysV);
    const sMaxU = computeSideShrinkRot(hitsMaxU, numRaysV);
    const sMinV = computeSideShrinkRot(hitsMinV, numRaysU);
    const sMaxV = computeSideShrinkRot(hitsMaxV, numRaysU);

    const newRotW = rotW - (sMinU + sMaxU);
    const newRotH = rotH - (sMinV + sMaxV);

    if (newRotW >= 4 && newRotH >= 4) {
      const deltaU = (sMinU - sMaxU) / 2;
      const deltaV = (sMinV - sMaxV) / 2;
      const newCx = clamp(cx + deltaU * cosA - deltaV * sinA, 0, state.doc.image_width);
      const newCy = clamp(cy + deltaU * sinA + deltaV * cosA, 0, state.doc.image_height);
      const nextRot = {
        cx: Math.round(newCx * 10) / 10,
        cy: Math.round(newCy * 10) / 10,
        w: Math.round(newRotW * 10) / 10,
        h: Math.round(newRotH * 10) / 10,
        angle_deg: rotated.angle_deg,
      };
      const aabb = rotatedAabb(nextRot.cx, nextRot.cy, nextRot.w, nextRot.h, nextRot.angle_deg);
      return { box: aabb.map(v => Math.round(v * 10) / 10), rotated: nextRot };
    }
    return { box, rotated };
  } catch (err) {
    console.warn("Contrast edge snap error:", err);
    return { box, rotated };
  }
}

function findFeatureTipInArea(probePt, item, searchRadius = 24) {
  if (!state.image || !item || !state.doc) return null;
  const ctr = itemCenter(item);
  let px, py, useOmni = false;
  if (!probePt) {
    useOmni = true;
    px = ctr[0];
    py = ctr[1];
  } else {
    px = probePt[0];
    py = probePt[1];
  }

  const dx = px - ctr[0], dy = py - ctr[1];
  const distCtr = Math.hypot(dx, dy);
  if (distCtr < 3 && !probePt) {
    useOmni = true;
  }
  const probeAngle = distCtr > 1e-4 ? Math.atan2(dy, dx) : 0;
  const dirX = distCtr > 1e-4 ? dx / distCtr : 0;
  const dirY = distCtr > 1e-4 ? dy / distCtr : -1;

  const bb = item.bbox_xyxy;
  const margin = 6;
  const sx0 = Math.max(0, Math.floor(Math.max(bb[0] - margin, useOmni ? bb[0] - margin : px - searchRadius)));
  const sy0 = Math.max(0, Math.floor(Math.max(bb[1] - margin, useOmni ? bb[1] - margin : py - searchRadius)));
  const sx1 = Math.min(state.doc.image_width, Math.ceil(Math.min(bb[2] + margin, useOmni ? bb[2] + margin : px + searchRadius + 1)));
  const sy1 = Math.min(state.doc.image_height, Math.ceil(Math.min(bb[3] + margin, useOmni ? bb[3] + margin : py + searchRadius + 1)));
  const sw = sx1 - sx0, sh = sy1 - sy0;
  if (sw < 3 || sh < 3) return null;

  const ctx = getEdgeSnapCtx();
  edgeSnapCanvas.width = sw;
  edgeSnapCanvas.height = sh;
  ctx.drawImage(state.image, sx0, sy0, sw, sh, 0, 0, sw, sh);
  const imgData = ctx.getImageData(0, 0, sw, sh);
  const d = imgData.data;

  let borderLSum = 0, borderCount = 0;
  for (let x = 0; x < sw; x += 2) {
    const t = x * 4, b = ((sh - 1) * sw + x) * 4;
    borderLSum += 0.299 * d[t] + 0.587 * d[t + 1] + 0.114 * d[t + 2];
    borderLSum += 0.299 * d[b] + 0.587 * d[b + 1] + 0.114 * d[b + 2];
    borderCount += 2;
  }
  for (let y = 0; y < sh; y += 2) {
    const l = y * sw * 4, r = (y * sw + (sw - 1)) * 4;
    borderLSum += 0.299 * d[l] + 0.587 * d[l + 1] + 0.114 * d[l + 2];
    borderLSum += 0.299 * d[r] + 0.587 * d[r + 1] + 0.114 * d[r + 2];
    borderCount += 2;
  }
  const bgL = borderCount ? borderLSum / borderCount : 255;
  const isLightBg = bgL >= 128;

  let maxContrast = 0;
  for (let y = 0; y < sh; y += 2) {
    const row = y * sw * 4;
    for (let x = 0; x < sw; x += 2) {
      const idx = row + x * 4;
      const lum = 0.299 * d[idx] + 0.587 * d[idx + 1] + 0.114 * d[idx + 2];
      const c = isLightBg ? (bgL - lum) : (lum - bgL);
      if (c > maxContrast) maxContrast = c;
    }
  }
  const contrastThresh = getSnapThreshold(maxContrast, state.snapSensitivity);

  function isInk(ix, iy) {
    const lx = Math.round(ix - sx0);
    const ly = Math.round(iy - sy0);
    if (lx < 0 || lx >= sw || ly < 0 || ly >= sh) return false;
    const idx = (ly * sw + lx) * 4;
    const lum = 0.299 * d[idx] + 0.587 * d[idx + 1] + 0.114 * d[idx + 2];
    const contrast = isLightBg ? (bgL - lum) : (lum - bgL);
    return contrast >= contrastThresh;
  }

  // Cast rays scaled with feature size/radius
  const featureSpan = useOmni ? Math.hypot(bb[2] - bb[0], bb[3] - bb[1]) : searchRadius * 2;
  const density = (state.snapSensitivity === "max") ? 3.5 : (state.snapSensitivity === "high" ? 2.8 : 2.0);
  const numRays = useOmni
    ? Math.max(90, Math.min(260, Math.round(featureSpan * density)))
    : Math.max(50, Math.min(180, Math.round(featureSpan * density)));

  const arcSpan = useOmni ? Math.PI * 2 : (Math.PI * 0.75);
  const startAngle = useOmni ? 0 : (probeAngle - arcSpan / 2);
  const angleStep = arcSpan / (numRays - 1 || 1);

  const rays = [];
  const candidateTips = [];
  let maxProj = -999999;
  const maxRayLen = useOmni ? Math.hypot(bb[2] - bb[0], bb[3] - bb[1]) * 0.85 : (distCtr + searchRadius + 6);
  const minRayLen = useOmni ? 2 : Math.max(2, distCtr - searchRadius);

  for (let i = 0; i < numRays; i++) {
    const ang = startAngle + i * angleStep;
    const cosA = Math.cos(ang), sinA = Math.sin(ang);
    const rayStart = [ctr[0] + cosA * minRayLen, ctr[1] + sinA * minRayLen];
    let outermostInk = null;

    for (let r = minRayLen; r <= maxRayLen; r += 0.5) {
      const rx = ctr[0] + cosA * r;
      const ry = ctr[1] + sinA * r;
      if (rx < sx0 || rx >= sx1 || ry < sy0 || ry >= sy1) break;
      if (isInk(rx, ry)) {
        outermostInk = [rx, ry];
      }
    }

    const rayEnd = outermostInk || [ctr[0] + cosA * maxRayLen, ctr[1] + sinA * maxRayLen];
    rays.push([rayStart, rayEnd]);

    if (outermostInk) {
      const proj = useOmni ? Math.hypot(outermostInk[0] - ctr[0], outermostInk[1] - ctr[1])
                           : (outermostInk[0] - ctr[0]) * dirX + (outermostInk[1] - ctr[1]) * dirY;
      if (proj > maxProj + 0.01) {
        maxProj = proj;
        candidateTips.length = 0;
        candidateTips.push(outermostInk);
      } else if (Math.abs(proj - maxProj) <= 0.75) {
        candidateTips.push(outermostInk);
      }
    }
  }

  if (!candidateTips.length) {
    return null;
  }

  let avgX = 0, avgY = 0;
  for (const pt of candidateTips) {
    avgX += pt[0];
    avgY += pt[1];
  }
  avgX /= candidateTips.length;
  avgY /= candidateTips.length;

  const res = [Math.round(avgX * 10) / 10, Math.round(avgY * 10) / 10];
  res.xy = [res[0], res[1]];
  res.rays = rays;
  res.label = "Feature Tip";
  return res;
}

function snapContrastEdgesSelected() {
  const targets = chosenList();
  if (!targets.length) {
    notify("Select one or more boxes first to snap to symbol edges.", "warning");
    return;
  }
  flushPendingInputs();
  remember();
  let snappedCount = 0;
  for (const item of targets) {
    const before = reviewEvidenceKey(item);
    const res = snapBoxToContrastEdges(item.bbox_xyxy, item.bbox_rotated);
    if (checkBox(res.box)) {
      item.bbox_xyxy = res.box;
      if (res.rotated) {
        item.bbox_rotated = res.rotated;
      } else if (item.bbox_rotated) {
        item.bbox_rotated.w = res.box[2] - res.box[0];
        item.bbox_rotated.h = res.box[3] - res.box[1];
        item.bbox_rotated.cx = (res.box[0] + res.box[2]) / 2;
        item.bbox_rotated.cy = (res.box[1] + res.box[3]) / 2;
      }
      invalidateReviewOnChange(item, before);
      snappedCount++;
    }
  }
  editState();
  refresh();
  const sensLabel = { normal: "Normal", high: "High", max: "Max Edge" }[state.snapSensitivity] || state.snapSensitivity;
  notify(`Snapped ${snappedCount} box${snappedCount > 1 ? "es" : ""} to symbol contrast edges [${sensLabel}].`, "saved");
}

function refreshControls() {
  if ($("snapSensitivity")) $("snapSensitivity").value = state.snapSensitivity || "high";
  if ($("currentDocBadge")) {
    $("currentDocBadge").textContent = state.currentDocName || "Document";
    $("currentDocBadge").title = state.currentDocPath || "";
  }
  $("pageIndicator").textContent = (state.pageCount === 1 && state.doc?.page_index > 0)
    ? "Source p" + (state.doc.page_index + 1)
    : "Page " + (state.page + 1) + " / " + state.pageCount;
  const navBase = state.pendingPage ?? state.loadingPage ?? state.page;
  $("prevPage").disabled = navBase <= 0;
  $("nextPage").disabled = navBase >= state.pageCount - 1;
  const jump = $("pageJump");
  if (jump) {
    jump.max = String(state.pageCount);
    jump.placeholder = state.pageCount > 1 ? `Go to (1-${state.pageCount})` : "Go to";
    jump.disabled = state.pageCount <= 1;
  }
  for (const name of ["pan", "select", "draw", "oriented", "anchor", "snap"]) {
    const el = $(name + "Tool");
    if (el) el.setAttribute("aria-pressed", String(state.mode === name));
  }
  $("undo").disabled = state.undo.length === 0;
  $("redo").disabled = state.redo.length === 0;
  $("save").disabled = (!state.dirty && state.revision !== null) || state.busy;
  const evalBtn = $("evaluatePageBtn");
  const evalConf = $("evalConfidence");
  if (evalBtn) {
    const det = state.detectorInfo;
    const avail = Boolean(det?.available);
    evalBtn.disabled = !avail || !state.doc || state.busy;
    if (evalConf) evalConf.disabled = !avail || !state.doc || state.busy;
    if (!avail) {
      evalBtn.title = "No trained model found under work/models/latest (run remote/pull_best_model.sh)";
    } else {
      const epochTxt = det.epoch != null ? ` (epoch ${det.epoch})` : "";
      const fitTxt = typeof det.best_fitness === "number" ? `, fitness ${det.best_fitness.toFixed(3)}` : "";
      evalBtn.title = `Evaluate current page deterministically with ${det.model_name || "detector"}${epochTxt}${fitTxt} and add pending bounding boxes`;
    }
  }
  $("verifiedNoSymbols").checked = state.doc?.verified_no_symbols === true;
  const pending = (state.doc?.instances || []).filter(x => x.review_status !== "accepted").length;
  $("pageComplete").checked = state.doc?.page_complete === true;
  $("pageComplete").disabled = !state.doc || state.doc.verified_no_symbols === true ||
    (pending > 0 && state.doc.page_complete !== true);
  $("pageComplete").parentElement.title = pending
    ? `Verify or remove the ${pending} pending box${pending > 1 ? "es" : ""} first`
    : "Every electrical symbol on the page has a verified box: usable for validation/testing and as verified background";
  $("verifiedNoSymbols").disabled = !state.doc || state.doc.page_complete === true;
  $("toggleLabels").setAttribute("aria-pressed", String(state.showLabels));
  $("toggleWalls").hidden = !state.walls.length;
  $("toggleWalls").setAttribute("aria-pressed", String(state.showWalls));
  $("cycleViewFilter").textContent = "View: " + VIEW_FILTERS[state.viewFilterIdx].name;

  state.qualityWarnings = computeQualityWarnings();
  const qb = $("qualityBadge");
  if (state.qualityWarnings.length) {
    qb.hidden = false;
    qb.textContent = `${state.qualityWarnings.length} check${state.qualityWarnings.length > 1 ? "s" : ""}`;
  } else {
    qb.hidden = true;
  }

  const banner = $("modeBanner");
  if (state.mode === "oriented") {
    banner.hidden = false;
    banner.textContent = "Oriented box: drag or tap to spawn rotated box auto-aligned to walls (Esc to exit)";
  } else if (state.mode === "anchor") {
    banner.hidden = false;
    const named = state.activeAnchorName && state.activeAnchorName !== "auto"
      ? `"${state.activeAnchorName}"` : "anchors (auto-named: centre / wall_attachment)";
    banner.textContent = `Anchor mode: click on or beside any box to place ${named}; it attaches to the nearest box and stays on for the next one (snaps to Center, Edges, Feature Tip · Esc to exit)`;
  } else if (state.mode === "snap") {
    banner.hidden = false;
    banner.textContent = "Snap vector mode: drag from origin toward wall (Shift snaps 15° · Esc to exit)";
  } else {
    banner.hidden = true;
  }

  const switcher = $("fileSwitcher");
  if (state.modeKind === "edit" && state.siblingFiles.length > 1) {
    switcher.hidden = false;
    if (switcher.options.length !== state.siblingFiles.length) {
      switcher.replaceChildren();
      for (const f of state.siblingFiles) {
        const opt = document.createElement("option");
        opt.value = f.name;
        opt.textContent = f.name;
        switcher.append(opt);
      }
    }
    switcher.value = state.jsonFilename;
  } else {
    switcher.hidden = true;
  }
}

function sortedItems(list = state.doc.instances) {
  return [...list].sort((a, b) =>
    a.bbox_xyxy[1] - b.bbox_xyxy[1] || a.bbox_xyxy[0] - b.bbox_xyxy[0]
  );
}

function itemSearchText(item) {
  const attrs = Array.isArray(item.attributes_from_text) ? item.attributes_from_text.join(" ") : "";
  const cat = catalogEntryFor(item.symbol_family, item.symbol_variant);
  const catText = cat ? `${cat.label} ${cat.category}` : "";
  return (item.symbol_family + " " + item.symbol_variant + " " +
    item.instance_id + " " + disciplineFor(item.symbol_family) + " " + attrs + " " + catText).toLowerCase();
}

function refreshList() {
  const accepted = state.doc.instances.filter(x => x.review_status === "accepted").length;
  const unreviewed = state.doc.instances.filter(x => x.review_status !== "accepted").length;
  const rejected = (state.doc.rejected_proposals || []).length;
  $("count").textContent = state.doc.instances.length + " active · " +
    accepted + " verified · " + unreviewed + " pending · " + rejected + " rejected";

  const query = $("search").value.trim().toLowerCase();
  const filter = $("filter").value;
  elements.items.replaceChildren();

  if (filter === "rejected") {
    const rows = sortedItems(state.doc.rejected_proposals || []).filter(item =>
      !query || itemSearchText(item).includes(query)
    );
    if (!rows.length) {
      const p = document.createElement("p");
      p.className = "small";
      p.textContent = "No rejected proposals on this page.";
      elements.items.append(p);
      return;
    }
    for (const item of rows.slice(0, 400)) {
      const wrap = document.createElement("div");
      wrap.className = "item-row";
      const btn = document.createElement("button");
      btn.className = "item-btn";
      const txt = document.createElement("span");
      txt.className = "item-label";
      txt.textContent = deviceLabel(item);
      txt.title = item.symbol_family + " / " + item.symbol_variant;
      const tag = document.createElement("span");
      tag.className = "tag rejected";
      tag.textContent = "Rejected";
      btn.append(txt, tag);
      btn.addEventListener("click", () => centerBox(item.bbox_xyxy));

      const restoreBtn = document.createElement("button");
      restoreBtn.className = "ghost";
      restoreBtn.textContent = "Restore";
      restoreBtn.title = "Move back to active pending list";
      restoreBtn.addEventListener("click", () => restoreRejected(item.instance_id));
      wrap.append(btn, restoreBtn);
      elements.items.append(wrap);
    }
    return;
  }

  const rows = sortedItems().filter(item => {
    if (filter === "accepted" && item.review_status !== "accepted") return false;
    if (filter === "unreviewed" && item.review_status === "accepted") return false;
    return !query || itemSearchText(item).includes(query);
  });

  for (const item of rows.slice(0, 400)) {
    const btn = document.createElement("button");
    btn.className = "item-btn" + (item.instance_id === state.selectedId ? " chosen" : "");
    const txt = document.createElement("span");
    txt.className = "item-label";
    txt.textContent = deviceLabel(item);
    txt.title = item.symbol_family + " / " + item.symbol_variant;
    const tag = document.createElement("span");
    if (item.review_status === "accepted") {
      tag.className = "tag accepted";
      tag.textContent = "Verified";
    } else if (item.review_status == null && !item.uncertain) {
      tag.className = "tag legacy";
      tag.textContent = "Existing";
    } else {
      tag.className = "tag";
      tag.textContent = (item.review_source === "model" && typeof item.model_confidence === "number")
        ? `Pending · ${Math.round(item.model_confidence * 100)}%${item.model_review_required ? " · check" : ""}`
        : "Pending";
      if (item.review_source === "model" && Array.isArray(item.model_review_reasons) && item.model_review_reasons.length) {
        tag.title = "Model flags: " + item.model_review_reasons.join(", ");
      }
    }
    btn.append(txt, tag);
    btn.addEventListener("click", () => select(item.instance_id, true));
    elements.items.append(btn);
  }
  if (rows.length > 400) {
    const note = document.createElement("p");
    note.className = "small";
    note.textContent = "Showing first 400. Filter to narrow results.";
    elements.items.append(note);
  }
}


function subtypesForFamily(family) {
  const base = state.variantsByFamily[family] || ["unspecified"];
  const custom = state.customVariantsByFamily[family] || [];
  const merged = [...base];
  for (const v of custom) {
    if (!merged.includes(v)) merged.unshift(v);
  }
  return merged;
}

function applyQuickSubtype(subtype) {
  if (!subtype) return;
  const item = chosen();
  if (!item) return;
  state.lastVariantByFamily[item.symbol_family] = subtype;
  modifySelection(x => {
    x.symbol_variant = subtype;
  });
  notify(`Set subtype to ${subtype}.`, "saved");
}

function renderTextAttributes(item) {
  const container = $("textAttrList");
  container.replaceChildren();
  const attrs = Array.isArray(item.attributes_from_text) ? item.attributes_from_text : [];
  if (!attrs.length) {
    const empty = document.createElement("span");
    empty.className = "small";
    empty.textContent = "No nearby text callouts labeled.";
    container.append(empty);
    return;
  }
  attrs.forEach((val, idx) => {
    const chip = document.createElement("span");
    chip.className = "attr-tag";
    const span = document.createElement("span");
    span.textContent = val;
    const rm = document.createElement("button");
    rm.type = "button";
    rm.textContent = "×";
    rm.title = "Remove text attribute";
    rm.addEventListener("click", () => modifySelection(x => {
      x.attributes_from_text = (x.attributes_from_text || []).filter((_, i) => i !== idx);
    }));
    chip.append(span, rm);
    container.append(chip);
  });
}

function renderDeviceAttributes(item) {
  const schemaFields = $("schemaFields");
  const flagFields = $("flagFields");
  const specFields = $("specFields");
  const customList = $("customAttrList");
  schemaFields.replaceChildren();
  flagFields.replaceChildren();
  specFields.replaceChildren();
  customList.replaceChildren();

  const attrs = item.device_attributes || {};
  const schemaCfg = state.attributeSchema || {};
  const schemaKey = schemaCfg.family_map?.[item.symbol_family] || item.symbol_family;
  const schema = schemaCfg.schemas?.[schemaKey] || {};
  const listFields = new Set(schemaCfg.list_fields || []);
  const handledKeys = new Set();

  for (const [key, choices] of Object.entries(schema)) {
    handledKeys.add(key);
    if (listFields.has(key)) {
      const wrap = document.createElement("div");
      wrap.style.marginBottom = "8px";
      const lbl = document.createElement("div");
      lbl.className = "small";
      lbl.textContent = key.replace(/_/g, " ");
      const chips = document.createElement("div");
      chips.className = "chip-list";
      const selected = new Set(Array.isArray(attrs[key]) ? attrs[key] : []);
      for (const choice of choices) {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "chip";
        btn.setAttribute("aria-pressed", String(selected.has(choice)));
        btn.textContent = choice.replace(/_/g, " ");
        btn.addEventListener("click", () => modifySelection(x => {
          x.device_attributes ||= {};
          const next = new Set(Array.isArray(x.device_attributes[key]) ? x.device_attributes[key] : []);
          if (next.has(choice)) next.delete(choice);
          else next.add(choice);
          if (next.size) x.device_attributes[key] = [...next];
          else delete x.device_attributes[key];
          if (!Object.keys(x.device_attributes).length) delete x.device_attributes;
        }));
        chips.append(btn);
      }
      wrap.append(lbl, chips);
      schemaFields.append(wrap);
    } else {
      const lbl = document.createElement("label");
      lbl.className = "field";
      lbl.textContent = key.replace(/_/g, " ");
      const sel = document.createElement("select");
      const emptyOpt = document.createElement("option");
      emptyOpt.value = "";
      emptyOpt.textContent = "— not specified —";
      sel.append(emptyOpt);
      for (const choice of choices) {
        const opt = document.createElement("option");
        opt.value = choice;
        opt.textContent = choice.replace(/_/g, " ");
        sel.append(opt);
      }
      sel.value = attrs[key] || "";
      sel.addEventListener("change", () => modifySelection(x => {
        x.device_attributes ||= {};
        if (sel.value) x.device_attributes[key] = sel.value;
        else delete x.device_attributes[key];
        if (!Object.keys(x.device_attributes).length) delete x.device_attributes;
      }));
      lbl.append(sel);
      schemaFields.append(lbl);
    }
  }

  for (const flag of schemaCfg.flags || []) {
    handledKeys.add(flag);
    const lbl = document.createElement("label");
    lbl.className = "checkbox";
    const cb = document.createElement("input");
    cb.type = "checkbox";
    cb.checked = attrs[flag] === true;
    cb.addEventListener("change", () => modifySelection(x => {
      x.device_attributes ||= {};
      if (cb.checked) x.device_attributes[flag] = true;
      else delete x.device_attributes[flag];
      if (!Object.keys(x.device_attributes).length) delete x.device_attributes;
    }));
    lbl.append(cb, " " + flag.replace(/_/g, " "));
    flagFields.append(lbl);
  }

  for (const key of schemaCfg.numeric || []) {
    handledKeys.add(key);
    const lbl = document.createElement("label");
    lbl.textContent = key.replace(/_/g, " ");
    const inp = document.createElement("input");
    inp.type = "number";
    inp.step = (key === "pole_count" || key === "wire_count" || key === "phase_count") ? "1" : "any";
    inp.min = "0";
    inp.value = attrs[key] != null ? String(attrs[key]) : "";
    inp.placeholder = key === "phase_count" ? "1 or 3" : "";
    inp.addEventListener("change", () => {
      const raw = inp.value.trim();
      if (!raw) {
        modifySelection(x => {
          if (x.device_attributes) {
            delete x.device_attributes[key];
            if (!Object.keys(x.device_attributes).length) delete x.device_attributes;
          }
        });
        return;
      }
      const num = Number(raw);
      if (!Number.isFinite(num) || num <= 0) {
        notify(`${key} must be a positive number.`, "error");
        fillSelection();
        return;
      }
      if (key === "phase_count" && num !== 1 && num !== 3) {
        notify("phase_count must be 1 or 3.", "error");
        fillSelection();
        return;
      }
      if ((key === "pole_count" || key === "wire_count") && !Number.isInteger(num)) {
        notify(`${key} must be a whole number.`, "error");
        fillSelection();
        return;
      }
      modifySelection(x => {
        x.device_attributes ||= {};
        x.device_attributes[key] = num;
      });
    });
    lbl.append(inp);
    specFields.append(lbl);
  }

  for (const key of schemaCfg.string || []) {
    handledKeys.add(key);
    const lbl = document.createElement("label");
    lbl.textContent = key.replace(/_/g, " ");
    const inp = document.createElement("input");
    inp.type = "text";
    inp.value = attrs[key] != null ? String(attrs[key]) : "";
    inp.addEventListener("change", () => {
      const val = inp.value.trim();
      modifySelection(x => {
        x.device_attributes ||= {};
        if (val) x.device_attributes[key] = val;
        else delete x.device_attributes[key];
        if (!Object.keys(x.device_attributes).length) delete x.device_attributes;
      });
    });
    lbl.append(inp);
    specFields.append(lbl);
  }

  for (const [k, v] of Object.entries(attrs)) {
    if (handledKeys.has(k) || k === "evidence") continue;
    const chip = document.createElement("span");
    chip.className = "attr-tag";
    const span = document.createElement("span");
    span.textContent = `${k}: ${typeof v === "object" ? JSON.stringify(v) : v}`;
    const rm = document.createElement("button");
    rm.type = "button";
    rm.textContent = "×";
    rm.addEventListener("click", () => modifySelection(x => {
      if (x.device_attributes) {
        delete x.device_attributes[k];
        if (!Object.keys(x.device_attributes).length) delete x.device_attributes;
      }
    }));
    chip.append(span, rm);
    customList.append(chip);
  }
}

function renderAnchors(item) {
  const list = $("anchorList");
  list.replaceChildren();
  const entries = Object.entries(item.anchors || {});
  if (!entries.length) {
    const none = document.createElement("span");
    none.className = "small";
    none.textContent = "none";
    list.append(none);
    return;
  }
  for (const [name] of entries) {
    const tag = document.createElement("span");
    tag.className = "attr-tag";
    tag.textContent = name.replace(/_/g, " ");
    const rm = document.createElement("button");
    rm.type = "button";
    rm.textContent = "×";
    rm.title = "Remove anchor";
    rm.addEventListener("click", () => modifySelection(x => {
      if (x.anchors) {
        delete x.anchors[name];
        if (!Object.keys(x.anchors).length) delete x.anchors;
      }
    }));
    tag.append(rm);
    list.append(tag);
  }
}

// A box can be verified only once it names one specific device.
function incompleteReason(item) {
  const family = String(item?.symbol_family || "");
  const variant = String(item?.symbol_variant || "");
  if (!family || family === "unknown_symbol" || family === "unmapped") return "Pick the device in the list below.";
  if (!variant || variant === "unspecified") return "Pick the exact device type below before verifying.";
  return null;
}

function deviceLabel(item) {
  const entry = catalogEntryFor(item.symbol_family, item.symbol_variant);
  return entry ? entry.label : `${item.symbol_family} / ${item.symbol_variant}`;
}

function fillSelection() {
  const count = state.selectedIds?.size || (chosen() ? 1 : 0);
  $("multiSelectionCard").hidden = count <= 1;
  if (count > 1) {
    const ready = chosenList().filter(x => !incompleteReason(x)).length;
    $("multiSelectionTitle").textContent = `${count} symbols selected` +
      (ready < count ? ` · ${count - ready} need a device before verifying` : "");
  }
  const item = chosen();
  $("emptySelection").hidden = count > 0;
  $("selectionDetails").hidden = count !== 1 || !item;
  if (!item || count !== 1) return;

  $("selectedLabel").textContent = deviceLabel(item);
  $("selectedLabel").title = `${item.symbol_family} / ${item.symbol_variant}`;
  const status = $("selectedStatus");
  const verified = item.review_status === "accepted";
  status.textContent = verified ? "Verified" : "Pending";
  status.className = "tag" + (verified ? " accepted" : "");
  const missing = incompleteReason(item);
  $("verifyHint").hidden = !missing || verified;
  $("verifyHint").textContent = missing || "";
  $("accept").disabled = Boolean(missing) || verified;
  $("accept").title = missing ? missing : "Verify selected box (A)";
  $("unverify").hidden = !verified;
  $("angle").value = item.bbox_rotated?.angle_deg ?? (item.rotation_degrees || 0);
  $("occluded").checked = Boolean(item.is_occluded);
  $("truncated").checked = Boolean(item.is_truncated);
  renderTextAttributes(item);
  renderDeviceAttributes(item);
  renderAnchors(item);
}

function refresh() {
  if (!state.doc) return;
  if (state.selectedId && !chosen()) state.selectedId = null;
  refreshControls();
  refreshList();
  fillSelection();
  updateActiveDevicePill();
  renderInlineDeviceCatalog();
  draw();
}

function select(id, focus = false) {
  if (state.selectedId !== id) state.snapPreview = null;
  if (id == null) {
    state.selectedIds.clear();
    state.selectedId = null;
  } else {
    state.selectedIds = new Set([id]);
    state.selectedId = id;
  }
  if (focus && chosen()) centerBox(chosen().bbox_xyxy);
  refresh();
}

function selectMultiple(ids) {
  state.selectedIds = new Set(ids);
  state.selectedId = ids.length ? ids[0] : null;
  refresh();
}

function toggleSelect(id) {
  if (state.selectedIds.has(id)) {
    state.selectedIds.delete(id);
    if (state.selectedId === id) {
      state.selectedId = state.selectedIds.size ? [...state.selectedIds][0] : null;
    }
  } else {
    state.selectedIds.add(id);
    state.selectedId = id;
  }
  refresh();
}

function setMode(mode) {
  if (state.busy) return;
  if (mode === "snap" && !chosen()) {
    notify("Select a box first before drawing a snap vector.", "warning");
    return;
  }
  if (mode === "anchor" && !state.doc?.instances?.length) {
    notify("Draw a box first; anchors attach to boxes.", "warning");
    return;
  }
  if (mode !== "anchor") {
    state.anchorTargetId = null;
    state.activeAnchorSnap = null;
  }
  state.orientedDraft = null;
  state.mode = mode;
  refreshControls();
  elements.canvas.style.cursor =
    mode === "pan" ? "grab" :
    (mode === "draw" || mode === "oriented" || mode === "anchor" || mode === "snap") ? "crosshair" : "default";
  draw();
}

function subtypeForChangedFamily(family, existingVariant) {
  // A subtype such as 'duplex' must not survive a change to 'switch'.
  // Keep cross-family variants only if the destination explicitly supports them.
  const supported = state.variantsByFamily[family] || [];
  return supported.includes(existingVariant) ? existingVariant : "unspecified";
}

function applyQuickFamily(family) {
  if (!family) return;
  state.lastFamily = family;
  const list = chosenList();
  if (!list.length) {
    notify(`Next drawn box will default to ${family}.`);
    return;
  }
  remember();
  for (const item of list) {
    const before = reviewEvidenceKey(item);
    if (family !== item.symbol_family) {
      item.symbol_variant = subtypeForChangedFamily(family, item.symbol_variant);
    }
    item.symbol_family = family;
    item.discipline = disciplineFor(family);
    item.device_type = family;
    invalidateReviewOnChange(item, before);
  }
  editState();
  refresh();
  notify(`Set family to ${family} for ${list.length} symbol${list.length > 1 ? "s" : ""}.`, "saved");
}

function newInstance(box, rotated = null) {
  try {
    const snapped = snapBoxToContrastEdges(box, rotated);
    if (snapped && checkBox(snapped.box)) {
      box = snapped.box;
      if (snapped.rotated) rotated = snapped.rotated;
    }
  } catch (err) {
    console.warn("Auto edge snap skipped:", err);
  }
  const family = state.lastFamily;
  const item = {
    instance_id: "manual_p" + (state.page + 1) + "_" + generateId(),
    symbol_family: family,
    symbol_variant: state.lastVariantByFamily[family] || "unspecified",
    discipline: disciplineFor(family),
    device_type: family,
    bbox_xyxy: box.map(x => Math.round(x * 1000) / 1000),
    attributes_from_text: [],
    rotation_degrees: rotated ? Math.round(rotated.angle_deg * 1000) / 1000 : 0,
    uncertain: true,
    review_status: "unreviewed",
    review_source: "manual_draft",
    verified_ground_truth: false,
    is_truncated: false,
    is_occluded: false,
  };
  if (rotated && Math.abs(rotated.angle_deg) > 1e-6) {
    item.bbox_rotated = {
      cx: Math.round(rotated.cx * 1000) / 1000,
      cy: Math.round(rotated.cy * 1000) / 1000,
      w: Math.round(rotated.w * 1000) / 1000,
      h: Math.round(rotated.h * 1000) / 1000,
      angle_deg: Math.round(rotated.angle_deg * 1000) / 1000,
    };
  }
  state.doc.verified_no_symbols = false;
  state.doc.instances.push(item);
  select(item.instance_id);
  editState();
  notify(rotated ? "Oriented box added & snapped. Press 1–6 for family, A to verify." : "Box added & snapped to contrast edges. Press 1–6 for family, A to verify.");
}

function checkBox(box) {
  return box[2] - box[0] >= 2 && box[3] - box[1] >= 2 &&
    box[0] >= 0 && box[1] >= 0 && box[2] <= state.doc.image_width &&
    box[3] <= state.doc.image_height;
}

const REVIEW_EVIDENCE_FIELDS = [
  "bbox_xyxy", "bbox_rotated", "rotation_degrees",
  "symbol_family", "symbol_variant", "discipline", "device_type",
  "anchors", "snap_vector", "device_attributes", "attributes_from_text",
  "is_occluded", "is_truncated",
];

function reviewEvidenceKey(item) {
  return JSON.stringify(REVIEW_EVIDENCE_FIELDS.map(key => item[key] ?? null));
}

function invalidateReviewOnChange(item, before) {
  if (reviewEvidenceKey(item) === before) return;
  state.snapPreview = null; // A preview calculated for old geometry is stale.
  if (item.verified_ground_truth === true) {
    item.review_status = "unreviewed";
    item.review_source = "manual_draft";
    item.verified_ground_truth = false;
    item.uncertain = true;
  }
}

function modifySelection(callback) {
  if (state.busy) return;
  const item = chosen();
  if (!item) return;
  const before = reviewEvidenceKey(item);
  remember();
  try {
    const ok = callback(item);
    if (ok === false) { state.undo.pop(); return; }
    invalidateReviewOnChange(item, before);
    editState();
    refresh();
  } catch (error) {
    state.undo.pop();
    notify(String(error.message || error), "error");
  }
}

function flushPendingInputs() {
  const active = document.activeElement;
  if (active && active.matches?.("#inspector input, #inspector select, #inspector textarea")) {
    active.dispatchEvent(new Event("change", { bubbles: true }));
  }
  if (chosen()) {
    updateSelectedFields();
    updateAngle();
  }
}

function verifySelected() {
  flushPendingInputs();
  const list = chosenList();
  if (!list.length) return;
  const ready = list.filter(item => !incompleteReason(item));
  const skipped = list.length - ready.length;
  if (!ready.length) {
    notify(incompleteReason(list[0]), "warning");
    return;
  }
  remember();
  for (const item of ready) {
    item.review_status = "accepted";
    item.review_source = "human";
    item.verified_ground_truth = true;
    item.uncertain = false;
  }
  editState();
  refresh();
  notify(`Verified ${ready.length} symbol${ready.length > 1 ? "s" : ""}.` +
         (skipped ? ` ${skipped} skipped: pick their device type first.` : ""), skipped ? "warning" : "saved");
  if (!skipped) nextPending(false);
}

function unverifySelected() {
  flushPendingInputs();
  const list = chosenList();
  if (!list.length) return;
  remember();
  for (const item of list) {
    item.review_status = "unreviewed";
    item.review_source = "manual_draft";
    item.verified_ground_truth = false;
    item.uncertain = true;
  }
  editState();
  refresh();
  notify(`Marked ${list.length} symbol${list.length > 1 ? "s" : ""} as pending.`, "saved");
}

function rejectSelected() {
  flushPendingInputs();
  if (state.busy) return;
  const list = chosenList();
  if (!list.length) return;
  remember();
  const rejSet = new Set(list.map(x => x.instance_id));
  state.doc.instances = state.doc.instances.filter(x => !rejSet.has(x.instance_id));
  state.doc.rejected_proposals ||= [];
  for (const item of list) {
    state.doc.rejected_proposals.push({
      ...item,
      review_status: "rejected",
      review_source: "human",
      verified_ground_truth: false,
      uncertain: true,
    });
  }
  state.selectedId = null;
  state.selectedIds.clear();
  editState();
  refresh();
  notify(`Rejected ${list.length} symbol${list.length > 1 ? "s" : ""}.`, "saved");
  nextPending(false);
}

function restoreRejected(instanceId) {
  if (state.busy) return;
  const list = state.doc.rejected_proposals || [];
  const target = list.find(x => x.instance_id === instanceId);
  if (!target) return;
  remember();
  state.doc.rejected_proposals = list.filter(x => x.instance_id !== instanceId);
  const restored = {
    ...target,
    review_status: "unreviewed",
    review_source: target.proposal_score != null ? "sam3" : "manual_draft",
    verified_ground_truth: false,
    uncertain: true,
  };
  state.doc.instances.push(restored);
  $("filter").value = "all";
  select(restored.instance_id, true);
  editState();
  notify(`Restored ${restored.instance_id} to pending review.`, "saved");
}

function cloneInstanceAt(item, targetCenter = null) {
  const w = item.bbox_xyxy[2] - item.bbox_xyxy[0];
  const h = item.bbox_xyxy[3] - item.bbox_xyxy[1];
  const oldCx = (item.bbox_xyxy[0] + item.bbox_xyxy[2]) / 2;
  const oldCy = (item.bbox_xyxy[1] + item.bbox_xyxy[3]) / 2;
  const rawNx0 = targetCenter ? targetCenter[0] - w / 2 : item.bbox_xyxy[0] + 12;
  const rawNy0 = targetCenter ? targetCenter[1] - h / 2 : item.bbox_xyxy[1] + 12;
  const nx0 = clamp(rawNx0, 0, state.doc.image_width - w);
  const ny0 = clamp(rawNy0, 0, state.doc.image_height - h);
  const dx = nx0 - item.bbox_xyxy[0];
  const dy = ny0 - item.bbox_xyxy[1];

  const copy = JSON.parse(JSON.stringify(item));
  copy.instance_id = "manual_p" + (state.page + 1) + "_" + generateId();
  copy.bbox_xyxy = [
    Math.round((item.bbox_xyxy[0] + dx) * 1000) / 1000,
    Math.round((item.bbox_xyxy[1] + dy) * 1000) / 1000,
    Math.round((item.bbox_xyxy[2] + dx) * 1000) / 1000,
    Math.round((item.bbox_xyxy[3] + dy) * 1000) / 1000,
  ];
  if (copy.bbox_rotated) {
    copy.bbox_rotated.cx = Math.round((oldCx + dx) * 1000) / 1000;
    copy.bbox_rotated.cy = Math.round((oldCy + dy) * 1000) / 1000;
  }
  if (copy.anchors) {
    for (const a of Object.values(copy.anchors)) {
      if (Array.isArray(a.xy)) {
        a.xy = [
          clamp(Math.round((a.xy[0] + dx) * 1000) / 1000, 0, state.doc.image_width),
          clamp(Math.round((a.xy[1] + dy) * 1000) / 1000, 0, state.doc.image_height),
        ];
      }
    }
  }
  if (copy.snap_vector?.origin) {
    copy.snap_vector.origin = [
      clamp(Math.round((copy.snap_vector.origin[0] + dx) * 1000) / 1000, 0, state.doc.image_width),
      clamp(Math.round((copy.snap_vector.origin[1] + dy) * 1000) / 1000, 0, state.doc.image_height),
    ];
  }
  copy.review_status = "unreviewed";
  copy.review_source = "manual_draft";
  copy.verified_ground_truth = false;
  copy.uncertain = true;
  delete copy.snap;
  delete copy.proposal_score;
  delete copy.proposal_visual_group;
  delete copy.proposal_neca_symbol_id;
  delete copy.proposal_alternative_labels;
  return copy;
}

function duplicateSelected() {
  flushPendingInputs();
  if (state.busy) return;
  const item = chosen();
  if (!item) return;
  remember();
  const copy = cloneInstanceAt(item, null);
  state.doc.verified_no_symbols = false;
  state.doc.instances.push(copy);
  select(copy.instance_id);
  editState();
  notify("Duplicated box as draft. Adjust position and press A to verify.");
}

function copySelected() {
  const item = chosen();
  if (!item) return;
  state.clipboard = JSON.parse(JSON.stringify(item));
  notify(`Copied ${item.symbol_family}/${item.symbol_variant}. Move cursor and press Ctrl+V to stamp.`, "saved");
}

function pasteAtCursor() {
  if (!state.clipboard || !state.doc || state.busy) return;
  remember();
  const copy = cloneInstanceAt(state.clipboard, state.pointerImg);
  state.doc.verified_no_symbols = false;
  state.doc.instances.push(copy);
  select(copy.instance_id);
  editState();
  notify(`Stamped ${copy.symbol_family}/${copy.symbol_variant} at cursor. Press A to verify.`, "saved");
}

function deleteSelected() {
  const list = chosenList();
  if (state.busy || !list.length) return;
  remember();
  const toDelete = new Set(list.map(x => x.instance_id));
  state.doc.instances = state.doc.instances.filter(x => !toDelete.has(x.instance_id));
  state.selectedId = null;
  state.selectedIds.clear();
  editState();
  refresh();
  notify(`Deleted ${list.length} box${list.length > 1 ? "es" : ""}.`, "saved");
}

function nudgeSelected(dx, dy) {
  const list = chosenList();
  if (!list.length || state.busy) return;
  remember();
  for (const item of list) {
    const tx = clamp(dx, -item.bbox_xyxy[0], state.doc.image_width - item.bbox_xyxy[2]);
    const ty = clamp(dy, -item.bbox_xyxy[1], state.doc.image_height - item.bbox_xyxy[3]);
    if (Math.abs(tx) < 1e-6 && Math.abs(ty) < 1e-6) continue;
    const before = reviewEvidenceKey(item);
    item.bbox_xyxy = [
      Math.round((item.bbox_xyxy[0] + tx) * 1000) / 1000,
      Math.round((item.bbox_xyxy[1] + ty) * 1000) / 1000,
      Math.round((item.bbox_xyxy[2] + tx) * 1000) / 1000,
      Math.round((item.bbox_xyxy[3] + ty) * 1000) / 1000,
    ];
    if (item.bbox_rotated) {
      item.bbox_rotated.cx = Math.round((item.bbox_rotated.cx + tx) * 1000) / 1000;
      item.bbox_rotated.cy = Math.round((item.bbox_rotated.cy + ty) * 1000) / 1000;
    }
    invalidateReviewOnChange(item, before);       // a moved box needs a fresh check
  }
  editState();
  refresh();
}

function nextPending(wrap = true) {
  const items = sortedItems().filter(x => x.review_status !== "accepted");
  if (!items.length) {
    if (wrap) notify("No pending proposals on this page.", "saved");
    return;
  }
  const idx = items.findIndex(x => x.instance_id === state.selectedId);
  select(items[(idx + 1) % items.length].instance_id, true);
}

function jumpNextQualityWarning() {
  if (!state.qualityWarnings.length) return;
  const w = state.qualityWarnings[state.warningCursor % state.qualityWarnings.length];
  state.warningCursor = (state.warningCursor + 1) % state.qualityWarnings.length;
  select(w.id, true);
  notify(`Quality check: ${w.reason}`, "warning");
}

function currentPageMouseHit(point) {
  const selected = chosen();
  if (selected) {
    const hit = hitBox(selected.bbox_xyxy, point[0], point[1], state.scale, true, selected.bbox_rotated);
    if (hit) return { item: selected, action: hit };
  }
  for (const item of [...state.doc.instances].reverse()) {
    const hit = hitBox(item.bbox_xyxy, point[0], point[1], state.scale, false, item.bbox_rotated);
    if (hit) return { item, action: hit };
  }
  return null;
}

function pointerDown(event) {
  if (!state.image || state.busy || event.button > 1) return;
  event.preventDefault();
  flushPendingInputs();
  const xy = coordinate(event), pt = screenToImage(...xy, viewport());
  if (state.space || event.button === 1) {
    state.drag = { type: "pan", at: xy, x: state.x, y: state.y };
  } else if (state.mode === "pan") {
    const hit = currentPageMouseHit(pt);
    if (hit) {
      select(hit.item.instance_id);
      remember();
      const bb = hit.item.bbox_xyxy;
      const rotBase = hit.item.bbox_rotated
        ? { ...hit.item.bbox_rotated }
        : { cx: (bb[0] + bb[2]) / 2, cy: (bb[1] + bb[3]) / 2, w: bb[2] - bb[0], h: bb[3] - bb[1], angle_deg: 0 };
      state.drag = {
        type: hit.action,
        start: pt,
        box: [...bb],
        boxes: { [hit.item.instance_id]: [...bb] },
        originalRotation: hit.item.bbox_rotated ? { ...hit.item.bbox_rotated } : null,
        originalRotations: { [hit.item.instance_id]: hit.item.bbox_rotated ? { ...hit.item.bbox_rotated } : null },
        rotBase,
        moved: false,
        selected: hit.item.instance_id,
        selectedIds: [hit.item.instance_id],
        reviewEvidence: reviewEvidenceKey(hit.item),
      };
    } else {
      select(null);
      state.drag = { type: "pan", at: xy, x: state.x, y: state.y };
    }
  } else if (state.mode === "select") {
    const hit = currentPageMouseHit(pt);
    if (!hit) {
      if (!event.shiftKey) select(null);
      state.drag = { type: "marquee", start: pt, current: pt };
    } else {
      if (event.shiftKey) {
        toggleSelect(hit.item.instance_id);
      } else if (!state.selectedIds?.has(hit.item.instance_id)) {
        select(hit.item.instance_id);
      }
      remember();
      const movingItems = chosenList();
      const boxes = {};
      const originalRotations = {};
      for (const it of movingItems) {
        boxes[it.instance_id] = [...it.bbox_xyxy];
        originalRotations[it.instance_id] = it.bbox_rotated ? { ...it.bbox_rotated } : null;
      }
      const bb = hit.item.bbox_xyxy;
      const rotBase = hit.item.bbox_rotated
        ? { ...hit.item.bbox_rotated }
        : { cx: (bb[0] + bb[2]) / 2, cy: (bb[1] + bb[3]) / 2, w: bb[2] - bb[0], h: bb[3] - bb[1], angle_deg: 0 };
      state.drag = {
        type: hit.action,
        start: pt,
        box: [...bb],
        boxes,
        originalRotation: hit.item.bbox_rotated ? { ...hit.item.bbox_rotated } : null,
        originalRotations,
        rotBase,
        moved: false,
        selected: hit.item.instance_id,
        selectedIds: movingItems.map(x => x.instance_id),
        reviewEvidence: reviewEvidenceKey(hit.item),
      };
    }
  } else if (state.mode === "draw") {
    const hit = currentPageMouseHit(pt);
    if (hit) {
      select(hit.item.instance_id);
      remember();
      const bb = hit.item.bbox_xyxy;
      const rotBase = hit.item.bbox_rotated
        ? { ...hit.item.bbox_rotated }
        : { cx: (bb[0] + bb[2]) / 2, cy: (bb[1] + bb[3]) / 2, w: bb[2] - bb[0], h: bb[3] - bb[1], angle_deg: 0 };
      state.drag = {
        type: hit.action,
        start: pt,
        box: [...bb],
        boxes: { [hit.item.instance_id]: [...bb] },
        originalRotation: hit.item.bbox_rotated ? { ...hit.item.bbox_rotated } : null,
        originalRotations: { [hit.item.instance_id]: hit.item.bbox_rotated ? { ...hit.item.bbox_rotated } : null },
        rotBase,
        moved: false,
        selected: hit.item.instance_id,
        selectedIds: [hit.item.instance_id],
        reviewEvidence: reviewEvidenceKey(hit.item),
      };
    } else {
      state.drag = { type: "draw", start: pt, current: pt };
    }
  } else if (state.mode === "oriented") {
    const hit = currentPageMouseHit(pt);
    if (hit) {
      select(hit.item.instance_id);
      remember();
      const bb = hit.item.bbox_xyxy;
      const rotBase = hit.item.bbox_rotated
        ? { ...hit.item.bbox_rotated }
        : { cx: (bb[0] + bb[2]) / 2, cy: (bb[1] + bb[3]) / 2, w: bb[2] - bb[0], h: bb[3] - bb[1], angle_deg: 0 };
      state.drag = {
        type: hit.action,
        start: pt,
        box: [...bb],
        boxes: { [hit.item.instance_id]: [...bb] },
        originalRotation: hit.item.bbox_rotated ? { ...hit.item.bbox_rotated } : null,
        originalRotations: { [hit.item.instance_id]: hit.item.bbox_rotated ? { ...hit.item.bbox_rotated } : null },
        rotBase,
        moved: false,
        selected: hit.item.instance_id,
        selectedIds: [hit.item.instance_id],
        reviewEvidence: reviewEvidenceKey(hit.item),
      };
    } else {
      const clampedPt = [clamp(pt[0], 0, state.doc.image_width), clamp(pt[1], 0, state.doc.image_height)];
      state.drag = { type: "oriented", start: clampedPt, current: clampedPt };
    }
  } else if (state.mode === "anchor") {
    updateAnchorSnap(pt);
    const item = anchorTargetItem();
    if (!item) {
      notify("No box near the pointer; move onto or next to the box to anchor.", "warning");
      return;
    }
    let ax = clamp(pt[0], 0, state.doc.image_width);
    let ay = clamp(pt[1], 0, state.doc.image_height);
    let snapLabel = "manual";
    let snapKey = null;

    if (state.activeAnchorSnap) {
      ax = state.activeAnchorSnap.xy[0];
      ay = state.activeAnchorSnap.xy[1];
      snapLabel = state.activeAnchorSnap.label;
      snapKey = state.activeAnchorSnap.key || state.activeAnchorSnap.kind;
    } else {
      const tip = findFeatureTipInArea(pt, item, 22 / state.scale);
      if (tip) {
        ax = tip.xy ? tip.xy[0] : tip[0];
        ay = tip.xy ? tip.xy[1] : tip[1];
        snapLabel = "Feature Tip";
        snapKey = "tip";
      }
    }

    const name = resolveAnchorName(item, snapKey);
    // Attach to the box under the pointer without a separate select step; the
    // tool stays active so a whole page can be anchored box after box.
    if (state.selectedId !== item.instance_id) select(item.instance_id);
    modifySelection(x => {
      x.anchors ||= {};
      x.anchors[name] = {
        xy: [Math.round(ax * 1000) / 1000, Math.round(ay * 1000) / 1000],
        visible: true,
        annotated: true,
      };
    });
    notify(`Anchor "${name}" on ${item.instance_id} at (${ax.toFixed(1)}, ${ay.toFixed(1)}) [${snapLabel}].`, "saved");
    state.activeAnchorSnap = null;
    updateAnchorSnap(pt);
    draw();
    return;
  } else if (state.mode === "snap") {
    const item = chosen();
    if (!item) { setMode("select"); return; }
    const ctr = itemCenter(item);
    const useCenter = Math.hypot(pt[0] - ctr[0], pt[1] - ctr[1]) <= 14 / state.scale;
    const origin = useCenter
      ? ctr
      : [clamp(pt[0], 0, state.doc.image_width), clamp(pt[1], 0, state.doc.image_height)];
    state.drag = { type: "snap", start: origin, current: pt };
  } else {
    const hit = currentPageMouseHit(pt);
    if (!hit) {
      select(null);
      state.drag = { type: "pan", at: xy, x: state.x, y: state.y };
    } else {
      select(hit.item.instance_id);
      remember();
      const bb = hit.item.bbox_xyxy;
      const rotBase = hit.item.bbox_rotated
        ? { ...hit.item.bbox_rotated }
        : { cx: (bb[0] + bb[2]) / 2, cy: (bb[1] + bb[3]) / 2, w: bb[2] - bb[0], h: bb[3] - bb[1], angle_deg: 0 };
      state.drag = {
        type: hit.action,
        start: pt,
        box: [...bb],
        boxes: { [hit.item.instance_id]: [...bb] },
        originalRotation: hit.item.bbox_rotated ? { ...hit.item.bbox_rotated } : null,
        originalRotations: { [hit.item.instance_id]: hit.item.bbox_rotated ? { ...hit.item.bbox_rotated } : null },
        rotBase,
        moved: false,
        selected: hit.item.instance_id,
        selectedIds: [hit.item.instance_id],
        reviewEvidence: reviewEvidenceKey(hit.item),
      };
    }
  }
  if (state.drag) elements.canvas.setPointerCapture(event.pointerId);
  draw();
}

const CIRCULAR_ANCHOR_FAMILIES = ["receptacle", "luminaire", "junction_box", "fire_alarm_device",
                                  "sprinkler_head", "sensor"];

// "auto" names the anchor by where it lands: the centre becomes circle_center
// (round symbols) or visual_center, an edge or ink tip becomes wall_attachment.
function resolveAnchorName(item, snapKey) {
  const chosenName = state.activeAnchorName;
  if (chosenName && chosenName !== "auto") return chosenName;
  if (snapKey === "center" || snapKey == null) {
    return CIRCULAR_ANCHOR_FAMILIES.includes(item.symbol_family) ? "circle_center" : "visual_center";
  }
  return "wall_attachment";
}

function anchorTargetItem() {
  if (!state.anchorTargetId || !state.doc) return null;
  return state.doc.instances.find(x => x.instance_id === state.anchorTargetId) || null;
}

// The box an anchor click attaches to: the box the pointer is inside or
// closest to (within a margin that grows with box size), preferring the
// selected box on ties and the smaller box when boxes nest.
function findAnchorTarget(pt) {
  if (!state.doc || !pt) return null;
  let best = null, bestScore = Infinity;
  for (const item of state.doc.instances) {
    const [x0, y0, x1, y1] = item.bbox_xyxy;
    const size = Math.max(x1 - x0, y1 - y0);
    const margin = Math.max(18 / state.scale, size * 0.35);
    const dx = Math.max(x0 - pt[0], 0, pt[0] - x1);
    const dy = Math.max(y0 - pt[1], 0, pt[1] - y1);
    const dist = Math.hypot(dx, dy);
    if (dist > margin) continue;
    const area = (x1 - x0) * (y1 - y0);
    const score = dist * 1000 + area * 1e-3 - (item.instance_id === state.selectedId ? 0.5 : 0);
    if (score < bestScore) { bestScore = score; best = item; }
  }
  return best;
}

function updateAnchorSnap(pt) {
  const item = findAnchorTarget(pt);
  state.anchorTargetId = item ? item.instance_id : null;
  if (!item || !pt) {
    state.activeAnchorSnap = null;
    return;
  }
  const snapDist = 16 / state.scale;
  let closestSnap = null;
  let minD = snapDist;

  // 1. Check box snap points: Center, Top, Bottom, Left, Right
  const boxPoints = getBoxSnapPoints(item);
  for (const sp of boxPoints) {
    const d = Math.hypot(pt[0] - sp.xy[0], pt[1] - sp.xy[1]);
    if (d < minD) {
      minD = d;
      closestSnap = { xy: [...sp.xy], label: sp.name, kind: "box", key: sp.key };
    }
  }

  // 2. Check feature tip snap if not directly locked onto a box snap point
  if (!closestSnap || minD > 8 / state.scale) {
    const tip = findFeatureTipInArea(pt, item, 22 / state.scale);
    if (tip) {
      const dTip = Math.hypot(pt[0] - tip[0], pt[1] - tip[1]);
      if (dTip < minD) {
        minD = dTip;
        closestSnap = { xy: tip.xy || tip, rays: tip.rays, label: "Feature Tip", kind: "tip" };
      }
    }
  }

  state.activeAnchorSnap = closestSnap;
}

function pointerMove(event) {
  const xy = coordinate(event), pt = screenToImage(...xy, viewport());
  state.pointerScreen = xy;
  state.pointerImg = pt;
  if (state.doc) {
    $("cursorCoords").textContent = `x:${pt[0].toFixed(0)} y:${pt[1].toFixed(0)}`;
  }
  if (!state.drag) {
    if (state.mode === "anchor") {
      updateAnchorSnap(pt);
    } else {
      state.activeAnchorSnap = null;
    }
    if (state.mode === "draw" || state.mode === "oriented" || state.mode === "anchor" ||
        state.mode === "snap" || state.orientedDraft) {
      draw();
    }
    return;
  }
  const d = state.drag;
  if (d.type === "pan") {
    state.x = d.x + xy[0] - d.at[0];
    state.y = d.y + xy[1] - d.at[1];
  } else if (d.type === "marquee") {
    d.current = pt;
  } else if (d.type === "draw" || d.type === "oriented") {
    d.current = pt;
  } else if (d.type === "oriented-axis") {
    let cur = [clamp(pt[0], 0, state.doc.image_width), clamp(pt[1], 0, state.doc.image_height)];
    const dx = cur[0] - d.start[0], dy = cur[1] - d.start[1];
    const dist = Math.hypot(dx, dy);
    let rawAng = Math.atan2(dy, dx) * 180 / Math.PI;
    let snappedAng = rawAng;
    if (event.shiftKey) {
      snappedAng = Math.round(rawAng / 15) * 15;
    } else {
      snappedAng = snapAngleToWallsOrCardinals(rawAng, d.start);
    }
    const rad = snappedAng * Math.PI / 180;
    d.current = [
      clamp(d.start[0] + Math.cos(rad) * dist, 0, state.doc.image_width),
      clamp(d.start[1] + Math.sin(rad) * dist, 0, state.doc.image_height),
    ];
  } else if (d.type === "snap") {
    let cur = [clamp(pt[0], 0, state.doc.image_width), clamp(pt[1], 0, state.doc.image_height)];
    if (event.shiftKey) {
      const dx = cur[0] - d.start[0], dy = cur[1] - d.start[1];
      const dist = Math.hypot(dx, dy);
      const snappedAng = Math.round((Math.atan2(dy, dx) * 180 / Math.PI) / 15) * 15 * Math.PI / 180;
      cur = [
        clamp(d.start[0] + Math.cos(snappedAng) * dist, 0, state.doc.image_width),
        clamp(d.start[1] + Math.sin(snappedAng) * dist, 0, state.doc.image_height),
      ];
    }
    d.current = cur;
  } else {
    const item = state.doc.instances.find(x => x.instance_id === d.selected);
    if (!item) return;
    if (d.type === "rotate") {
      const { cx, cy, w, h } = d.rotBase;
      const rawDeg = Math.atan2(pt[1] - cy, pt[0] - cx) * 180 / Math.PI + 90;
      const snapped = event.shiftKey ? Math.round(rawDeg / 15) * 15 : Math.round(rawDeg * 2) / 2;
      const angle = ((snapped + 180) % 360 + 360) % 360 - 180;
      const box = rotatedAabb(cx, cy, w, h, angle);
      if (checkBox(box)) {
        item.bbox_xyxy = box.map(v => Math.round(v * 1000) / 1000);
        item.rotation_degrees = angle;
        if (Math.abs(angle) < 1e-6) delete item.bbox_rotated;
        else item.bbox_rotated = { cx, cy, w, h, angle_deg: angle };
        d.moved = true;
      }
      draw();
      return;
    }
    if (d.type.startsWith("rot-")) {
      const rot = d.rotBase;
      const a = rot.angle_deg * Math.PI / 180;
      const c = Math.cos(a), s = Math.sin(a);
      const dx = pt[0] - rot.cx, dy = pt[1] - rot.cy;
      const lx = dx * c + dy * s;
      const ly = -dx * s + dy * c;
      const newW = Math.max(4, Math.abs(lx) * 2);
      const newH = Math.max(4, Math.abs(ly) * 2);
      const nextRot = { cx: rot.cx, cy: rot.cy, w: Math.round(newW * 1000) / 1000, h: Math.round(newH * 1000) / 1000, angle_deg: rot.angle_deg };
      const box = rotatedAabb(nextRot.cx, nextRot.cy, nextRot.w, nextRot.h, nextRot.angle_deg);
      if (checkBox(box)) {
        item.bbox_rotated = nextRot;
        item.bbox_xyxy = box.map(v => Math.round(v * 1000) / 1000);
        d.moved = true;
      }
      draw();
      return;
    }
    const dx = pt[0] - d.start[0], dy = pt[1] - d.start[1];
    let box = [...d.box];
    if (d.type === "move") {
      if (dx || dy) d.moved = true;
      if (d.selectedIds && d.selectedIds.length > 1) {
        for (const id of d.selectedIds) {
          const it = state.doc.instances.find(x => x.instance_id === id);
          const origBox = d.boxes?.[id];
          if (!it || !origBox) continue;
          const tx = clamp(dx, -origBox[0], state.doc.image_width - origBox[2]);
          const ty = clamp(dy, -origBox[1], state.doc.image_height - origBox[3]);
          it.bbox_xyxy = [origBox[0] + tx, origBox[1] + ty, origBox[2] + tx, origBox[3] + ty];
          if (it.bbox_rotated && d.originalRotations?.[id]) {
            it.bbox_rotated.cx = d.originalRotations[id].cx + tx;
            it.bbox_rotated.cy = d.originalRotations[id].cy + ty;
          }
        }
      } else {
        const tx = clamp(dx, -box[0], state.doc.image_width - box[2]);
        const ty = clamp(dy, -box[1], state.doc.image_height - box[3]);
        box = [box[0] + tx, box[1] + ty, box[2] + tx, box[3] + ty];
        if (item.bbox_rotated && d.originalRotation) {
          item.bbox_rotated.cx = d.originalRotation.cx + tx;
          item.bbox_rotated.cy = d.originalRotation.cy + ty;
        }
        item.bbox_xyxy = box;
      }
    } else {
      if (d.type.includes("w")) box[0] = clamp(d.box[0] + dx, 0, box[2] - 2);
      if (d.type.includes("e")) box[2] = clamp(d.box[2] + dx, box[0] + 2, state.doc.image_width);
      if (d.type.includes("n")) box[1] = clamp(d.box[1] + dy, 0, box[3] - 2);
      if (d.type.includes("s")) box[3] = clamp(d.box[3] + dy, box[1] + 2, state.doc.image_height);
      delete item.bbox_rotated;
      item.rotation_degrees = 0;
      d.moved = true;
      item.bbox_xyxy = box;
    }
  }
  draw();
}

function pointerUp(event) {
  const d = state.drag;
  if (!d) return;
  if (elements.canvas.hasPointerCapture(event.pointerId)) {
    elements.canvas.releasePointerCapture(event.pointerId);
  }
  state.drag = null;
  if (d.type === "marquee") {
    const box = normalizedBox(d.start, d.current, state.doc.image_width, state.doc.image_height);
    const w = box[2] - box[0], h = box[3] - box[1];
    if (w >= 3 && h >= 3) {
      const hits = state.doc.instances.filter(it =>
        !(it.bbox_xyxy[2] < box[0] || it.bbox_xyxy[0] > box[2] ||
          it.bbox_xyxy[3] < box[1] || it.bbox_xyxy[1] > box[3])
      );
      if (hits.length) {
        selectMultiple(hits.map(x => x.instance_id));
        notify(`Selected ${hits.length} symbol${hits.length > 1 ? "s" : ""}. Press Del to delete, A to verify, X to reject, G to snap.`, "saved");
      } else {
        select(null);
      }
    }
  } else if (d.type === "draw") {
    let box = normalizedBox(d.start, d.current, state.doc.image_width, state.doc.image_height);
    const bw = box[2] - box[0], bh = box[3] - box[1];
    if (bw < 3 || bh < 3) {
      const cx = (d.start[0] + d.current[0]) / 2;
      const cy = (d.start[1] + d.current[1]) / 2;
      box = normalizedBox([cx - 14, cy - 14], [cx + 14, cy + 14], state.doc.image_width, state.doc.image_height);
    }
    if (checkBox(box)) {
      remember();
      newInstance(box);
    }
  } else if (d.type === "oriented") {
    const rot = computeOrientedFromDrag(d.start, d.current);
    const aabb = rotatedAabb(rot.cx, rot.cy, rot.w, rot.h, rot.angle_deg);
    if (checkBox(aabb)) {
      remember();
      newInstance(aabb, rot);
    } else {
      const clampedBox = [
        clamp(aabb[0], 0, state.doc.image_width - 4),
        clamp(aabb[1], 0, state.doc.image_height - 4),
        clamp(aabb[2], 4, state.doc.image_width),
        clamp(aabb[3], 4, state.doc.image_height),
      ];
      if (checkBox(clampedBox)) {
        remember();
        newInstance(clampedBox, rot);
      } else {
        notify("Box extends outside page bounds.", "warning");
      }
    }
  } else if (d.type === "oriented-axis") {
    const rot = computeOrientedFromDrag(d.start, d.current);
    const aabb = rotatedAabb(rot.cx, rot.cy, rot.w, rot.h, rot.angle_deg);
    if (checkBox(aabb)) {
      remember();
      newInstance(aabb, rot);
    }
  } else if (d.type === "snap") {
    const dx = d.current[0] - d.start[0];
    const dy = d.current[1] - d.start[1];
    const len = Math.hypot(dx, dy);
    if (len >= 2) {
      modifySelection(x => {
        x.snap_vector = {
          origin: [Math.round(d.start[0] * 1000) / 1000, Math.round(d.start[1] * 1000) / 1000],
          direction: [Math.round((dx / len) * 10000) / 10000, Math.round((dy / len) * 10000) / 10000],
        };
      });
      notify("Extra snap vector updated.", "saved");
      setMode("select");
    } else {
      notify("Drag further to define a snap vector direction.", "warning");
    }
  } else if (d.type !== "pan") {
    if (d.moved) {
      if (d.selectedIds && d.selectedIds.length > 1) {
        for (const id of d.selectedIds) {
          const edited = state.doc.instances.find(x => x.instance_id === id);
          if (edited) invalidateReviewOnChange(edited, "");
        }
      } else {
        const edited = state.doc.instances.find(x => x.instance_id === d.selected);
        if (edited) invalidateReviewOnChange(edited, d.reviewEvidence);
      }
      editState();
      refresh();
    } else {
      state.undo.pop();
    }
  }
  draw();
}

function cancelDrag() {
  state.orientedDraft = null;
  const drag = state.drag;
  if (!drag) { refreshControls(); draw(); return; }
  if (drag.type === "pan" || drag.type === "marquee") {
    if (drag.type === "pan") {
      state.x = drag.x; state.y = drag.y;
    }
  } else if (drag.type !== "draw" && drag.type !== "oriented" && drag.type !== "snap" && drag.type !== "oriented-axis") {
    if (drag.selectedIds && drag.selectedIds.length > 1) {
      for (const id of drag.selectedIds) {
        const item = state.doc.instances.find(x => x.instance_id === id);
        if (item && drag.boxes?.[id]) {
          item.bbox_xyxy = [...drag.boxes[id]];
          if (drag.originalRotations?.[id]) {
            item.bbox_rotated = { ...drag.originalRotations[id] };
            item.rotation_degrees = drag.originalRotations[id].angle_deg;
          } else {
            delete item.bbox_rotated;
            item.rotation_degrees = 0;
          }
        }
      }
    } else {
      const item = state.doc.instances.find(x => x.instance_id === drag.selected);
      if (item) {
        item.bbox_xyxy = [...drag.box];
        if (drag.originalRotation) {
          item.bbox_rotated = { ...drag.originalRotation };
          item.rotation_degrees = drag.originalRotation.angle_deg;
        } else {
          delete item.bbox_rotated;
          item.rotation_degrees = 0;
        }
      }
    }
    state.undo.pop();
  }
  state.drag = null;
  refresh();
}

function updateSelectedFields() {
  if (state.busy) return;
  const item = chosen();
  if (!item) return;
  const occluded = $("occluded").checked, truncated = $("truncated").checked;
  if (occluded === Boolean(item.is_occluded) && truncated === Boolean(item.is_truncated)) return;
  modifySelection(x => {
    x.is_occluded = occluded;
    x.is_truncated = truncated;
  });
}

function setSelectedAngle(angle) {
  if (state.busy) return;
  const item = chosen();
  if (!item) return;
  if (!Number.isFinite(angle) || angle < -360 || angle > 360) {
    notify("Rotation must be between -360 and 360 degrees.", "error");
    fillSelection();
    return;
  }
  const currentAngle = item.bbox_rotated?.angle_deg ?? (item.rotation_degrees || 0);
  if (Math.abs(angle - currentAngle) < 1e-6) return;
  modifySelection(x => {
    const existing = x.bbox_rotated;
    const bb = x.bbox_xyxy;
    const cx = existing?.cx ?? (bb[0] + bb[2]) / 2;
    const cy = existing?.cy ?? (bb[1] + bb[3]) / 2;
    const w = existing?.w ?? bb[2] - bb[0];
    const h = existing?.h ?? bb[3] - bb[1];
    const box = rotatedAabb(cx, cy, w, h, angle);
    if (!checkBox(box)) {
      notify("Rotation would extend outside the drawing.", "error");
      return false;
    }
    x.bbox_xyxy = box.map(v => Math.round(v * 1000) / 1000);
    x.rotation_degrees = angle;
    if (Math.abs(angle) < 1e-6) delete x.bbox_rotated;
    else x.bbox_rotated = { cx, cy, w, h, angle_deg: angle };
  });
}

function updateAngle() {
  setSelectedAngle(Number($("angle").value));
}

function addTextAttribute() {
  const inp = $("textAttrInput");
  const val = inp.value.trim();
  if (!val) return;
  modifySelection(x => {
    x.attributes_from_text ||= [];
    if (!x.attributes_from_text.includes(val)) {
      x.attributes_from_text.push(val);
    }
  });
  inp.value = "";
}

function addCustomDeviceAttribute() {
  const k = $("customAttrKey").value.trim();
  const raw = $("customAttrVal").value.trim();
  if (!k || !raw) {
    notify("Enter both a custom attribute key and value.", "warning");
    return;
  }
  let val = raw;
  if (raw === "true") val = true;
  else if (raw === "false") val = false;
  else if (!Number.isNaN(Number(raw)) && raw !== "") val = Number(raw);
  modifySelection(x => {
    x.device_attributes ||= {};
    x.device_attributes[k] = val;
  });
  $("customAttrKey").value = "";
  $("customAttrVal").value = "";
}

function anchorAllBoxes() {
  if (state.busy || !state.doc) return;
  const selected = chosenList();
  const targets = selected.length > 1 ? selected : state.doc.instances;
  const todo = targets.filter(x => !(x.anchors?.circle_center || x.anchors?.visual_center));
  if (!todo.length) {
    notify("Every box already has a centre anchor.", "info");
    return;
  }
  remember();
  for (const item of todo) {
    const before = reviewEvidenceKey(item);
    const [cx, cy] = getBoxSnapPoints(item).find(p => p.key === "center").xy;
    const name = CIRCULAR_ANCHOR_FAMILIES.includes(item.symbol_family) ? "circle_center" : "visual_center";
    item.anchors ||= {};
    item.anchors[name] = {
      xy: [Math.round(cx * 1000) / 1000, Math.round(cy * 1000) / 1000],
      visible: true,
      annotated: true,
    };
    invalidateReviewOnChange(item, before);
  }
  editState();
  refresh();
  notify(`Placed centre anchors on ${todo.length} box${todo.length === 1 ? "" : "es"}` +
         (selected.length > 1 ? " in the selection." : " on this page."), "saved");
}

async function snapRefineSelected() {
  const item = chosen();
  if (!item) {
    notify("Select a box first to snap to wall.", "warning");
    return;
  }
  flushPendingInputs();
  remember();

  let snapRes = null;
  try {
    const res = await api("/api/snap?page=" + state.page + docQuery(), {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Labeler-Token": state.token },
      body: JSON.stringify({ instance: JSON.parse(JSON.stringify(item)) }),
    });
    snapRes = res.snap;
  } catch (_) {}

  let nearestWall = null;
  let minDist = 80;
  const ctr = itemCenter(item);
  if (state.walls && state.walls.length) {
    for (let i = 0; i < state.walls.length; i++) {
      const w = state.walls[i];
      const sx = w.start[0], sy = w.start[1], ex = w.end[0], ey = w.end[1];
      const wx = ex - sx, wy = ey - sy;
      const wlen2 = wx * wx + wy * wy;
      if (wlen2 < 1e-6) continue;
      const u = clamp(((ctr[0] - sx) * wx + (ctr[1] - sy) * wy) / wlen2, 0, 1);
      const px = sx + u * wx, py = sy + u * wy;
      const d = Math.hypot(ctr[0] - px, ctr[1] - py);
      if (d < minDist) {
        minDist = d;
        nearestWall = { wall: w, pt: [px, py], dist: d, wx, wy };
      }
    }
  }

  if (nearestWall) {
    const { pt, wx, wy } = nearestWall;
    const wallAng = Math.atan2(wy, wx) * 180 / Math.PI;
    const curAng = item.bbox_rotated?.angle_deg ?? (item.rotation_degrees || 0);
    let bestAng = curAng;
    let minAngDiff = 999;
    for (const offset of [0, 90, 180, -90]) {
      const cand = ((wallAng + offset + 180) % 360 + 360) % 360 - 180;
      const diff = Math.abs(((curAng - cand + 180) % 360 + 360) % 360 - 180);
      if (diff < minAngDiff) {
        minAngDiff = diff;
        bestAng = cand;
      }
    }
    const finalAng = Math.round(bestAng * 10) / 10;

    item.anchors ||= {};
    item.anchors.wall_attachment = {
      xy: [Math.round(pt[0] * 1000) / 1000, Math.round(pt[1] * 1000) / 1000],
      visible: true,
      annotated: true,
    };

    const vdx = pt[0] - ctr[0], vdy = pt[1] - ctr[1];
    const vlen = Math.hypot(vdx, vdy);
    if (vlen > 1e-3) {
      item.snap_vector = {
        origin: [Math.round(ctr[0] * 1000) / 1000, Math.round(ctr[1] * 1000) / 1000],
        direction: [Math.round((vdx / vlen) * 10000) / 10000, Math.round((vdy / vlen) * 10000) / 10000],
      };
    }

    setSelectedAngle(finalAng);
    if (snapRes) {
      item.snap = snapRes;
      state.snapPreview = snapRes;
    }
    editState();
    refresh();
    notify(`Refined: snapped to wall at (${pt[0].toFixed(1)}, ${pt[1].toFixed(1)}), angle ${finalAng}°.`, "saved");
  } else if (snapRes && snapRes.point) {
    item.anchors ||= {};
    item.anchors.wall_attachment = {
      xy: snapRes.point,
      visible: true,
      annotated: true,
    };
    if (snapRes.wall_angle_deg != null) {
      const curAng = item.bbox_rotated?.angle_deg ?? (item.rotation_degrees || 0);
      let bestAng = curAng;
      let minAngDiff = 999;
      for (const offset of [0, 90, 180, -90]) {
        const cand = ((snapRes.wall_angle_deg + offset + 180) % 360 + 360) % 360 - 180;
        const diff = Math.abs(((curAng - cand + 180) % 360 + 360) % 360 - 180);
        if (diff < minAngDiff) {
          minAngDiff = diff;
          bestAng = cand;
        }
      }
      setSelectedAngle(Math.round(bestAng * 10) / 10);
    }
    item.snap = snapRes;
    state.snapPreview = snapRes;
    editState();
    refresh();
    notify(`Wall snap resolved: ${snapRes.kind}.`, "saved");
  } else {
    notify("No wall segment found within 80 px of the box to snap to.", "warning");
  }
}

// Every page request names the PDF this tab is editing, so another tab (or an
// agent) opening a different drawing never redirects this tab's reads or saves.
function docQuery() {
  return state.modeKind === "pdf" && state.currentDocPath
    ? "&doc=" + encodeURIComponent(state.currentDocPath) : "";
}
function isCurrentDoc(doc) {
  return state.currentDocPath ? doc.path === state.currentDocPath : Boolean(doc.is_current);
}

// Pages kept in memory besides unsaved ones. A long drawing set visited page
// by page would otherwise keep every page (with 80 undo snapshots each).
const CLEAN_PAGES_KEPT = 12;

function cachePage(page) {
  state.pageCache[page] = {
    doc: JSON.parse(JSON.stringify(state.doc)),
    dirty: Boolean(state.dirty),
    revision: state.revision,
    proposalsVersion: state.proposalsVersion ?? null,
    undo: [...state.undo],
    redo: [...state.redo],
    selectedIds: new Set(state.selectedIds),
    selectedId: state.selectedId,
    visited: Date.now() + (state.visitSeq = (state.visitSeq || 0) + 1) / 1e6,
  };
  trimPageCache(page);
}

function trimPageCache(keep) {
  const clean = Object.entries(state.pageCache)
    .filter(([p, c]) => !c.dirty && Number(p) !== keep)
    .sort((a, b) => (b[1].visited || 0) - (a[1].visited || 0));
  const keepIsClean = state.pageCache[keep] && !state.pageCache[keep].dirty;
  for (const [p] of clean.slice(CLEAN_PAGES_KEPT - (keepIsClean ? 1 : 0))) delete state.pageCache[p];
}

const MAX_IMAGE_CACHE = 4;

function imageCacheKey(docPath, page) {
  return `${docPath || ""}::${page}`;
}

function clearImageCache() {
  if (state.preloadTimer) { clearTimeout(state.preloadTimer); state.preloadTimer = null; }
  if (state.preloadRetryTimer) { clearTimeout(state.preloadRetryTimer); state.preloadRetryTimer = null; }
  if (state.imageCache instanceof Map) {
    for (const entry of state.imageCache.values()) {
      if (entry?.objectUrl) {
        try { URL.revokeObjectURL(entry.objectUrl); } catch (_) {}
      }
    }
    state.imageCache.clear();
  }
  if (state.imagePreloads instanceof Map) state.imagePreloads.clear();
}

function putImageCache(key, entry) {
  if (!(state.imageCache instanceof Map)) state.imageCache = new Map();
  const prev = state.imageCache.get(key);
  if (prev?.objectUrl && prev.objectUrl !== entry.objectUrl) {
    try { URL.revokeObjectURL(prev.objectUrl); } catch (_) {}
  }
  state.imageCache.delete(key);
  state.imageCache.set(key, entry);
  const currentKey = imageCacheKey(state.currentDocPath, state.page);
  while (state.imageCache.size > MAX_IMAGE_CACHE) {
    let evictKey = null;
    for (const candidate of state.imageCache.keys()) {
      if (candidate !== key && candidate !== currentKey) {
        evictKey = candidate;
        break;
      }
    }
    if (!evictKey) break;
    const evicted = state.imageCache.get(evictKey);
    state.imageCache.delete(evictKey);
    if (evicted?.objectUrl) {
      try { URL.revokeObjectURL(evicted.objectUrl); } catch (_) {}
    }
  }
}

function loadImageElement(url) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = async () => {
      if (typeof img.decode === "function") {
        try { await img.decode(); } catch (_) {}
      }
      resolve(img);
    };
    img.onerror = () => reject(new Error("Could not render source drawing image"));
    img.src = url;
  });
}

async function getPageImage(docPath, page, imageUrl, expectedSha, force) {
  const key = imageCacheKey(docPath, page);
  if (!force && state.imageCache instanceof Map) {
    const cached = state.imageCache.get(key);
    if (cached && (!expectedSha || cached.sha === expectedSha || cached.url === imageUrl)) {
      cached.usedAt = Date.now();
      state.imageCache.delete(key);
      state.imageCache.set(key, cached);
      return cached.img;
    }
    const inflight = state.imagePreloads instanceof Map ? state.imagePreloads.get(key) : null;
    if (inflight) {
      try {
        const entry = await inflight;
        if (entry && typeof entry === "object" && entry.img &&
            (!expectedSha || entry.sha === expectedSha || entry.url === imageUrl)) {
          entry.usedAt = Date.now();
          return entry.img;
        }
      } catch (_) {}
    }
  } else if (force && state.imageCache instanceof Map) {
    const old = state.imageCache.get(key);
    if (old?.objectUrl) {
      try { URL.revokeObjectURL(old.objectUrl); } catch (_) {}
    }
    state.imageCache.delete(key);
  }
  const img = await loadImageElement(imageUrl);
  putImageCache(key, { sha: expectedSha || null, url: imageUrl, img, objectUrl: null, usedAt: Date.now() });
  return img;
}

async function preloadCachedPageImage(docPath, page) {
  if (state.modeKind !== "pdf" || !docPath) return "stop";
  const key = imageCacheKey(docPath, page);
  if (state.imageCache.has(key)) return "cached";
  if (state.imagePreloads.has(key)) {
    const existing = await state.imagePreloads.get(key).catch(() => null);
    if (existing === "pending") return "pending";
    return state.imageCache.has(key) ? "cached" : "failed";
  }
  const qDoc = "&doc=" + encodeURIComponent(docPath);
  const task = (async () => {
    const resp = await fetch(`/api/image?page=${page}${qDoc}&cached=1`, { cache: "no-store" });
    if (resp.status === 202) return "pending";
    if (!resp.ok) return null;
    const sha = resp.headers.get("X-Image-Sha256") || null;
    const blob = await resp.blob();
    if (state.currentDocPath !== docPath) return null;
    const objectUrl = URL.createObjectURL(blob);
    try {
      const img = await loadImageElement(objectUrl);
      if (state.currentDocPath !== docPath) {
        URL.revokeObjectURL(objectUrl);
        return null;
      }
      const entry = { sha, url: null, img, objectUrl, usedAt: Date.now() };
      putImageCache(key, entry);
      return entry;
    } catch (_) {
      URL.revokeObjectURL(objectUrl);
      return null;
    }
  })();
  state.imagePreloads.set(key, task);
  try {
    const res = await task;
    if (res === "pending") return "pending";
    return res ? "loaded" : "failed";
  } finally {
    if (state.imagePreloads.get(key) === task) state.imagePreloads.delete(key);
  }
}

function scheduleImagePreload(page, retryAttempt = 0) {
  if (state.modeKind !== "pdf" || !state.currentDocPath) return;
  if (state.preloadTimer) { clearTimeout(state.preloadTimer); state.preloadTimer = null; }
  if (state.preloadRetryTimer) { clearTimeout(state.preloadRetryTimer); state.preloadRetryTimer = null; }
  const docAtSchedule = state.currentDocPath;
  state.preloadTimer = setTimeout(async () => {
    state.preloadTimer = null;
    if (state.modeKind !== "pdf" || state.currentDocPath !== docAtSchedule || state.page !== page || state.busy) return;
    const candidates = [page + 1, page + 2, page - 1].filter(p => p >= 0 && p < state.pageCount);
    for (const targetPage of candidates) {
      if (state.currentDocPath !== docAtSchedule || state.page !== page) return;
      if (state.imageCache.has(imageCacheKey(docAtSchedule, targetPage))) continue;
      const outcome = await preloadCachedPageImage(docAtSchedule, targetPage);
      if (outcome === "pending") {
        if (retryAttempt < 6 && state.currentDocPath === docAtSchedule && state.page === page) {
          state.preloadRetryTimer = setTimeout(() => {
            state.preloadRetryTimer = null;
            scheduleImagePreload(page, retryAttempt + 1);
          }, 900);
        }
        return;
      }
      if (targetPage === page + 1 && (outcome === "loaded" || outcome === "cached")) {
        const deeper = [page + 3, page + 4].filter(p => p >= 0 && p < state.pageCount);
        if (deeper.length && state.token && state.currentDocPath === docAtSchedule && state.page === page) {
          api("/api/prefetch", {
            method: "POST",
            headers: { "Content-Type": "application/json", "X-Labeler-Token": state.token },
            body: JSON.stringify({ path: docAtSchedule, pages: deeper }),
          }).catch(() => {});
        }
      }
    }
  }, retryAttempt === 0 ? 150 : 0);
}

// Page navigation for long documents: a request made while a page is loading
// is remembered (latest wins) and opened as soon as that load finishes, and
// steps count from the page being opened, so three quick "next" clicks move
// three pages instead of one.
function goToPage(page) {
  if (!Number.isInteger(page)) return;
  const last = Math.max(0, state.pageCount - 1);
  if (page < 0 || page > last) {
    notify(page < 0 ? "Already on the first page." : `Already on the last page (${state.pageCount}).`, "warning");
    return;
  }
  if (state.busy || state.drag) {
    if (state.drag) { notify("Finish drawing or moving the box first.", "warning"); return; }
    state.pendingPage = page;
    notify(`Going to page ${page + 1} / ${state.pageCount}...`);
    refreshControls();
    return;
  }
  if (page === state.page && state.doc) return;
  loadPage(page);
}

// The next page with pending boxes after the one shown (wrapping), from the
// server's overview of saved pages and unopened proposal pages, corrected by
// this tab's unsaved edits.
async function nextReviewPage() {
  if (state.reviewLoading) return;
  const btn = $("nextReviewPage");
  const docAtStart = state.currentDocPath;
  state.reviewLoading = true;
  if (btn) {
    btn.disabled = true;
    btn.textContent = "Finding...";
  }
  notify("Finding next page to review...");
  try {
    let overview;
    try {
      const q = docQuery();
      overview = await api("/api/overview" + (q ? "?" + q.slice(1) : ""));
    } catch (error) {
      if (state.currentDocPath === docAtStart) {
        notify("Could not read the page overview: " + (error.message || error), "error");
      }
      return;
    }
    if (state.currentDocPath !== docAtStart) return;
    const pendingIn = (doc) => (doc?.instances || []).filter(x => x.review_status !== "accepted").length;
    const pending = new Map((overview.pages || []).map(row => [row.page, row.pending]));
    for (const [p, cached] of Object.entries(state.pageCache)) {
      if (cached?.dirty) pending.set(Number(p), pendingIn(cached.doc));
    }
    if (state.doc) pending.set(state.page, pendingIn(state.doc));
    const from = state.pendingPage ?? state.loadingPage ?? state.page;
    const pages = [...pending].filter(([, n]) => n > 0).map(([p]) => p).sort((a, b) => a - b);
    const next = pages.find(p => p > from) ?? pages[0];
    if (next === undefined) {
      notify("No page in this document has pending boxes.", "saved");
    } else if (next === from) {
      notify("Only this page has pending boxes.", "warning");
      if (state.page === from && !state.busy) nextPending(true);
    } else {
      const afterNext = pages.find(p => p > next);
      if (afterNext !== undefined && state.modeKind === "pdf" && state.token) {
        api("/api/prefetch", {
          method: "POST",
          headers: { "Content-Type": "application/json", "X-Labeler-Token": state.token },
          body: JSON.stringify({ path: state.currentDocPath || undefined, pages: [afterNext] }),
        }).catch(() => {});
      }
      notify(`Page ${next + 1}: ${pending.get(next)} pending (${pages.length} page${pages.length === 1 ? "" : "s"} to review).`);
      goToPage(next);
    }
  } finally {
    state.reviewLoading = false;
    if (btn) {
      btn.disabled = false;
      btn.textContent = "Review ›";
    }
  }
}

function stepPage(delta) {
  const base = state.pendingPage ?? state.loadingPage ?? state.page;
  goToPage(base + delta);
}

// "PDF done": progress bookmark only (does not control training). Training
// uses saved pages marked "Every symbol on this page is labeled" or "No target symbols on this page".
async function setTrainingReady(event) {
  const box = event.target;
  const ready = box.checked;
  if (ready && isAnyPageDirty()) {
    box.checked = false;
    notify("Save your changes first (Ctrl+S): training reads saved pages only.", "warning");
    return;
  }
  box.disabled = true;
  try {
    const result = await api("/api/training_ready?" + docQuery().slice(1), {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Labeler-Token": state.token },
      body: JSON.stringify({ ready }),
    });
    box.checked = Boolean(result.training_ready);
    notify(result.training_ready
      ? `PDF marked done (bookmark only): ${result.usable_pages.length} complete/empty page(s) of ${result.saved_pages} saved are eligible for training.`
      : "PDF unmarked (bookmark only; page completeness checkboxes still control training).", result.training_ready && !result.usable_pages.length ? "warning" : "info");
    const doc = (state.documents || []).find(isCurrentDoc);
    if (doc) doc.training_ready = Boolean(result.training_ready);
  } catch (error) {
    box.checked = !ready;
    notify(String(error.message || error), "error");
  } finally {
    box.disabled = state.modeKind !== "pdf";
  }
}

function isAnyPageDirty() {
  return Boolean(state.dirty || Object.values(state.pageCache || {}).some(c => c.dirty));
}

async function evaluateCurrentPage() {
  if (!state.doc || state.busy) return;
  if (state.drag) {
    notify("Finish drawing or moving the box before evaluating.", "warning");
    return;
  }
  if (state.detectorInfo && !state.detectorInfo.available) {
    notify("No trained model available locally.", "warning");
    return;
  }
  const pageAtStart = state.page;
  const docAtStart = state.currentDocPath;
  const confidence = Number($("evalConfidence")?.value || 0.50);
  const evalBtn = $("evaluatePageBtn");
  state.busy = true;
  if (evalBtn) evalBtn.textContent = "Evaluating...";
  refreshControls();
  notify(`Evaluating page ${pageAtStart + 1} across full sheet...`);
  try {
    const res = await api(`/api/evaluate_page?page=${pageAtStart}${docQuery()}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Labeler-Token": state.token },
      body: JSON.stringify({
        confidence,
        confidence_source: state.evalConfidenceManual ? "manual"
          : (state.evalConfidenceRecommended ? "recommended" : "default"),
      }),
    });
    if (!state.doc || state.page !== pageAtStart || state.currentDocPath !== docAtStart) return;
    const existing = state.doc.instances || [];
    const kept = existing.filter(x => !(x.review_source === "model" && x.review_status !== "accepted"));
    const replaced = existing.length - kept.length;
    const rejected = state.doc.rejected_proposals || [];
    const usedIds = new Set([
      ...kept.map(x => String(x.instance_id)),
      ...rejected.map(x => String(x.instance_id)),
    ]);
    const toAdd = [];
    let skippedOverlap = 0;
    let skippedRejected = 0;
    for (const rawItem of (res.instances || [])) {
      const bb = rawItem?.bbox_xyxy;
      if (!Array.isArray(bb) || bb.length !== 4) continue;
      if (rejected.some(r => Array.isArray(r.bbox_xyxy) && boxIoU(bb, r.bbox_xyxy) >= 0.45)) {
        skippedRejected++;
        continue;
      }
      if (kept.some(k => Array.isArray(k.bbox_xyxy) && boxIoU(bb, k.bbox_xyxy) >= 0.45) ||
          toAdd.some(k => boxIoU(bb, k.bbox_xyxy) >= 0.45)) {
        skippedOverlap++;
        continue;
      }
      const item = JSON.parse(JSON.stringify(rawItem));
      let id = String(item.instance_id || "m_det_1");
      if (usedIds.has(id)) {
        let n = 1;
        while (usedIds.has(`${id}_${n}`)) n++;
        id = `${id}_${n}`;
      }
      usedIds.add(id);
      item.instance_id = id;
      toAdd.push(item);
    }
    if (replaced > 0 || toAdd.length > 0) {
      remember();
      state.doc.instances = [...kept, ...toAdd];
      if (state.selectedId && !state.doc.instances.some(x => x.instance_id === state.selectedId)) {
        state.selectedId = null;
      }
      if (state.selectedIds) {
        const validIds = new Set(state.doc.instances.map(x => x.instance_id));
        state.selectedIds = new Set([...state.selectedIds].filter(id => validIds.has(id)));
      }
      if (toAdd.length > 0) {
        state.doc.verified_no_symbols = false;
        state.doc.page_complete = false;
      }
      editState();
      refresh();
    }
    const secs = typeof res.elapsed_ms === "number" ? ` in ${(res.elapsed_ms / 1000).toFixed(1)}s` : "";
    const tilesTxt = res.tiles ? ` across ${res.tiles} tiles` : "";
    const extras = [];
    if (replaced) extras.push(`${replaced} prior model replaced`);
    if (skippedOverlap) extras.push(`${skippedOverlap} overlapped existing`);
    if (skippedRejected) extras.push(`${skippedRejected} skipped rejected`);
    const gate = res.sheet_gate;
    if (gate && gate.action === "skip") extras.push(`sheet ${gate.sheet?.sheet_number || ""} non-electrical: skipped`);
    if (gate && gate.action === "raise") extras.push(`sheet ${gate.sheet?.sheet_number || ""} non-electrical: cutoff ${gate.confidence}`);
    if (res.review_required) extras.push(`${res.review_required} flagged to check`);
    const extraTxt = extras.length ? ` (${extras.join(", ")})` : "";
    notify(
      `Evaluated page ${pageAtStart + 1}${tilesTxt}${secs}: added ${toAdd.length} box${toAdd.length === 1 ? "" : "es"}${extraTxt}. Ctrl+Z to undo · Ctrl+S to save.`,
      toAdd.length ? "saved" : "warning"
    );
  } catch (error) {
    notify("Page evaluation failed: " + (error.message || error), "error");
  } finally {
    if (evalBtn) evalBtn.textContent = "Evaluate page";
    state.busy = false;
    refreshControls();
  }
}

const SESSION_LOST = "local session authorization required";

async function api(path, options = {}, retried = false) {
  const response = await fetch(path, { cache: "no-store", ...options });
  const data = await response.json();
  if (!response.ok) {
    // The server was restarted (new session token) while this tab stayed
    // open: re-attach to it and retry once instead of failing every save.
    if (response.status === 403 && data.error === SESSION_LOST && !retried &&
        options.headers && "X-Labeler-Token" in options.headers) {
      await reattachSession();
      return api(path, { ...options, headers: { ...options.headers, "X-Labeler-Token": state.token } }, true);
    }
    const err = new Error(data.error || "Request failed: HTTP " + response.status);
    err.status = response.status;
    throw err;
  }
  return data;
}

function applyRecommendedEvalConfidence(det) {
  const sel = $("evalConfidence");
  if (!sel || state.evalConfidenceManual) return;
  const rec = det?.recommended_confidence;
  if (typeof rec !== "number" || !Number.isFinite(rec)) return;
  const opts = [...sel.options]
    .map(opt => ({ value: opt.value, num: Number(opt.value) }))
    .filter(opt => Number.isFinite(opt.num));
  if (!opts.length) return;
  let best = opts[0];
  let bestDiff = Math.abs(opts[0].num - rec);
  for (const opt of opts.slice(1)) {
    const diff = Math.abs(opt.num - rec);
    if (diff < bestDiff - 1e-9 || (Math.abs(diff - bestDiff) <= 1e-9 && opt.num > best.num)) {
      best = opt;
      bestDiff = diff;
    }
  }
  sel.value = best.value;
  state.evalConfidenceRecommended = true;
}

async function reattachSession() {
  const fresh = await (await fetch("/api/session", { cache: "no-store" })).json();
  if (!fresh.token) throw new Error(fresh.error || "The labeling server did not accept this browser; reload the page.");
  // Requests name this tab's PDF (?doc=), so a restarted server that opened
  // another start-up PDF still reads and saves the right pages.
  state.token = fresh.token;
  if ("detector" in fresh) {
    state.detectorInfo = fresh.detector || null;
    applyRecommendedEvalConfidence(state.detectorInfo);
  }
  notify("Reconnected to the restarted labeling server.", "");
}

async function loadPage(page, force = false) {
  if (state.busy || state.drag || page < 0 || (page >= state.pageCount && state.doc)) {
    state.lastLoadError = state.busy || state.drag ? "the editor is busy; try again" : "no such page";
    return false;
  }
  if (state.preloadTimer) { clearTimeout(state.preloadTimer); state.preloadTimer = null; }
  if (state.preloadRetryTimer) { clearTimeout(state.preloadRetryTimer); state.preloadRetryTimer = null; }
  // force means "take this page from disk". Never seed the cache with the
  // in-memory copy of that same page, and never read it back afterwards:
  // otherwise "Reload from disk" returns the stale revision that caused the
  // save conflict and the conflict can never be cleared.
  const reloadingCurrent = force && page === state.page;
  if (state.doc && !reloadingCurrent) cachePage(state.page);
  state.busy = true;
  state.loadingPage = page;
  if (state.pendingPage === page) state.pendingPage = null;
  refreshControls();
  if (state.doc) notify(`Loading page ${page + 1} / ${state.pageCount}...`);
  const docAtStart = state.currentDocPath;
  try {
    const result = await api("/api/page?page=" + page + docQuery());
    const docPath = result.current_doc_path || state.currentDocPath || "";
    const img = await getPageImage(docPath, page, result.image_url, result.document?.image_sha256 || null, force);
    if (img.naturalWidth !== result.document.image_width ||
        img.naturalHeight !== result.document.image_height) {
      throw new Error("Image dimensions do not match annotation metadata");
    }
    state.page = page;
    state.pageCount = result.page_count;
    state.image = img;
    state.familyDisciplines = result.family_disciplines || {};
    state.variantsByFamily = result.variants_by_family || {};
    state.attributeSchema = result.attribute_schema || state.attributeSchema;
    state.modeKind = result.mode || "pdf";
    state.jsonFilename = result.json_filename || "";
    state.jsonPath = result.json_path || "";
    state.currentDocName = result.current_doc_name || state.currentDocName;
    state.currentDocPath = result.current_doc_path || state.currentDocPath;
    if ($("docSelect") && state.currentDocPath) $("docSelect").value = state.currentDocPath;
    state.siblingFiles = result.sibling_files || [];
    state.walls = Array.isArray(result.walls) ? result.walls : [];
    const trainingBox = $("trainingReady");
    if (trainingBox) {
      trainingBox.checked = Boolean(result.training_ready);
      trainingBox.disabled = state.modeKind !== "pdf";
    }

    let cached = force ? null : state.pageCache[page];
    if (force) delete state.pageCache[page];
    // The page was saved elsewhere (an agent, another tab) since this tab kept
    // it: a copy without edits is simply replaced by the new one; edits are
    // kept, and the conflict is shown now instead of at the next save.
    // An unsaved page changes when its machine proposals do (an edited
    // DEVICES.json), so that version is compared too.
    const changedOnDisk = Boolean(cached) && (cached.revision !== result.revision ||
      (cached.proposalsVersion ?? null) !== (result.proposals_version ?? null));
    state.proposalsVersion = result.proposals_version ?? null;
    if (changedOnDisk && !cached.dirty) {
      delete state.pageCache[page];
      cached = null;
    }
    if (cached) {
      state.doc = JSON.parse(JSON.stringify(cached.doc));
      state.dirty = Boolean(cached.dirty);
      state.revision = cached.revision;
      state.undo = [...cached.undo];
      state.redo = [...cached.redo];
      state.selectedIds = new Set(cached.selectedIds || []);
      state.selectedId = cached.selectedId || null;
    } else {
      state.doc = result.document;
      state.dirty = false;
      state.revision = result.revision;
      state.undo = [];
      state.redo = [];
      state.selectedIds = new Set();
      state.selectedId = null;
    }

    state.snapPreview = null;
    state.orientedDraft = null;
    $("search").value = "";
    $("filter").value = "all";
    updateDirtyIndicator();
    $("conflictBanner").hidden = true;
    if (changedOnDisk && cached) {
      state.conflictPage = page;
      $("conflictText").textContent =
        `Page ${page + 1} changed on disk since you edited it. Reload it (your edits here are lost) or save to see the conflict.`;
      $("conflictBanner").hidden = false;
    }
    // A page kept in memory already holds this tab's edits (the draft is a
    // copy of them); only a page loaded from disk can have a draft to restore.
    if (cached) $("draftBanner").hidden = true;
    else checkLocalDraft();
    fit();
    refresh();
    notify(`Loaded ${state.jsonFilename || ("page " + (page + 1))}.`);
    prefetchAhead();
    scheduleImagePreload(page);
    return true;
  } catch (error) {
    state.lastLoadError = String(error.message || error);
    notify(state.lastLoadError, "error");
    state.pendingPage = null;          // do not run on into more failing loads
    return false;
  } finally {
    state.busy = false;
    state.loadingPage = null;
    refreshControls();
    const next = state.pendingPage;
    if (next !== null && state.currentDocPath === docAtStart) {
      state.pendingPage = null;
      if (next !== state.page) setTimeout(() => goToPage(next), 0);
    } else if (state.currentDocPath !== docAtStart) {
      state.pendingPage = null;
    }
  }
}

async function loadDocumentsList() {
  const select = $("docSelect");
  try {
    const res = await api("/api/documents");
    const docs = res.documents || [];
    state.documents = docs;
    if (state.doc) prefetchAhead();
    if (select) {
      select.replaceChildren();
      if (!docs.length) {
        const opt = document.createElement("option");
        opt.textContent = "No blueprints found";
        select.appendChild(opt);
      } else {
        for (const doc of docs) {
          const opt = document.createElement("option");
          opt.value = doc.path;
          let badge = "• ";
          if (doc.training_ready) badge = "✔ [Done] ";
          else if (doc.has_annotations) badge = "★ [Annotated] ";
          else if (doc.has_proposals) badge = "◆ [Proposals] ";
          opt.textContent = `${badge}${doc.name} (${doc.rel_path})`;
          if (isCurrentDoc(doc)) {
            opt.selected = true;
          }
          select.appendChild(opt);
        }
      }
    }
    if ($("pdfMenuScreen") && !$("pdfMenuScreen").hidden) {
      renderPdfGrid();
    }
  } catch (err) {
    notify("Could not list documents: " + (err.message || err), "error");
  }
}

function catalogDevices() {
  return Array.isArray(state.deviceCatalog?.devices) ? state.deviceCatalog.devices : [];
}

function catalogCategories() {
  return Array.isArray(state.deviceCatalog?.categories) ? state.deviceCatalog.categories : [];
}

function catalogEntryFor(family, variant) {
  return catalogDevices().find(d => d.symbol_family === family && d.symbol_variant === variant) || null;
}

function deviceMatchesQuery(dev, query) {
  const q = (query || "").trim().toLowerCase();
  if (!q) return true;
  const tokens = q.split(/\s+/).filter(Boolean);
  const sample = Array.isArray(dev.sample_legend_texts) ? dev.sample_legend_texts.join(" ") : "";
  const hay = `${dev.label} ${dev.category} ${dev.symbol_family} ${dev.symbol_variant} ${sample}`.toLowerCase();
  return tokens.every(tok => hay.includes(tok));
}

function filteredCatalogDevices(category, query) {
  return catalogDevices().filter(d => {
    if (category && category !== "all" && d.category !== category) return false;
    return deviceMatchesQuery(d, query);
  });
}

function activeTargetPair() {
  const item = chosen();
  if (item) return [item.symbol_family, item.symbol_variant || "unspecified"];
  const fam = state.lastFamily || "receptacle";
  return [fam, state.lastVariantByFamily[fam] || "unspecified"];
}

function populateDeviceCategorySelect() {
  const sel = $("deviceCategorySelect");
  if (!sel) return;
  const allDevs = catalogDevices();
  const counts = {};
  for (const d of allDevs) {
    counts[d.category] = (counts[d.category] || 0) + 1;
  }
  sel.replaceChildren();
  const allOpt = document.createElement("option");
  allOpt.value = "all";
  allOpt.textContent = `All categories (${allDevs.length})`;
  sel.append(allOpt);
  for (const cat of catalogCategories()) {
    const opt = document.createElement("option");
    opt.value = cat;
    opt.textContent = `${cat} (${counts[cat] || 0})`;
    sel.append(opt);
  }
  sel.value = state.deviceCatalogCategory || "all";
}

function updateActiveDevicePill() {
  const pill = $("activeDevicePill");
  if (!pill) return;
  const [fam, varName] = activeTargetPair();
  const entry = catalogEntryFor(fam, varName);
  pill.textContent = entry ? entry.label : `${fam} / ${varName}`;
  pill.title = `${fam} / ${varName}`;
}

function applyCatalogDevice(dev) {
  if (!dev) return;
  const fam = dev.symbol_family;
  const varName = dev.symbol_variant || "unspecified";
  state.lastFamily = fam;
  state.lastVariantByFamily[fam] = varName;
  const list = chosenList();
  if (!list.length) {
    updateActiveDevicePill();
    renderInlineDeviceCatalog();
    notify(`Next drawn box set to ${dev.label} (${fam} / ${varName}).`, "saved");
    return;
  }
  flushPendingInputs();
  remember();
  for (const item of list) {
    const before = reviewEvidenceKey(item);
    item.symbol_family = fam;
    item.symbol_variant = varName;
    item.discipline = disciplineFor(fam);
    item.device_type = fam;
    invalidateReviewOnChange(item, before);
  }
  editState();
  refresh();
  notify(`Set ${list.length} symbol${list.length > 1 ? "s" : ""} to ${dev.label} (${fam} / ${varName}).`, "saved");
}

function renderInlineDeviceCatalog() {
  const listEl = $("deviceCatalogList");
  const countEl = $("deviceCatalogCount");
  if (!listEl) return;
  listEl.replaceChildren();

  const allDevs = catalogDevices();
  const filtered = filteredCatalogDevices(state.deviceCatalogCategory, state.deviceCatalogQuery);
  if (countEl) {
    countEl.textContent = filtered.length === allDevs.length
      ? String(allDevs.length)
      : `${filtered.length}/${allDevs.length}`;
  }
  if (!filtered.length) {
    const empty = document.createElement("span");
    empty.className = "small";
    empty.textContent = allDevs.length ? "No matching devices." : "Loading devices...";
    listEl.append(empty);
    return;
  }

  const [activeFam, activeVar] = activeTargetPair();
  const byCat = new Map();
  for (const cat of catalogCategories()) byCat.set(cat, []);
  for (const d of filtered) {
    if (!byCat.has(d.category)) byCat.set(d.category, []);
    byCat.get(d.category).push(d);
  }

  for (const [cat, devs] of byCat.entries()) {
    if (!devs.length) continue;
    if (state.deviceCatalogCategory === "all") {
      const hdr = document.createElement("div");
      hdr.className = "catalog-cat-header";
      hdr.textContent = `${cat} (${devs.length})`;
      listEl.append(hdr);
    }
    for (const dev of devs) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "catalog-device-btn";
      btn.textContent = dev.label;
      btn.dataset.family = dev.symbol_family;
      btn.dataset.variant = dev.symbol_variant;
      const sample = Array.isArray(dev.sample_legend_texts) && dev.sample_legend_texts.length
        ? `\nLegend examples: ${dev.sample_legend_texts.join(" | ")}`
        : "";
      btn.title = `${dev.category} · ${dev.symbol_family} / ${dev.symbol_variant} (${dev.source_count} PDFs)${sample}`;
      btn.setAttribute("aria-pressed", String(activeFam === dev.symbol_family && activeVar === dev.symbol_variant));
      btn.addEventListener("click", () => applyCatalogDevice(dev));
      listEl.append(btn);
    }
  }
}

function openDeviceCatalogModal() {
  const modal = $("deviceCatalogModal");
  if (!modal) return;
  modal.hidden = false;
  if ($("deviceModalSearch")) {
    $("deviceModalSearch").value = state.deviceModalQuery || "";
    $("deviceModalSearch").focus();
  }
  renderDeviceCatalogModal();
}

function closeDeviceCatalogModal() {
  const modal = $("deviceCatalogModal");
  if (modal) modal.hidden = true;
}

function renderDeviceCatalogModal() {
  const grid = $("deviceModalGrid");
  const pillsEl = $("deviceModalCategoryPills");
  const countEl = $("deviceModalCount");
  if (!grid || !pillsEl) return;

  const allDevs = catalogDevices();
  const cats = catalogCategories();
  const counts = {};
  for (const d of allDevs) counts[d.category] = (counts[d.category] || 0) + 1;

  pillsEl.replaceChildren();
  const makePill = (val, labelText) => {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "chip";
    btn.textContent = labelText;
    btn.setAttribute("aria-pressed", String((state.deviceModalCategory || "all") === val));
    btn.addEventListener("click", () => {
      state.deviceModalCategory = val;
      renderDeviceCatalogModal();
    });
    return btn;
  };
  pillsEl.append(makePill("all", `All (${allDevs.length})`));
  for (const cat of cats) {
    pillsEl.append(makePill(cat, `${cat} (${counts[cat] || 0})`));
  }

  const filtered = filteredCatalogDevices(state.deviceModalCategory, state.deviceModalQuery);
  if (countEl) {
    countEl.textContent = filtered.length === allDevs.length
      ? String(allDevs.length)
      : `${filtered.length} / ${allDevs.length}`;
  }

  grid.replaceChildren();
  if (!filtered.length) {
    const empty = document.createElement("div");
    empty.style.cssText = "grid-column: 1 / -1; padding: 30px; text-align: center; color: var(--muted);";
    empty.textContent = "No curated electrical devices match your search.";
    grid.append(empty);
    return;
  }

  const [activeFam, activeVar] = activeTargetPair();
  const byCat = new Map();
  for (const cat of cats) byCat.set(cat, []);
  for (const d of filtered) {
    if (!byCat.has(d.category)) byCat.set(d.category, []);
    byCat.get(d.category).push(d);
  }

  for (const [cat, devs] of byCat.entries()) {
    if (!devs.length) continue;
    const card = document.createElement("div");
    card.className = "device-cat-card";
    const hdr = document.createElement("div");
    hdr.style.cssText = "display:flex;align-items:center;justify-content:space-between;gap:8px;border-bottom:1px solid #243542;padding-bottom:6px;";
    const title = document.createElement("strong");
    title.style.cssText = "font-size:13.5px;color:var(--accent);";
    title.textContent = cat;
    const badge = document.createElement("span");
    badge.className = "pill";
    badge.textContent = `${devs.length}`;
    hdr.append(title, badge);
    card.append(hdr);

    const list = document.createElement("div");
    list.style.cssText = "display:flex;flex-direction:column;gap:5px;";
    for (const dev of devs) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "device-modal-btn";
      btn.setAttribute("aria-pressed", String(activeFam === dev.symbol_family && activeVar === dev.symbol_variant));
      const sample = Array.isArray(dev.sample_legend_texts) && dev.sample_legend_texts.length
        ? `Legend texts: ${dev.sample_legend_texts.join(" | ")}`
        : "";
      btn.title = sample;

      const left = document.createElement("span");
      left.textContent = dev.label;
      const right = document.createElement("span");
      right.className = "device-modal-meta";
      right.textContent = `${dev.symbol_family} / ${dev.symbol_variant} · ${dev.source_count}`;
      btn.append(left, right);
      btn.addEventListener("click", () => {
        applyCatalogDevice(dev);
        closeDeviceCatalogModal();
      });
      list.append(btn);
    }
    card.append(list);
    grid.append(card);
  }
}

function openPdfMenu() {
  const menu = $("pdfMenuScreen");
  if (!menu) return;
  menu.hidden = false;
  if ($("pdfMenuSearch")) {
    $("pdfMenuSearch").value = state.pdfMenuQuery || "";
    $("pdfMenuSearch").focus();
  }
  renderPdfGrid();
}

function closePdfMenu() {
  const menu = $("pdfMenuScreen");
  if (menu) menu.hidden = true;
}

function renderPdfGrid() {
  const grid = $("pdfGrid");
  const totalEl = $("pdfMenuTotal");
  if (!grid) return;
  grid.replaceChildren();

  const query = (state.pdfMenuQuery || "").trim().toLowerCase();
  const filter = state.pdfMenuFilter || "all";

  const filtered = state.documents.filter(doc => {
    if (filter === "proposals" && !doc.has_proposals) return false;
    if (filter === "annotated" && !doc.has_annotations) return false;
    if (!query) return true;
    const hay = `${doc.name} ${doc.stem || ""} ${doc.rel_path || ""} ${doc.path || ""}`.toLowerCase();
    return hay.includes(query);
  });

  if (totalEl) {
    totalEl.textContent = `${filtered.length} / ${state.documents.length}`;
  }

  if (!filtered.length) {
    const empty = document.createElement("div");
    empty.style.cssText = "grid-column: 1 / -1; padding: 30px; text-align: center; color: var(--muted);";
    empty.textContent = "No blueprints match the search criteria.";
    grid.appendChild(empty);
    return;
  }

  for (const doc of filtered) {
    const isCurrent = isCurrentDoc(doc);
    const card = document.createElement("div");
    card.style.cssText = `
      background: ${isCurrent ? "#162836" : "var(--panel)"};
      border: 1px solid ${isCurrent ? "var(--accent)" : "var(--line)"};
      border-radius: 8px;
      padding: 12px;
      display: flex;
      flex-direction: column;
      gap: 8px;
      position: relative;
    `;

    const titleRow = document.createElement("div");
    titleRow.style.cssText = "display:flex;align-items:flex-start;justify-content:space-between;gap:8px;";

    const title = document.createElement("div");
    title.style.cssText = "font-weight:600;font-size:13.5px;color:var(--text);word-break:break-word;";
    title.textContent = doc.name;

    titleRow.appendChild(title);
    if (isCurrent) {
      const activeBadge = document.createElement("span");
      activeBadge.className = "pill";
      activeBadge.style.cssText = "background:#195070;border-color:#56b4db;color:#fff;font-size:11px;flex-shrink:0;";
      activeBadge.textContent = "Current";
      titleRow.appendChild(activeBadge);
    }
    card.appendChild(titleRow);

    const pathText = document.createElement("div");
    pathText.style.cssText = "font-size:11.5px;color:var(--muted);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;";
    pathText.textContent = doc.rel_path || doc.path;
    pathText.title = doc.path;
    card.appendChild(pathText);

    const tagsRow = document.createElement("div");
    tagsRow.style.cssText = "display:flex;align-items:center;gap:6px;flex-wrap:wrap;margin-top:auto;";

    if (doc.training_ready) {
      const tag = document.createElement("span");
      tag.className = "tag accepted";
      tag.textContent = "✔ Done → training";
      tagsRow.appendChild(tag);
    }
    if (doc.has_annotations) {
      const tag = document.createElement("span");
      tag.className = "tag accepted";
      tag.textContent = "★ Annotated";
      tagsRow.appendChild(tag);
    }
    if (doc.has_proposals) {
      const tag = document.createElement("span");
      tag.className = "tag legacy";
      tag.textContent = "◆ Proposals Available";
      tagsRow.appendChild(tag);
    }

    const openBtn = document.createElement("button");
    openBtn.className = isCurrent ? "ghost" : "primary";
    openBtn.style.cssText = "margin-left:auto;font-size:12px;padding:4px 10px;";
    openBtn.textContent = isCurrent ? "Active" : "Open Blueprint";
    openBtn.addEventListener("click", async () => {
      closePdfMenu();
      if (!isCurrent) {
        await switchDocument(doc.path);
      }
    });
    tagsRow.appendChild(openBtn);
    card.appendChild(tagsRow);

    grid.appendChild(card);
  }
}

// "Next PDF" walks the blueprints in a fixed folder/name order. The menu list
// is regrouped (annotated first) as you work, so stepping through it would
// jump back to documents already visited.
function documentsInFolderOrder() {
  return [...(state.documents || [])].sort((a, b) =>
    String(a.rel_path || a.name).localeCompare(String(b.rel_path || b.name), undefined,
                                                { numeric: true, sensitivity: "base" }));
}

// Ask the server to render what is likely opened next (the next pages of
// this PDF first, then the first page of the next PDF) while the user works.
function prefetchAhead() {
  if (state.modeKind !== "pdf" || !state.token) return;
  const post = (body) => api("/api/prefetch", {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Labeler-Token": state.token },
    body: JSON.stringify(body),
  }).catch(() => {});
  const ahead = [state.page + 1, state.page + 2].filter(p => p < state.pageCount);
  if (ahead.length) post({ path: state.currentDocPath || undefined, pages: ahead });
  const ordered = documentsInFolderOrder();
  const cur = ordered.findIndex(isCurrentDoc);
  const next = ordered.length > 1 ? ordered[(cur + 1) % ordered.length] : null;
  if (next && next.path !== state.currentDocPath) post({ path: next.path, pages: [0] });
}

async function nextDocument() {
  if (!state.documents || !state.documents.length) {
    await loadDocumentsList();
  }
  const ordered = documentsInFolderOrder();
  if (!ordered.length) {
    notify("No blueprints found to cycle.", "warning");
    return;
  }
  const curIdx = ordered.findIndex(isCurrentDoc);
  const nextIdx = (curIdx + 1) % ordered.length;
  const nextDoc = ordered[nextIdx];
  if (nextDoc && nextDoc.path !== state.currentDocPath) {
    await switchDocument(nextDoc.path);
    if (state.currentDocPath === nextDoc.path) {
      notify(`Switched to: ${nextDoc.name} (${nextIdx + 1} / ${ordered.length})`, "saved");
    }
  }
}

async function switchDocument(path) {
  if (!path || path === state.currentDocPath) return;
  if (state.busy || state.drag) {
    notify("Finish the current action before switching documents.", "warning");
    return;
  }
  if (isAnyPageDirty()) {
    const saveFirst = await confirmAction(
      "You have unsaved annotations across this PDF. Save all pages before switching documents?",
      { title: "Unsaved annotations", confirmLabel: "Save all pages", cancelLabel: "Discard changes" }
    );
    if (saveFirst) {
      const saved = await saveEntirePdf();
      if (!saved) {
        if ($("docSelect")) $("docSelect").value = state.currentDocPath;
        return;
      }
    }
  }
  const previous = { path: state.currentDocPath, page: state.page };
  state.pendingPage = null;
  notify("Switching document...", "");
  try {
    state.pageCache = {};
    clearImageCache();
    state.currentDocPath = path;
    state.page = 0;
    // loadPage() caches the in-memory document under state.page before it
    // fetches the next one. Drop the previous PDF first, otherwise its labels
    // are written into page 0 and restored as if they belonged to the new PDF.
    state.doc = null;
    state.dirty = false;
    state.revision = null;
    state.undo = [];
    state.redo = [];
    state.selectedIds = new Set();
    state.selectedId = null;
    if (!(await loadPage(0, true))) throw new Error(state.lastLoadError || "the PDF could not be opened");
    loadDocumentsList();          // badges refresh in the background
  } catch (err) {
    // Go back to the drawing this tab had open instead of an empty editor.
    state.currentDocPath = previous.path;
    if (previous.path) await loadPage(previous.page, true);
    if ($("docSelect")) $("docSelect").value = state.currentDocPath;
    notify("Could not switch document: " + (err.message || err), "error");
  }
}

async function switchSiblingFile(filename) {
  if (!filename || filename === state.jsonFilename) return;
  if (state.dirty && !(await confirmAction(
    "Discard unsaved annotations before switching JSON files?",
    { title: "Discard unsaved annotations", confirmLabel: "Discard", cancelLabel: "Keep editing" }
  ))) {
    $("fileSwitcher").value = state.jsonFilename;
    return;
  }
  state.busy = true;
  refreshControls();
  try {
    const res = await api("/api/open", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Labeler-Token": state.token },
      body: JSON.stringify({ filename }),
    });
    state.pageCount = res.page_count;
    state.busy = false;
    await loadPage(res.page_index, true);
  } catch (error) {
    state.busy = false;
    notify("Could not open JSON: " + (error.message || error), "error");
    refreshControls();
  }
}

async function saveEntirePdf() {
  if (state.drag) {
    notify("Finish drawing or moving the box before saving.", "warning");
    return false;
  }
  flushPendingInputs();
  if (!state.doc || state.busy) return true;

  if (state.doc) cachePage(state.page);

  const dirtyPages = [];
  for (const [pStr, c] of Object.entries(state.pageCache)) {
    if (c.dirty) dirtyPages.push(Number(pStr));
  }
  if (!dirtyPages.includes(state.page) && (state.dirty || !dirtyPages.length)) {
    dirtyPages.push(state.page);
  }

  state.busy = true;
  refreshControls();
  let savedCount = 0;
  let totalSavedSymbols = 0;
  let conflictPage = null;
  try {
    for (const p of dirtyPages) {
      const cached = state.pageCache[p];
      const docToSave = (p === state.page) ? state.doc : (cached ? cached.doc : state.doc);
      const revToSave = (p === state.page) ? state.revision : (cached ? cached.revision : state.revision);
      const metadata = {
        verified_no_symbols: docToSave.verified_no_symbols === true,
        page_complete: docToSave.page_complete === true &&
          docToSave.instances.every(x => x.review_status === "accepted"),
      };
      let result;
      try {
        result = await api("/api/save?page=" + p + docQuery(), {
          method: "POST",
          headers: { "Content-Type": "application/json", "X-Labeler-Token": state.token },
          body: JSON.stringify({
            revision: revToSave,
            instances: docToSave.instances,
            rejected_proposals: docToSave.rejected_proposals || [],
            metadata,
          }),
        });
      } catch (error) {
        // One stale page must not block every other page. Keep going, then
        // name the conflicting page so it can be reloaded and re-applied.
        if (error.status !== 409) throw error;
        conflictPage = p;
        continue;
      }
      if (cached) {
        cached.revision = result.revision;
        cached.dirty = false;
        if (result.document) cached.doc = result.document;
      }
      if (p === state.page) {
        state.revision = result.revision;
        state.dirty = false;
        if (result.document) state.doc = result.document;
      }
      savedCount++;
      totalSavedSymbols += (docToSave.instances?.length || 0);
    }
    if (conflictPage !== null) {
      state.conflictPage = conflictPage;
      $("conflictBanner").hidden = false;
      $("conflictText").textContent =
        `Page ${conflictPage + 1} changed on disk and was not saved.`;
      if (conflictPage !== state.page) clearLocalDraft();
      updateDirtyIndicator();
      notify(
        `Saved ${savedCount} page${savedCount === 1 ? "" : "s"} (${totalSavedSymbols} symbols); ` +
        `page ${conflictPage + 1} changed on disk. Reload that page, then re-apply your edits.`,
        "warning"
      );
      return false;
    }
    for (const p of dirtyPages) {
      if (state.pageCache[p]) {
        state.pageCache[p].dirty = false;
      }
    }
    state.conflictPage = null;
    savedState();
    refresh();
    notify(`Saved entire PDF: ${savedCount} page${savedCount > 1 ? "s" : ""} saved (${totalSavedSymbols} symbols).`, "saved");
    return true;
  } catch (error) {
    notify("Save failed: " + (error.message || error), "error");
    return false;
  } finally {
    state.busy = false;
    refreshControls();
  }
}
const savePage = saveEntirePdf;

function wireEvents() {
  if ($("navPdfsBtn")) $("navPdfsBtn").addEventListener("click", openPdfMenu);
  if ($("nextPdfBtn")) $("nextPdfBtn").addEventListener("click", nextDocument);
  if ($("openDeviceCatalogBtn")) $("openDeviceCatalogBtn").addEventListener("click", openDeviceCatalogModal);
  if ($("closeDeviceCatalogBtn")) $("closeDeviceCatalogBtn").addEventListener("click", closeDeviceCatalogModal);
  if ($("deviceCatalogSearch")) {
    $("deviceCatalogSearch").addEventListener("input", (e) => {
      state.deviceCatalogQuery = e.target.value;
      renderInlineDeviceCatalog();
    });
    $("deviceCatalogSearch").addEventListener("keydown", (e) => {
      if (e.key === "Enter") {
        e.preventDefault();
        const matches = filteredCatalogDevices(state.deviceCatalogCategory, state.deviceCatalogQuery);
        if (matches.length) applyCatalogDevice(matches[0]);
      } else if (e.key === "Escape") {
        e.preventDefault();
        e.stopPropagation();
        if (state.deviceCatalogQuery) {
          e.target.value = "";
          state.deviceCatalogQuery = "";
          renderInlineDeviceCatalog();
        } else {
          e.target.blur();
        }
      }
    });
  }
  if ($("deviceCategorySelect")) {
    $("deviceCategorySelect").addEventListener("change", (e) => {
      state.deviceCatalogCategory = e.target.value;
      renderInlineDeviceCatalog();
    });
  }
  if ($("deviceModalSearch")) {
    $("deviceModalSearch").addEventListener("input", (e) => {
      state.deviceModalQuery = e.target.value;
      renderDeviceCatalogModal();
    });
    $("deviceModalSearch").addEventListener("keydown", (e) => {
      if (e.key === "Escape") {
        e.preventDefault();
        e.stopPropagation();
        closeDeviceCatalogModal();
      }
    });
  }
  if ($("closePdfMenuBtn")) $("closePdfMenuBtn").addEventListener("click", closePdfMenu);
  if ($("pdfMenuSearch")) $("pdfMenuSearch").addEventListener("input", (e) => {
    state.pdfMenuQuery = e.target.value;
    renderPdfGrid();
  });
  if ($("filterPdfAll")) $("filterPdfAll").addEventListener("click", () => {
    state.pdfMenuFilter = "all";
    $("filterPdfAll").setAttribute("aria-pressed", "true");
    $("filterPdfProposals").setAttribute("aria-pressed", "false");
    $("filterPdfAnnotated").setAttribute("aria-pressed", "false");
    renderPdfGrid();
  });
  if ($("filterPdfProposals")) $("filterPdfProposals").addEventListener("click", () => {
    state.pdfMenuFilter = "proposals";
    $("filterPdfAll").setAttribute("aria-pressed", "false");
    $("filterPdfProposals").setAttribute("aria-pressed", "true");
    $("filterPdfAnnotated").setAttribute("aria-pressed", "false");
    renderPdfGrid();
  });
  if ($("filterPdfAnnotated")) $("filterPdfAnnotated").addEventListener("click", () => {
    state.pdfMenuFilter = "annotated";
    $("filterPdfAll").setAttribute("aria-pressed", "false");
    $("filterPdfProposals").setAttribute("aria-pressed", "false");
    $("filterPdfAnnotated").setAttribute("aria-pressed", "true");
    renderPdfGrid();
  });

  if ($("multiVerifyBtn")) $("multiVerifyBtn").addEventListener("click", verifySelected);
  if ($("multiSnapBtn")) $("multiSnapBtn").addEventListener("click", snapContrastEdgesSelected);
  if ($("multiRejectBtn")) $("multiRejectBtn").addEventListener("click", rejectSelected);
  if ($("multiDeleteBtn")) $("multiDeleteBtn").addEventListener("click", deleteSelected);

  if ($("snapRefineBtn")) $("snapRefineBtn").addEventListener("click", snapContrastEdgesSelected);
  if ($("snapRefineInspectorBtn")) $("snapRefineInspectorBtn").addEventListener("click", snapContrastEdgesSelected);
  if ($("snapSensitivity")) $("snapSensitivity").addEventListener("change", (e) => setSnapSensitivity(e.target.value));

  if ($("docSelect")) $("docSelect").addEventListener("change", (e) => switchDocument(e.target.value));

  $("prevPage").addEventListener("click", () => stepPage(-1));
  $("nextReviewPage").addEventListener("click", nextReviewPage);
  $("trainingReady").addEventListener("change", setTrainingReady);
  $("nextPage").addEventListener("click", () => stepPage(1));
  $("pageJump").addEventListener("keydown", (e) => {
    if (e.key !== "Enter") return;
    e.preventDefault();
    const wanted = Number.parseInt($("pageJump").value, 10);
    if (!Number.isInteger(wanted)) { notify("Type a page number.", "warning"); return; }
    $("pageJump").value = "";
    $("pageJump").blur();
    goToPage(wanted - 1);
  });
  $("fileSwitcher").addEventListener("change", (e) => switchSiblingFile(e.target.value));
  $("selectTool").addEventListener("click", () => setMode("select"));
  $("drawTool").addEventListener("click", () => setMode("draw"));
  $("orientedTool").addEventListener("click", () => setMode("oriented"));
  $("anchorTool").addEventListener("click", () => { state.activeAnchorName = "auto"; setMode("anchor"); });
  $("snapTool").addEventListener("click", () => setMode("snap"));
  $("panTool").addEventListener("click", () => setMode("pan"));
  $("fit").addEventListener("click", fit);
  $("zoomIn").addEventListener("click", () => zoomAt(elements.canvas.clientWidth / 2, elements.canvas.clientHeight / 2, 1.3));
  $("zoomOut").addEventListener("click", () => zoomAt(elements.canvas.clientWidth / 2, elements.canvas.clientHeight / 2, 1 / 1.3));
  $("toggleLabels").addEventListener("click", () => { state.showLabels = !state.showLabels; refreshControls(); draw(); });
  $("toggleWalls").addEventListener("click", () => { state.showWalls = !state.showWalls; refreshControls(); draw(); });
  $("cycleViewFilter").addEventListener("click", () => {
    state.viewFilterIdx = (state.viewFilterIdx + 1) % VIEW_FILTERS.length;
    refreshControls();
    draw();
  });
  $("qualityBadge").addEventListener("click", jumpNextQualityWarning);
  $("undo").addEventListener("click", undo);
  $("redo").addEventListener("click", redo);
  $("save").addEventListener("click", savePage);
  if ($("evaluatePageBtn")) $("evaluatePageBtn").addEventListener("click", evaluateCurrentPage);
  if ($("evalConfidence")) $("evalConfidence").addEventListener("change", () => { state.evalConfidenceManual = true; });
  $("verifiedNoSymbols").addEventListener("change", async (event) => {
    if (!state.doc || state.busy) return;
    const value = event.target.checked;
    if (value && state.doc.instances.length) {
      event.target.checked = false;
      notify("Reject or remove every active proposal before marking this page symbol-free.", "warning");
      return;
    }
    if (value && !(await confirmAction(
      "Have you reviewed the ENTIRE page and confirmed there are no target symbols? This creates negative training labels.",
      { title: "Mark page symbol-free", confirmLabel: "Yes, no symbols on this page" }
    ))) {
      event.target.checked = false;
      return;
    }
    remember();
    state.doc.verified_no_symbols = value;
    editState();
    refresh();
  });
  $("reloadFromDisk").addEventListener("click", async () => {
    const target = Number.isInteger(state.conflictPage) ? state.conflictPage : state.page;
    state.conflictPage = null;
    $("conflictBanner").hidden = true;
    await loadPage(target, true);
  });
  $("confirmOkBtn").addEventListener("click", () => settleConfirm(true));
  $("confirmCancelBtn").addEventListener("click", () => settleConfirm(false));
  $("confirmDialog").addEventListener("cancel", (event) => {
    event.preventDefault();
    settleConfirm(false);
  });
  $("restoreDraftBtn").addEventListener("click", async () => {
    try {
      const parsed = JSON.parse(window.localStorage?.getItem(draftStorageKey()) || "null");
      if (!draftCompatible(parsed)) {
        $("draftBanner").hidden = true;
        notify("Draft cannot be restored: image identity or saved revision differs.", "error");
        return;
      }
      const source = { page: state.page, revision: state.revision, jsonPath: state.jsonPath };
      await api("/api/validate?page=" + source.page + docQuery(), {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Labeler-Token": state.token },
        body: JSON.stringify({
          revision: source.revision, instances: parsed.instances,
          rejected_proposals: parsed.rejected_proposals,
          metadata: {verified_no_symbols: parsed.verified_no_symbols === true},
        }),
      });
      if (state.page !== source.page || state.revision !== source.revision ||
          state.jsonPath !== source.jsonPath || !draftCompatible(parsed)) {
        throw new Error("Active page changed while validating the draft; nothing restored.");
      }
      remember();
      state.doc.instances = parsed.instances;
      state.doc.rejected_proposals = parsed.rejected_proposals;
      state.doc.verified_no_symbols = parsed.verified_no_symbols === true;
      $("draftBanner").hidden = true;
      editState();
      refresh();
      notify("Restored unsaved local draft. Press Save when ready.", "saved");
    } catch (error) {
      notify("Draft restore failed: " + (error.message || error), "error");
    }
  });
  $("dismissDraftBtn").addEventListener("click", clearLocalDraft);

  $("search").addEventListener("input", refreshList);
  $("filter").addEventListener("change", () => { refreshList(); draw(); });
  $("nextPending").addEventListener("click", () => nextPending());
  $("accept").addEventListener("click", verifySelected);
  $("unverify").addEventListener("click", unverifySelected);
  $("reject").addEventListener("click", rejectSelected);
  $("duplicate").addEventListener("click", duplicateSelected);
  $("delete").addEventListener("click", deleteSelected);

  for (const id of ["occluded", "truncated"]) $(id).addEventListener("change", updateSelectedFields);
  $("angle").addEventListener("change", updateAngle);
  for (const btn of document.querySelectorAll(".rot-step")) {
    btn.addEventListener("click", () => {
      const item = chosen();
      if (!item) return;
      if (btn.dataset.abs != null) {
        setSelectedAngle(Number(btn.dataset.abs));
      } else {
        const cur = item.bbox_rotated?.angle_deg ?? (item.rotation_degrees || 0);
        const next = ((cur + Number(btn.dataset.delta) + 180) % 360 + 360) % 360 - 180;
        setSelectedAngle(next);
      }
    });
  }

  $("addTextAttr").addEventListener("click", addTextAttribute);
  $("textAttrInput").addEventListener("keydown", (e) => {
    if (e.key === "Enter") { e.preventDefault(); addTextAttribute(); }
  });
  $("addCustomAttr").addEventListener("click", addCustomDeviceAttribute);
  $("customAttrVal").addEventListener("keydown", (e) => {
    if (e.key === "Enter") { e.preventDefault(); addCustomDeviceAttribute(); }
  });

  $("anchorAllBoxes").addEventListener("click", anchorAllBoxes);
  $("placeAutoAnchor").addEventListener("click", () => { state.activeAnchorName = "auto"; setMode("anchor"); });
  $("helpBtn").addEventListener("click", () => notify($("helpBtn").title, ""));
  $("pageComplete").addEventListener("change", async (event) => {
    if (!state.doc || state.busy) return;
    const value = event.target.checked;
    const pending = state.doc.instances.filter(x => x.review_status !== "accepted").length;
    if (value && pending) {
      event.target.checked = false;
      notify(`Verify or remove the ${pending} pending box${pending > 1 ? "es" : ""} first.`, "warning");
      return;
    }
    if (value && !(await confirmAction(
      "Is EVERY electrical symbol on this page boxed and verified? The page will be used to test the model " +
      "and anything unboxed will count as background.",
      { title: "Page fully labeled", confirmLabel: "Yes, every symbol is labeled" }
    ))) {
      event.target.checked = false;
      return;
    }
    remember();
    state.doc.page_complete = value;
    editState();
    refresh();
  });
  elements.canvas.addEventListener("pointerdown", pointerDown);
  elements.canvas.addEventListener("pointermove", pointerMove);
  elements.canvas.addEventListener("pointerup", pointerUp);
  elements.canvas.addEventListener("pointercancel", cancelDrag);
  elements.canvas.addEventListener("pointerleave", () => {
    state.pointerImg = null;
    if (state.mode === "draw" || state.mode === "oriented" || state.mode === "anchor" || state.mode === "snap") draw();
  });
  elements.canvas.addEventListener("wheel", event => {
    event.preventDefault();
    const [x, y] = coordinate(event);
    zoomAt(x, y, Math.exp(-event.deltaY * 0.002));
  }, { passive: false });
  new ResizeObserver(draw).observe(elements.viewport);

  window.addEventListener("beforeunload", e => {
    if (isAnyPageDirty()) { e.preventDefault(); e.returnValue = ""; }
  });
  window.addEventListener("keyup", event => {
    if (event.code === "Space") state.space = false;
  });
  window.addEventListener("keydown", event => {
    if (event.key === "Escape" && $("deviceCatalogModal") && !$("deviceCatalogModal").hidden) {
      event.preventDefault();
      closeDeviceCatalogModal();
      return;
    }
    const form = event.target.matches?.("input,textarea,select,[contenteditable]");
    if (event.key.toLowerCase() === "s" && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      savePage();
      return;
    }
    if (form) return;
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "z") {
      event.preventDefault(); undo(); return;
    }
    if ((event.ctrlKey || event.metaKey) && (event.key.toLowerCase() === "y" ||
        (event.shiftKey && event.key.toLowerCase() === "z"))) {
      event.preventDefault(); redo(); return;
    }
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "c") {
      event.preventDefault(); copySelected(); return;
    }
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "v") {
      event.preventDefault(); pasteAtCursor(); return;
    }
    if (event.code === "Space") { event.preventDefault(); state.space = true; return; }
    if (event.repeat) return;
    const key = event.key.toLowerCase();
    // Page keys work while a page loads: the request is queued (goToPage).
    if (!event.ctrlKey && !event.metaKey && !event.altKey) {
      if (key === "[") { stepPage(-1); return; }
      if (key === "]") { stepPage(1); return; }
      if (key === "home") { event.preventDefault(); goToPage(0); return; }
      if (key === "n" && event.shiftKey) { nextReviewPage(); return; }
      if (key === "end") { event.preventDefault(); goToPage(state.pageCount - 1); return; }
    }
    if (state.busy) return;
    if (key === "escape") {
      cancelDrag();
      setMode("select");
      return;
    }
    if (event.shiftKey && /^Digit[1-6]$/.test(event.code)) {
      const item = chosen();
      if (item) {
        const subs = subtypesForFamily(item.symbol_family);
        const pick = subs[Number(event.code.slice(-1)) - 1];
        if (pick) applyQuickSubtype(pick);
      }
      return;
    }
    if (!event.shiftKey && /^[1-9]$/.test(key)) {
      applyQuickFamily(QUICK_FAMILIES[Number(key) - 1]);
      return;
    }
    const step = event.shiftKey ? 10 : 1;
    if (key === "arrowleft") { event.preventDefault(); nudgeSelected(-step, 0); return; }
    if (key === "arrowright") { event.preventDefault(); nudgeSelected(step, 0); return; }
    if (key === "arrowup") { event.preventDefault(); nudgeSelected(0, -step); return; }
    if (key === "arrowdown") { event.preventDefault(); nudgeSelected(0, step); return; }
    if (key === "b") setMode("draw");
    else if (key === "o") setMode("oriented");
    else if (key === "v") setMode("select");
    else if (key === "h" || key === "p") setMode("pan");
    else if (key === "k") { state.activeAnchorName = "auto"; setMode("anchor"); }
    else if (key === "c") {
      if ($("deviceCatalogModal")?.hidden) openDeviceCatalogModal();
      else closeDeviceCatalogModal();
    }
    else if (key === "g") {
      if (event.shiftKey) {
        event.preventDefault();
        cycleSnapSensitivity();
      } else {
        snapContrastEdgesSelected();
      }
    }
    else if (key === "m") { state.showLoupe = !state.showLoupe; refreshControls(); draw(); }
    else if (key === "i") { state.viewFilterIdx = (state.viewFilterIdx + 1) % VIEW_FILTERS.length; refreshControls(); draw(); }
    else if (key === "a") verifySelected();
    else if (key === "x") rejectSelected();
    else if (key === "d") duplicateSelected();
    else if (key === "n") nextPending();
    else if (key === "l") { state.showLabels = !state.showLabels; refreshControls(); draw(); }
    else if (key === "delete" || key === "backspace") deleteSelected();
    else if (key === "0") fit();
    else if (key === "+" || key === "=") zoomAt(elements.canvas.clientWidth / 2, elements.canvas.clientHeight / 2, 1.3);
    else if (key === "-") zoomAt(elements.canvas.clientWidth / 2, elements.canvas.clientHeight / 2, 1 / 1.3);
  });
}

async function init() {
  wireEvents();
  try {
    const response = await api("/api/session");
    state.token = response.token;
    state.detectorInfo = response.detector || null;
    applyRecommendedEvalConfidence(state.detectorInfo);
    state.modeKind = response.mode || state.modeKind;
    state.currentDocName = response.current_doc_name || "";
    state.currentDocPath = response.current_doc_path || "";
    state.deviceCatalog = response.device_catalog || state.deviceCatalog;
    populateDeviceCategorySelect();
    updateActiveDevicePill();
    renderInlineDeviceCatalog();
    const docsReady = loadDocumentsList();
    await loadPage(response.initial_page, true);
    await docsReady;
  } catch (error) {
    notify("Could not start editor: " + (error.message || error), "error");
  }
}

if (typeof document !== "undefined") init();
