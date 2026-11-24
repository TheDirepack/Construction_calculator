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
const QUICK_FAMILIES = ["receptacle", "switch", "luminaire", "fire_alarm_device", "data_outlet", "junction_box"];
const VIEW_FILTERS = [
  { name: "Normal", css: "none" },
  { name: "Dim", css: "brightness(0.72) contrast(1.08)" },
  { name: "Invert", css: "invert(0.92) hue-rotate(180deg) contrast(1.1)" },
];
const $ = (id) => document.getElementById(id);

const state = {
  token: null, page: 0, pageCount: 1, revision: null, doc: null,
  image: null, mode: "pan", selectedId: null,
  selectedIds: new Set(),
  pageCache: {},
  scale: 1, x: 0, y: 0, space: false, drag: null, dirty: false,
  undo: [], redo: [], lastFamily: "receptacle", lastVariantByFamily: {}, customVariantsByFamily: {}, busy: false,
  showLabels: true, showLoupe: true, showWalls: true,
  viewFilterIdx: 0,
  clipboard: null,
  qualityWarnings: [],
  warningCursor: 0,
  activeAnchorName: "circle_center",
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
};

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
    if (a.symbol_family === "unknown_symbol" && a.review_status === "accepted") {
      warnings.push({ id: a.instance_id, reason: "Verified box still marked unknown_symbol" });
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
  const [ix, iy] = state.pointerImg;
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
    const item = chosen();
    if (item) {
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
  $("prevPage").disabled = state.page === 0 || state.busy;
  $("nextPage").disabled = state.page >= state.pageCount - 1 || state.busy;
  for (const name of ["pan", "select", "draw", "oriented", "anchor", "snap"]) {
    const el = $(name + "Tool");
    if (el) el.setAttribute("aria-pressed", String(state.mode === name));
  }
  $("undo").disabled = state.undo.length === 0;
  $("redo").disabled = state.redo.length === 0;
  $("save").disabled = (!state.dirty && state.revision !== null) || state.busy;
  $("verifiedNoSymbols").checked = state.doc?.verified_no_symbols === true;
  $("toggleLabels").setAttribute("aria-pressed", String(state.showLabels));
  $("toggleLoupe").setAttribute("aria-pressed", String(state.showLoupe));
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
    banner.textContent = `Anchor mode: click to place "${state.activeAnchorName}" (snaps to Center, Edges, or Feature Tip · Esc to exit)`;
  } else if (state.mode === "snap") {
    banner.hidden = false;
    banner.textContent = "Snap vector mode: drag from origin toward wall (Shift snaps 15° · Esc to exit)";
  } else {
    banner.hidden = true;
  }

  const badge = $("fileBadge");
  if (state.jsonFilename) {
    badge.hidden = false;
    badge.textContent = state.jsonFilename;
  } else {
    badge.hidden = true;
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
  return (item.symbol_family + " " + item.symbol_variant + " " +
    item.instance_id + " " + disciplineFor(item.symbol_family) + " " + attrs).toLowerCase();
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
      txt.textContent = item.symbol_family + " / " + item.symbol_variant;
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
    txt.textContent = item.symbol_family + " / " + item.symbol_variant;
    const tag = document.createElement("span");
    if (item.review_status === "accepted") {
      tag.className = "tag accepted";
      tag.textContent = "Verified";
    } else if (item.review_status == null && !item.uncertain) {
      tag.className = "tag legacy";
      tag.textContent = "Existing";
    } else {
      tag.className = "tag";
      tag.textContent = "Pending";
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

function renderQuickSubtypes(item) {
  const bar = $("quickSubtypeBar");
  if (!bar) return;
  bar.replaceChildren();
  const subtypes = subtypesForFamily(item.symbol_family).slice(0, 10);
  subtypes.forEach((sub, idx) => {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "chip";
    btn.setAttribute("aria-pressed", String(item.symbol_variant === sub));
    btn.textContent = (idx < 6 ? `⇧${idx + 1} ` : "") + sub.replace(/_/g, " ");
    btn.title = idx < 6 ? `Hotkey Shift+${idx + 1}: set subtype to ${sub}` : `Set subtype to ${sub}`;
    btn.addEventListener("click", () => applyQuickSubtype(sub));
    bar.append(btn);
  });
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

function updateVariantDatalist(family) {
  const list = $("variants");
  list.replaceChildren();
  for (const v of subtypesForFamily(family)) {
    const opt = document.createElement("option");
    opt.value = v;
    list.append(opt);
  }
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

function renderAnchorsAndSnap(item) {
  const list = $("anchorList");
  list.replaceChildren();
  const anchors = item.anchors || {};
  const entries = Object.entries(anchors);
  if (!entries.length) {
    const p = document.createElement("p");
    p.className = "small";
    p.textContent = "No anchors placed yet.";
    list.append(p);
  } else {
    for (const [name, anchor] of entries) {
      const row = document.createElement("div");
      row.className = "anchor-row";
      const label = document.createElement("span");
      label.textContent = `${name} (${anchor.xy[0].toFixed(1)}, ${anchor.xy[1].toFixed(1)})`;
      const controls = document.createElement("span");
      controls.style.display = "flex";
      controls.style.gap = "6px";
      controls.style.alignItems = "center";

      const visLabel = document.createElement("label");
      visLabel.className = "checkbox";
      const visCb = document.createElement("input");
      visCb.type = "checkbox";
      visCb.checked = anchor.visible !== false;
      visCb.addEventListener("change", () => modifySelection(x => {
        if (x.anchors?.[name]) x.anchors[name].visible = visCb.checked;
      }));
      visLabel.append(visCb, "vis");

      const rm = document.createElement("button");
      rm.type = "button";
      rm.className = "ghost";
      rm.textContent = "×";
      rm.title = "Remove anchor";
      rm.addEventListener("click", () => modifySelection(x => {
        if (x.anchors) {
          delete x.anchors[name];
          if (!Object.keys(x.anchors).length) delete x.anchors;
        }
      }));
      controls.append(visLabel, rm);
      row.append(label, controls);
      list.append(row);
    }
  }

  const svActions = $("snapVectorActions");
  const svInfo = $("snapVectorInfo");
  $("testWallSnap").hidden = !state.walls.length;
  if (item.snap_vector) {
    svActions.hidden = false;
    const o = item.snap_vector.origin, d = item.snap_vector.direction;
    const deg = Math.atan2(d[1], d[0]) * 180 / Math.PI;
    svInfo.textContent = `Snap vector: origin (${o[0].toFixed(1)}, ${o[1].toFixed(1)}) · dir (${d[0].toFixed(3)}, ${d[1].toFixed(3)}) · ${deg.toFixed(1)}°`;
  } else {
    svActions.hidden = !state.walls.length;
    svInfo.textContent = "No extra snap vector set.";
  }

  const sp = $("snapPreviewInfo");
  if (state.snapPreview) {
    sp.textContent = `Snap preview: ${state.snapPreview.kind} at (${state.snapPreview.point[0]}, ${state.snapPreview.point[1]}) [${state.snapPreview.quality}]`;
  } else if (item.snap?.kind) {
    sp.textContent = `Stored snap: ${item.snap.kind}`;
  } else {
    sp.textContent = "";
  }
}

function fillSheetMetadata() {
  if (!state.doc) return;
  $("sheetInfo").textContent =
    `${state.doc.image_width} × ${state.doc.image_height} px · ${state.doc.source_type || "manual"} · ${state.doc.image_path || ""}`;
  $("metaSourceId").value = state.doc.source_id || "";
  $("metaProjectFamilyId").value = state.doc.project_family_id || "";
  $("metaDocumentId").value = state.doc.document_id || "";
  $("metaNotes").value = state.doc.notes || "";
}

function fillSelection() {
  const count = state.selectedIds?.size || (chosen() ? 1 : 0);
  const multiCard = $("multiSelectionCard");
  if (multiCard) {
    if (count > 1) {
      multiCard.hidden = false;
      $("multiSelectionTitle").textContent = `${count} symbols selected`;
    } else {
      multiCard.hidden = true;
    }
  }

  const item = chosen();
  $("emptySelection").hidden = count > 0;
  $("selectionDetails").hidden = count === 0 || count > 1;
  if (!item || count > 1) return;
  $("selectedId").textContent = item.instance_id;
  $("instanceIdInput").value = item.instance_id;
  $("disciplineBadge").textContent = disciplineFor(item.symbol_family);
  $("family").value = item.symbol_family;
  for (const btn of document.querySelectorAll(".quick-family, .multi-quick-family")) {
    btn.setAttribute("aria-pressed", String(btn.dataset.family === item.symbol_family));
  }
  updateVariantDatalist(item.symbol_family);
  renderQuickSubtypes(item);
  $("variant").value = item.symbol_variant;
  ["x0", "y0", "x1", "y1"].forEach((id, i) => $(id).value = item.bbox_xyxy[i].toFixed(1));
  $("angle").value = item.bbox_rotated?.angle_deg ?? (item.rotation_degrees || 0);
  if (item.bbox_rotated) {
    $("orientedDims").hidden = false;
    $("rotW").value = item.bbox_rotated.w.toFixed(1);
    $("rotH").value = item.bbox_rotated.h.toFixed(1);
  } else {
    $("orientedDims").hidden = true;
  }
  $("occluded").checked = Boolean(item.is_occluded);
  $("truncated").checked = Boolean(item.is_truncated);
  $("unverify").hidden = item.review_status !== "accepted";

  const score = typeof item.proposal_score === "number" ? "score " + item.proposal_score.toFixed(3) : "";
  if (item.review_status === "accepted") {
    $("proposalHint").textContent = "Human-verified positive. Page completeness remains unknown.";
  } else if (item.review_status == null && !item.uncertain) {
    $("proposalHint").textContent = "Loaded from existing JSON. Press A to mark human-verified or edit freely.";
  } else {
    $("proposalHint").textContent =
      "Unverified " + (item.review_source || "candidate") + " proposal " + score + ". Inspect before verifying.";
  }

  renderTextAttributes(item);
  renderDeviceAttributes(item);
  renderAnchorsAndSnap(item);

  const alternatives = Array.isArray(item.proposal_alternative_labels)
    ? item.proposal_alternative_labels : [];
  const evidence = Boolean(item.proposal_score != null || item.proposal_neca_symbol_id ||
                           item.proposal_visual_group || alternatives.length);
  $("modelEvidence").hidden = !evidence;
  $("proposalAlternatives").replaceChildren();
  if (evidence) {
    const info = [];
    if (item.proposal_neca_symbol_id) info.push("NECA " + item.proposal_neca_symbol_id);
    if (item.proposal_visual_group) info.push("Group " + item.proposal_visual_group);
    if (typeof item.proposal_score === "number") info.push("Score " + item.proposal_score.toFixed(3));
    $("modelSource").textContent = info.join(" · ");
    for (const alt of alternatives.slice(0, 12)) {
      const family = String(alt?.device_type || "");
      const variant = String(alt?.subtype || "unspecified");
      if (!family) continue;
      const button = document.createElement("button");
      button.textContent = "Use " + family + " / " + variant +
        (typeof alt.score === "number" ? " (" + alt.score.toFixed(2) + ")" : "");
      button.title = "Apply this proposed class without marking the symbol verified";
      button.addEventListener("click", () => modifySelection(x => {
        x.symbol_family = family;
        x.symbol_variant = variant;
        x.discipline = disciplineFor(family);
        x.device_type = family;
        state.lastFamily = family;
      }));
      $("proposalAlternatives").append(button);
    }
  }
}

function refresh() {
  if (!state.doc) return;
  if (state.selectedId && !chosen()) state.selectedId = null;
  refreshControls();
  refreshList();
  fillSelection();
  fillSheetMetadata();
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
  if ((mode === "anchor" || mode === "snap") && !chosen()) {
    notify("Select a box first before placing an anchor or snap vector.", "warning");
    return;
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
  updateSheetMetadata();
}

function verifySelected() {
  flushPendingInputs();
  const list = chosenList();
  if (!list.length) return;
  remember();
  for (const item of list) {
    item.review_status = "accepted";
    item.review_source = "human";
    item.verified_ground_truth = true;
    item.uncertain = false;
  }
  editState();
  refresh();
  notify(`Verified ${list.length} symbol${list.length > 1 ? "s" : ""}.`, "saved");
  nextPending(false);
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
    const item = chosen();
    if (!item) { setMode("select"); return; }
    let ax = clamp(pt[0], 0, state.doc.image_width);
    let ay = clamp(pt[1], 0, state.doc.image_height);
    let snapLabel = "manual";

    if (state.activeAnchorSnap) {
      ax = state.activeAnchorSnap.xy[0];
      ay = state.activeAnchorSnap.xy[1];
      snapLabel = state.activeAnchorSnap.label;
    } else {
      const tip = findFeatureTipInArea(pt, item, 22 / state.scale);
      if (tip) {
        ax = tip[0];
        ay = tip[1];
        snapLabel = "Feature Tip";
      }
    }

    const name = state.activeAnchorName || "circle_center";
    modifySelection(x => {
      x.anchors ||= {};
      x.anchors[name] = {
        xy: [Math.round(ax * 1000) / 1000, Math.round(ay * 1000) / 1000],
        visible: true,
        annotated: true,
      };
    });
    notify(`Placed anchor "${name}" at (${ax.toFixed(1)}, ${ay.toFixed(1)}) [${snapLabel}].`, "saved");
    state.activeAnchorSnap = null;
    setMode("select");
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

function updateAnchorSnap(pt) {
  const item = chosen();
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
      closestSnap = { xy: [...sp.xy], label: sp.name, kind: "box" };
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

  const newId = $("instanceIdInput").value.trim();
  if (!ID_REGEX.test(newId)) {
    notify("Instance ID must be 1–120 alphanumeric/._:- characters.", "error");
    fillSelection();
    return;
  }
  if (newId !== item.instance_id) {
    const allIds = new Set([
      ...state.doc.instances.map(x => x.instance_id),
      ...(state.doc.rejected_proposals || []).map(x => x.instance_id),
    ]);
    if (allIds.has(newId)) {
      notify(`Instance ID "${newId}" is already used on this page.`, "error");
      fillSelection();
      return;
    }
  }

  const box = ["x0", "y0", "x1", "y1"].map(id => Number($(id).value));
  if (!checkBox(box) || box.some(x => !Number.isFinite(x))) {
    notify("Invalid box dimensions; coordinates must stay on the page.", "error");
    fillSelection();
    return;
  }
  const family = $("family").value.trim(), enteredVariant = $("variant").value.trim();
  const variant = family !== item.symbol_family && enteredVariant === item.symbol_variant
    ? subtypeForChangedFamily(family, enteredVariant)
    : enteredVariant;
  if (!family || family.length > 100 || !variant || variant.length > 160) {
    notify("Enter a valid family and variant.", "error");
    fillSelection();
    return;
  }

  const changed =
    newId !== item.instance_id ||
    family !== item.symbol_family ||
    variant !== item.symbol_variant ||
    box.some((v, i) => Math.abs(v - item.bbox_xyxy[i]) > 0.051) ||
    $("occluded").checked !== Boolean(item.is_occluded) ||
    $("truncated").checked !== Boolean(item.is_truncated);
  if (!changed) return;

  modifySelection(x => {
    const changedBox = box.some((v, i) => Math.abs(v - x.bbox_xyxy[i]) > 0.051);
    x.instance_id = newId;
    state.selectedId = newId;
    x.symbol_family = family;
    x.symbol_variant = variant;
    x.discipline = disciplineFor(family);
    x.device_type = family;
    if (changedBox) {
      x.bbox_xyxy = box;
      delete x.bbox_rotated;
      x.rotation_degrees = 0;
    }
    x.is_occluded = $("occluded").checked;
    x.is_truncated = $("truncated").checked;
    state.lastFamily = family;
    state.lastVariantByFamily[family] = variant;
    if (variant !== "unspecified" && !(state.variantsByFamily[family] || []).includes(variant)) {
      state.customVariantsByFamily[family] ||= [];
      if (!state.customVariantsByFamily[family].includes(variant)) state.customVariantsByFamily[family].unshift(variant);
    }
  });
}

function updateOrientedDims() {
  if (state.busy) return;
  const item = chosen();
  if (!item || !item.bbox_rotated) return;
  const w = Number($("rotW").value), h = Number($("rotH").value);
  if (!Number.isFinite(w) || !Number.isFinite(h) || w < 2 || h < 2) {
    notify("Oriented width and height must be at least 2 px.", "error");
    fillSelection();
    return;
  }
  const { cx, cy, angle_deg } = item.bbox_rotated;
  const aabb = rotatedAabb(cx, cy, w, h, angle_deg);
  if (!checkBox(aabb)) {
    notify("Oriented dimensions extend outside the image.", "error");
    fillSelection();
    return;
  }
  modifySelection(x => {
    x.bbox_rotated.w = Math.round(w * 1000) / 1000;
    x.bbox_rotated.h = Math.round(h * 1000) / 1000;
    x.bbox_xyxy = aabb.map(v => Math.round(v * 1000) / 1000);
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

function updateSheetMetadata() {
  if (!state.doc || state.busy) return;
  const next = {
    source_id: $("metaSourceId").value.trim(),
    project_family_id: $("metaProjectFamilyId").value.trim(),
    document_id: $("metaDocumentId").value.trim(),
    notes: $("metaNotes").value.trim(),
  };
  if (next.source_id === (state.doc.source_id || "") &&
      next.project_family_id === (state.doc.project_family_id || "") &&
      next.document_id === (state.doc.document_id || "") &&
      next.notes === (state.doc.notes || "")) return;
  remember();
  Object.assign(state.doc, next);
  editState();
  refreshControls();
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

function placeBoxSnapAnchor(snapKey) {
  const item = chosen();
  if (!item) {
    notify("Select a box first to place an anchor.", "warning");
    return;
  }
  const pts = getBoxSnapPoints(item);
  const found = pts.find(p => p.key === snapKey);
  if (!found) return;
  const [ax, ay] = found.xy;
  let name = state.activeAnchorName;
  if (!name || name === "circle_center" || name === "visual_center") {
    if (snapKey === "center") {
      const circular = ["receptacle", "luminaire", "junction_box", "fire_alarm_device", "sprinkler_head", "sensor"];
      name = circular.includes(item.symbol_family) ? "circle_center" : "visual_center";
    } else {
      name = "wall_attachment";
    }
  }
  modifySelection(x => {
    x.anchors ||= {};
    x.anchors[name] = {
      xy: [Math.round(ax * 1000) / 1000, Math.round(ay * 1000) / 1000],
      visible: true,
      annotated: true,
    };
  });
  notify(`Placed anchor "${name}" at ${found.name} (${ax.toFixed(1)}, ${ay.toFixed(1)}).`, "saved");
  draw();
}

function autoPlaceCenterAnchor() {
  placeBoxSnapAnchor("center");
}

function snapAnchorToTip() {
  const item = chosen();
  if (!item) {
    notify("Select a box first to snap anchor to a tip.", "warning");
    return;
  }
  const ctr = itemCenter(item);
  let probe = state.pointerImg;
  const maxDim = Math.max(item.bbox_xyxy[2] - item.bbox_xyxy[0], item.bbox_xyxy[3] - item.bbox_xyxy[1]);
  if (!probe || Math.hypot(probe[0] - ctr[0], probe[1] - ctr[1]) > maxDim * 1.5) {
    const rad = ((item.bbox_rotated?.angle_deg ?? (item.rotation_degrees || 0)) * Math.PI) / 180;
    const dist = Math.max(12, maxDim / 2 - 2);
    probe = [ctr[0] + Math.cos(rad) * dist, ctr[1] + Math.sin(rad) * dist];
  }
  const tip = findFeatureTipInArea(probe, item, Math.max(24, 28 / state.scale)) ||
              findFeatureTipInArea(null, item, maxDim);
  if (!tip) {
    notify("No distinct ink tip found in the probed area. In Anchor mode, click on or near a feature tip.", "warning");
    state.activeAnchorName = state.activeAnchorName || "wall_attachment";
    setMode("anchor");
    return;
  }
  const name = state.activeAnchorName || "wall_attachment";
  modifySelection(x => {
    x.anchors ||= {};
    x.anchors[name] = {
      xy: [Math.round(tip[0] * 1000) / 1000, Math.round(tip[1] * 1000) / 1000],
      visible: true,
      annotated: true,
    };
  });
  notify(`Snapped anchor "${name}" to feature tip at (${tip[0].toFixed(1)}, ${tip[1].toFixed(1)}).`, "saved");
  draw();
}

function autoSnapFromBoxAngle() {
  const item = chosen();
  if (!item) return;
  const origin = itemCenter(item);
  const rad = ((item.bbox_rotated?.angle_deg ?? (item.rotation_degrees || 0)) * Math.PI) / 180;
  modifySelection(x => {
    x.snap_vector = {
      origin: [Math.round(origin[0] * 1000) / 1000, Math.round(origin[1] * 1000) / 1000],
      direction: [Math.round(Math.cos(rad) * 10000) / 10000, Math.round(Math.sin(rad) * 10000) / 10000],
    };
  });
  notify("Created snap vector from box center & orientation angle.", "saved");
}

function autoSnapFromWallAnchor() {
  const item = chosen();
  if (!item) return;
  const wall = item.anchors?.wall_attachment?.xy;
  if (!wall) {
    notify("Place a wall_attachment anchor first to auto-compute the vector.", "warning");
    return;
  }
  const origin = itemCenter(item);
  const dx = wall[0] - origin[0], dy = wall[1] - origin[1];
  const len = Math.hypot(dx, dy);
  if (len < 1e-4) {
    notify("Center and wall_attachment coincide; drag a custom snap vector instead.", "warning");
    return;
  }
  modifySelection(x => {
    x.snap_vector = {
      origin: [Math.round(origin[0] * 1000) / 1000, Math.round(origin[1] * 1000) / 1000],
      direction: [Math.round((dx / len) * 10000) / 10000, Math.round((dy / len) * 10000) / 10000],
    };
  });
  notify("Computed snap vector from center toward wall_attachment.", "saved");
}

function rotateSnapVector(deltaDeg) {
  const item = chosen();
  if (!item?.snap_vector) return;
  const [dx, dy] = item.snap_vector.direction;
  const ang = Math.atan2(dy, dx) + (deltaDeg * Math.PI) / 180;
  modifySelection(x => {
    x.snap_vector.direction = [
      Math.round(Math.cos(ang) * 10000) / 10000,
      Math.round(Math.sin(ang) * 10000) / 10000,
    ];
  });
}

function alignBoxToSnapVector() {
  const item = chosen();
  if (!item?.snap_vector) return;
  const [dx, dy] = item.snap_vector.direction;
  const deg = Math.round((Math.atan2(dy, dx) * 180 / Math.PI) * 10) / 10;
  setSelectedAngle(deg);
  notify(`Aligned box rotation to snap vector (${deg}°).`, "saved");
}

async function runWallSnapPreview() {
  if (state.busy) return;
  flushPendingInputs();
  const item = chosen();
  if (!item) return;
  const request = {
    page: state.page, revision: state.revision,
    jsonPath: state.jsonPath, instanceId: item.instance_id,
    evidence: reviewEvidenceKey(item),
  };
  try {
    const res = await api("/api/snap?page=" + request.page, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Labeler-Token": state.token },
      body: JSON.stringify({ instance: JSON.parse(JSON.stringify(item)) }),
    });
    const current = chosen();
    if (state.page !== request.page || state.revision !== request.revision ||
        state.jsonPath !== request.jsonPath || !current ||
        current.instance_id !== request.instanceId ||
        reviewEvidenceKey(current) !== request.evidence) {
      notify("Discarded stale wall snap: selection or geometry changed.", "warning");
      return;
    }
    state.snapPreview = res.snap;
    modifySelection(x => { x.snap = res.snap; });
    notify(`Wall snap resolved: ${res.snap.kind}.`, "saved");
  } catch (error) {
    notify("Wall snap failed: " + (error.message || error), "error");
  }
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
    const res = await api("/api/snap?page=" + state.page, {
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

async function importJsonFromFile(file) {
  if (!file || !state.doc) return;
  const source = {
    page: state.page, revision: state.revision, jsonPath: state.jsonPath,
    image_sha256: state.doc.image_sha256,
  };
  const sourceUnchanged = () => state.page === source.page &&
    state.revision === source.revision && state.jsonPath === source.jsonPath &&
    state.doc?.image_sha256 === source.image_sha256;
  try {
    const text = await file.text();
    if (!sourceUnchanged()) throw new Error("Active page changed during import; import cancelled.");
    const imported = JSON.parse(text);
    if (!imported || typeof imported !== "object" || !Array.isArray(imported.instances)) {
      throw new Error("JSON file must contain an instances array.");
    }
    if (imported.image_width && imported.image_height &&
        (Number(imported.image_width) !== state.doc.image_width ||
         Number(imported.image_height) !== state.doc.image_height)) {
      throw new Error(
        `Imported JSON dimensions (${imported.image_width}×${imported.image_height}) ` +
        `do not match current page (${state.doc.image_width}×${state.doc.image_height}).`
      );
    }
    if (imported.coordinate_system && imported.coordinate_system !== state.doc.coordinate_system) {
      throw new Error("Imported coordinates use a different coordinate system.");
    }
    // Matching dimensions alone cannot establish label alignment or human review
    // provenance. A backup of the exact raster is safe; a different or
    // unidentified source requires confirmation and new human verification.
    const sameImage = Boolean(imported.image_sha256 && state.doc.image_sha256 &&
      imported.image_sha256 === state.doc.image_sha256);
    if (!sameImage && !window.confirm(
      "This JSON does not identify the exact current image. Import as UNVERIFIED " +
      "proposals and discard previous rejected marks? Review every box before accepting."
    )) return;
    const preparedInstances = imported.instances.map((raw, idx) => {
      const fam = String(raw.symbol_family || raw.device_type || "unknown_symbol");
      return {
        ...raw,
        instance_id: String(raw.instance_id || `imported_${idx + 1}`),
        symbol_family: fam,
        symbol_variant: String(raw.symbol_variant || "unspecified"),
        discipline: disciplineFor(fam),
        device_type: fam,
        attributes_from_text: Array.isArray(raw.attributes_from_text) ? raw.attributes_from_text : [],
        is_occluded: Boolean(raw.is_occluded),
        is_truncated: Boolean(raw.is_truncated),
        ...(!sameImage ? {
          review_status: "unreviewed", review_source: "import",
          verified_ground_truth: false, uncertain: true,
        } : {}),
      };
    });
    const preparedRejected = sameImage && Array.isArray(imported.rejected_proposals)
      ? imported.rejected_proposals : [];
    // Check the same geometry, review, and attribute gates used by Save before
    // mutating the current annotations. A failed import preserves editor state.
    await api("/api/validate?page=" + source.page, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Labeler-Token": state.token },
      body: JSON.stringify({
        revision: source.revision, instances: preparedInstances,
        rejected_proposals: preparedRejected,
        metadata: {verified_no_symbols: false},
      }),
    });
    if (!sourceUnchanged()) throw new Error("Active page changed during import; import cancelled.");
    remember();
    state.doc.instances = preparedInstances;
    state.doc.rejected_proposals = preparedRejected;
    state.doc.verified_no_symbols = false;
    state.selectedId = null;
    editState();
    refresh();
    notify(`Imported ${state.doc.instances.length} labels from ${file.name}. Review and press Save.`, "saved");
  } catch (error) {
    notify("Import failed: " + (error.message || error), "error");
  } finally {
    $("importJsonFile").value = "";
  }
}

function downloadJsonBackup() {
  if (!state.doc) return;
  flushPendingInputs();
  const payload = JSON.stringify(state.doc, null, 2) + "\n";
  const blob = new Blob([payload], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = state.jsonFilename || `annotations_p${state.page + 1}.json`;
  document.body.append(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
  notify("Exported JSON backup.", "saved");
}

async function api(path, options = {}) {
  const response = await fetch(path, { cache: "no-store", ...options });
  const data = await response.json();
  if (!response.ok) {
    const err = new Error(data.error || "Request failed: HTTP " + response.status);
    err.status = response.status;
    throw err;
  }
  return data;
}

function populateFamilyDatalist(families) {
  const list = $("families");
  list.replaceChildren();
  for (const label of families) {
    const option = document.createElement("option");
    option.value = label;
    list.append(option);
  }
}

async function loadPage(page, force = false) {
  if (state.busy || state.drag || page < 0 || (page >= state.pageCount && state.doc)) return;
  if (state.doc) {
    state.pageCache[state.page] = {
      doc: JSON.parse(JSON.stringify(state.doc)),
      dirty: Boolean(state.dirty),
      revision: state.revision,
      undo: [...state.undo],
      redo: [...state.redo],
      selectedIds: new Set(state.selectedIds),
      selectedId: state.selectedId,
    };
  }
  state.busy = true;
  refreshControls();
  try {
    const result = await api("/api/page?page=" + page);
    const img = new Image();
    await new Promise((resolve, reject) => {
      img.onload = resolve;
      img.onerror = () => reject(new Error("Could not render source drawing image"));
      img.src = result.image_url;
    });
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
    if (Array.isArray(result.families)) populateFamilyDatalist(result.families);

    const presence = result.page_presence_suggestion;
    const presenceHint = $("pagePresenceHint");
    if (presenceHint) {
      presenceHint.hidden = !presence;
      if (presence) {
        const labels = {
          possible_symbols: "Possible symbols; inspect and label all target devices.",
          review_required: "Unable to exclude symbols; visually inspect the entire page.",
          blank_candidate: "No PDF content stream detected; still requires explicit full-page review.",
        };
        const terms = Array.isArray(presence.risk_terms) && presence.risk_terms.length
          ? " Signals: " + presence.risk_terms.join(", ") : "";
        presenceHint.textContent = "Automated suggestion (unverified): " +
          (labels[presence.suggestion] || labels.review_required) + terms;
      }
    }

    const cached = state.pageCache[page];
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
    checkLocalDraft();
    fit();
    refresh();
    notify(`Loaded ${state.jsonFilename || ("page " + (page + 1))}.`);
  } catch (error) {
    notify(String(error.message || error), "error");
  } finally {
    state.busy = false;
    refreshControls();
  }
}

async function loadDocumentsList() {
  const select = $("docSelect");
  try {
    const res = await api("/api/documents");
    const docs = res.documents || [];
    state.documents = docs;
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
          if (doc.has_annotations) badge = "★ [Annotated] ";
          else if (doc.has_proposals) badge = "◆ [Proposals] ";
          opt.textContent = `${badge}${doc.name} (${doc.rel_path})`;
          if (doc.is_current || doc.path === state.currentDocPath) {
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
    const isCurrent = doc.is_current || doc.path === state.currentDocPath;
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

async function nextDocument() {
  if (!state.documents || !state.documents.length) {
    await loadDocumentsList();
  }
  if (!state.documents.length) {
    notify("No blueprints found to cycle.", "warning");
    return;
  }
  const curIdx = state.documents.findIndex(d => d.path === state.currentDocPath);
  const nextIdx = (curIdx + 1) % state.documents.length;
  const nextDoc = state.documents[nextIdx];
  if (nextDoc) {
    await switchDocument(nextDoc.path);
    notify(`Switched to: ${nextDoc.name} (${nextIdx + 1} / ${state.documents.length})`, "saved");
  }
}

async function switchDocument(path) {
  if (!path || path === state.currentDocPath) return;
  const hasDirty = Object.values(state.pageCache || {}).some(c => c.dirty) || state.dirty;
  if (hasDirty && !window.confirm("You have unsaved annotations across pages in this PDF. Discard them before switching documents?")) {
    if ($("docSelect")) $("docSelect").value = state.currentDocPath;
    return;
  }
  state.busy = true;
  refreshControls();
  notify("Switching document...", "");
  try {
    await api("/api/document", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Labeler-Token": state.token },
      body: JSON.stringify({ path }),
    });
    state.pageCache = {};
    state.currentDocPath = path;
    state.page = 0;
    state.busy = false;
    await loadDocumentsList();
    await loadPage(0, true);
  } catch (err) {
    state.busy = false;
    notify("Could not switch document: " + (err.message || err), "error");
    refreshControls();
  }
}

async function exportServer() {
  state.busy = true;
  refreshControls();
  notify("Exporting annotations to server...", "");
  try {
    const res = await api("/api/server_export", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Labeler-Token": state.token },
      body: JSON.stringify({}),
    });
    notify(`Exported to server: ${res.filename} (${res.total_instances} symbols, ${res.pages_count} pages)`, "saved");
    alert(`Export complete on server!\n\nPath: ${res.path}\nTotal symbols: ${res.total_instances} (${res.verified_instances} verified)\nPages exported: ${res.pages_count}`);
  } catch (err) {
    notify("Server export failed: " + (err.message || err), "error");
  } finally {
    state.busy = false;
    refreshControls();
  }
}

async function openServerImportDialog() {
  const dialog = $("serverImportDialog");
  const list = $("serverSourceList");
  if (!dialog || !list) return;
  list.replaceChildren();
  $("customServerPath").value = "";
  dialog.showModal();
  try {
    const res = await api("/api/server_sources?page=" + state.page);
    const sources = res.sources || [];
    if (!sources.length) {
      list.textContent = "No standard candidate proposal files found on server. Enter a custom server path below.";
      return;
    }
    for (const src of sources) {
      const row = document.createElement("div");
      row.style.cssText = "display:flex;align-items:center;justify-content:space-between;gap:8px;padding:4px 6px;background:#121c24;border-radius:4px;";
      const label = document.createElement("span");
      label.style.cssText = "font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1;";
      label.textContent = (src.is_recommended ? "★ " : "") + src.name;
      label.title = src.path;
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "ghost";
      btn.textContent = "Select";
      btn.addEventListener("click", () => {
        $("customServerPath").value = src.path;
      });
      row.appendChild(label);
      row.appendChild(btn);
      list.appendChild(row);
    }
  } catch (err) {
    list.textContent = "Could not list server sources: " + (err.message || err);
  }
}

async function confirmServerImport() {
  const path = $("customServerPath").value.trim();
  if (!path) {
    notify("Select or enter a server file path first.", "warning");
    return;
  }
  const dialog = $("serverImportDialog");
  if (dialog) dialog.close();
  state.busy = true;
  refreshControls();
  notify("Importing from server file...", "");
  try {
    const res = await api("/api/server_import?page=" + state.page, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Labeler-Token": state.token },
      body: JSON.stringify({ path }),
    });
    notify(`Imported ${res.imported_count} symbols from server file: ${res.filename}`, "saved");
    state.busy = false;
    await loadPage(state.page, true);
  } catch (err) {
    state.busy = false;
    notify("Server import failed: " + (err.message || err), "error");
    refreshControls();
  }
}

async function switchSiblingFile(filename) {
  if (!filename || filename === state.jsonFilename) return;
  if (state.dirty && !window.confirm("Discard unsaved annotations before switching JSON files?")) {
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

async function savePage() {
  if (state.drag) {
    notify("Finish drawing or moving the box before saving.", "warning");
    return false;
  }
  flushPendingInputs();
  if (!state.doc || state.busy) return true;

  if (state.doc) {
    state.pageCache[state.page] = {
      doc: JSON.parse(JSON.stringify(state.doc)),
      dirty: Boolean(state.dirty),
      revision: state.revision,
      undo: [...state.undo],
      redo: [...state.redo],
      selectedIds: new Set(state.selectedIds),
      selectedId: state.selectedId,
    };
  }

  const dirtyPages = [];
  for (const [pStr, c] of Object.entries(state.pageCache)) {
    if (c.dirty) dirtyPages.push(Number(pStr));
  }
  if (!dirtyPages.includes(state.page) && state.dirty) {
    dirtyPages.push(state.page);
  }

  if (!dirtyPages.length) {
    notify("All pages are already saved.", "saved");
    return true;
  }

  state.busy = true;
  refreshControls();
  let savedCount = 0;
  try {
    for (const p of dirtyPages) {
      const cached = state.pageCache[p];
      const docToSave = (p === state.page) ? state.doc : (cached ? cached.doc : state.doc);
      const revToSave = (p === state.page) ? state.revision : (cached ? cached.revision : state.revision);
      const metadata = {
        source_id: docToSave.source_id || "",
        project_family_id: docToSave.project_family_id || "",
        document_id: docToSave.document_id || "",
        verified_no_symbols: docToSave.verified_no_symbols === true,
      };
      if (docToSave.notes) metadata.notes = docToSave.notes;
      const result = await api("/api/save?page=" + p, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Labeler-Token": state.token },
        body: JSON.stringify({
          revision: revToSave,
          instances: docToSave.instances,
          rejected_proposals: docToSave.rejected_proposals || [],
          metadata,
        }),
      });
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
    }
    savedState();
    refresh();
    notify(`Saved ${savedCount} page${savedCount > 1 ? "s" : ""} to server.`, "saved");
    return true;
  } catch (error) {
    if (error.status === 409) {
      $("conflictBanner").hidden = false;
    }
    notify("Save failed: " + (error.message || error), "error");
    return false;
  } finally {
    state.busy = false;
    refreshControls();
  }
}

function wireEvents() {
  if ($("navPdfsBtn")) $("navPdfsBtn").addEventListener("click", openPdfMenu);
  if ($("nextPdfBtn")) $("nextPdfBtn").addEventListener("click", nextDocument);
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
  if ($("serverExportBtn")) $("serverExportBtn").addEventListener("click", exportServer);
  if ($("serverImportBtn")) $("serverImportBtn").addEventListener("click", openServerImportDialog);
  if ($("closeServerImportBtn")) $("closeServerImportBtn").addEventListener("click", () => $("serverImportDialog")?.close());
  if ($("confirmServerImportBtn")) $("confirmServerImportBtn").addEventListener("click", confirmServerImport);

  $("prevPage").addEventListener("click", () => loadPage(state.page - 1));
  $("nextPage").addEventListener("click", () => loadPage(state.page + 1));
  $("fileSwitcher").addEventListener("change", (e) => switchSiblingFile(e.target.value));
  $("selectTool").addEventListener("click", () => setMode("select"));
  $("drawTool").addEventListener("click", () => setMode("draw"));
  $("orientedTool").addEventListener("click", () => setMode("oriented"));
  $("anchorTool").addEventListener("click", () => setMode("anchor"));
  $("snapTool").addEventListener("click", () => setMode("snap"));
  $("panTool").addEventListener("click", () => setMode("pan"));
  $("fit").addEventListener("click", fit);
  $("zoomIn").addEventListener("click", () => zoomAt(elements.canvas.clientWidth / 2, elements.canvas.clientHeight / 2, 1.3));
  $("zoomOut").addEventListener("click", () => zoomAt(elements.canvas.clientWidth / 2, elements.canvas.clientHeight / 2, 1 / 1.3));
  $("toggleLabels").addEventListener("click", () => { state.showLabels = !state.showLabels; refreshControls(); draw(); });
  $("toggleLoupe").addEventListener("click", () => { state.showLoupe = !state.showLoupe; refreshControls(); draw(); });
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
  $("verifiedNoSymbols").addEventListener("change", (event) => {
    if (!state.doc || state.busy) return;
    const value = event.target.checked;
    if (value && state.doc.instances.length) {
      event.target.checked = false;
      notify("Reject or remove every active proposal before marking this page symbol-free.", "warning");
      return;
    }
    if (value && !window.confirm("Have you reviewed the ENTIRE page and confirmed there are no target symbols? This creates negative training labels.")) {
      event.target.checked = false;
      return;
    }
    remember();
    state.doc.verified_no_symbols = value;
    editState();
    refresh();
  });
  $("reloadFromDisk").addEventListener("click", () => loadPage(state.page, true));
  $("restoreDraftBtn").addEventListener("click", async () => {
    try {
      const parsed = JSON.parse(window.localStorage?.getItem(draftStorageKey()) || "null");
      if (!draftCompatible(parsed)) {
        $("draftBanner").hidden = true;
        notify("Draft cannot be restored: image identity or saved revision differs.", "error");
        return;
      }
      const source = { page: state.page, revision: state.revision, jsonPath: state.jsonPath };
      await api("/api/validate?page=" + source.page, {
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
  $("importJsonBtn").addEventListener("click", () => $("importJsonFile").click());
  $("importJsonFile").addEventListener("change", (e) => importJsonFromFile(e.target.files?.[0]));
  $("downloadJsonBtn").addEventListener("click", downloadJsonBackup);

  $("search").addEventListener("input", refreshList);
  $("filter").addEventListener("change", () => { refreshList(); draw(); });
  $("nextPending").addEventListener("click", () => nextPending());
  $("accept").addEventListener("click", verifySelected);
  $("unverify").addEventListener("click", unverifySelected);
  $("reject").addEventListener("click", rejectSelected);
  $("duplicate").addEventListener("click", duplicateSelected);
  $("delete").addEventListener("click", deleteSelected);

  for (const btn of document.querySelectorAll(".quick-family, .multi-quick-family")) {
    btn.addEventListener("click", () => applyQuickFamily(btn.dataset.family));
  }
  for (const id of ["instanceIdInput", "family", "variant", "x0", "y0", "x1", "y1", "occluded", "truncated"]) {
    $(id).addEventListener("change", updateSelectedFields);
  }
  $("family").addEventListener("input", () => {
    const fam = $("family").value.trim();
    $("disciplineBadge").textContent = disciplineFor(fam);
    updateVariantDatalist(fam);
    if (chosen() && fam !== chosen().symbol_family) editState();
  });
  for (const id of ["instanceIdInput", "variant", "x0", "y0", "x1", "y1", "angle", "rotW", "rotH"]) {
    $(id).addEventListener("input", () => { if (chosen()) editState(); });
  }
  $("angle").addEventListener("change", updateAngle);
  $("rotW").addEventListener("change", updateOrientedDims);
  $("rotH").addEventListener("change", updateOrientedDims);
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

  $("placeCircleCenter").addEventListener("click", () => { state.activeAnchorName = "circle_center"; setMode("anchor"); });
  $("placeVisualCenter").addEventListener("click", () => { state.activeAnchorName = "visual_center"; setMode("anchor"); });
  $("placeWallAttach").addEventListener("click", () => { state.activeAnchorName = "wall_attachment"; setMode("anchor"); });
  $("autoCenterAnchor")?.addEventListener("click", autoPlaceCenterAnchor);
  $("snapAnchorCenter")?.addEventListener("click", () => placeBoxSnapAnchor("center"));
  $("snapAnchorTop")?.addEventListener("click", () => placeBoxSnapAnchor("top"));
  $("snapAnchorBottom")?.addEventListener("click", () => placeBoxSnapAnchor("bottom"));
  $("snapAnchorLeft")?.addEventListener("click", () => placeBoxSnapAnchor("left"));
  $("snapAnchorRight")?.addEventListener("click", () => placeBoxSnapAnchor("right"));
  $("snapAnchorTip")?.addEventListener("click", snapAnchorToTip);
  $("placeCustomAnchor").addEventListener("click", () => {
    const name = $("customAnchorName").value.trim();
    if (!name) { notify("Enter a custom anchor name first.", "warning"); return; }
    state.activeAnchorName = name;
    $("customAnchorName").value = "";
    setMode("anchor");
  });
  $("drawSnapVec").addEventListener("click", () => setMode("snap"));
  $("autoSnapFromAngle").addEventListener("click", autoSnapFromBoxAngle);
  $("autoSnapFromWallAnchor").addEventListener("click", autoSnapFromWallAnchor);
  $("flipSnapVec").addEventListener("click", () => rotateSnapVector(180));
  $("perpSnapVec").addEventListener("click", () => rotateSnapVector(90));
  $("alignBoxToVec").addEventListener("click", alignBoxToSnapVector);
  $("testWallSnap").addEventListener("click", runWallSnapPreview);
  $("clearSnapVec").addEventListener("click", () => {
    state.snapPreview = null;
    modifySelection(x => { delete x.snap_vector; });
  });

  for (const id of ["metaSourceId", "metaProjectFamilyId", "metaDocumentId", "metaNotes"]) {
    $(id).addEventListener("change", updateSheetMetadata);
    $(id).addEventListener("input", () => { if (state.doc) editState(); });
  }

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
    if (state.dirty) { e.preventDefault(); e.returnValue = ""; }
  });
  window.addEventListener("keyup", event => {
    if (event.code === "Space") state.space = false;
  });
  window.addEventListener("keydown", event => {
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
    if (event.repeat || state.busy) return;
    const key = event.key.toLowerCase();
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
    if (!event.shiftKey && /^[1-6]$/.test(key)) {
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
    else if (key === "k") setMode("anchor");
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
    else if (key === "[") loadPage(state.page - 1);
    else if (key === "]") loadPage(state.page + 1);
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
    state.currentDocName = response.current_doc_name || "";
    state.currentDocPath = response.current_doc_path || "";
    await loadDocumentsList();
    await loadPage(response.initial_page, true);
  } catch (error) {
    notify("Could not start editor: " + (error.message || error), "error");
  }
}

if (typeof document !== "undefined") init();
