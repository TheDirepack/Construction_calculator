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
const QUICK_FAMILIES = ["receptacle", "switch", "luminaire", "fire_alarm_device", "data_outlet", "junction_box"];
const VIEW_FILTERS = [
  { name: "Normal", css: "none" },
  { name: "Dim", css: "brightness(0.72) contrast(1.08)" },
  { name: "Invert", css: "invert(0.92) hue-rotate(180deg) contrast(1.1)" },
];
const $ = (id) => document.getElementById(id);

const state = {
  token: null, page: 0, pageCount: 1, revision: null, doc: null,
  image: null, mode: "select", selectedId: null,
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
  walls: [],
  familyDisciplines: {},
  variantsByFamily: {},
  attributeSchema: { schemas: {}, family_map: {}, numeric: [], string: [], flags: [], list_fields: [] },
  modeKind: "pdf",
  jsonFilename: "",
  jsonPath: "",
  siblingFiles: [],
};

const elements = typeof document === "undefined" ? null : {
  canvas: $("canvas"), viewport: $("viewport"), items: $("items"),
};

function draftStorageKey() {
  return `blueprint_labeler_draft::${state.jsonPath || state.jsonFilename || ("page_" + state.page)}`;
}
function saveLocalDraft() {
  if (!state.doc || !state.dirty) return;
  try {
    window.localStorage?.setItem(draftStorageKey(), JSON.stringify({
      revision: state.revision,
      instances: state.doc.instances,
      rejected_proposals: state.doc.rejected_proposals || [],
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
    if (parsed && parsed.revision === state.revision && Array.isArray(parsed.instances)) {
      $("draftBanner").hidden = false;
    } else {
      clearLocalDraft();
    }
  } catch (_) {
    $("draftBanner").hidden = true;
  }
}

function notify(message, kind = "") {
  const target = $("message");
  target.textContent = message;
  target.className = kind;
}
function editState() {
  state.dirty = true;
  $("dirty").textContent = "Unsaved changes";
  $("dirty").className = "warning";
  $("save").disabled = false;
  saveLocalDraft();
}
function savedState() {
  state.dirty = false;
  $("dirty").textContent = "Saved";
  $("dirty").className = "saved";
  $("save").disabled = state.revision !== null;
  $("conflictBanner").hidden = true;
  clearLocalDraft();
}
function snapshot() {
  return JSON.stringify({
    instances: state.doc.instances,
    rejected_proposals: state.doc.rejected_proposals || [],
    selectedId: state.selectedId,
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
  state.selectedId = data.selectedId;
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
    const isSel = item.instance_id === state.selectedId;

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

    if (isSel) {
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

  if (state.drag?.type === "draw") {
    const box = normalizedBox(state.drag.start, state.drag.current,
                              state.doc.image_width, state.doc.image_height);
    const [x, y] = imageToScreen(box[0], box[1], viewport());
    ctx.strokeStyle = "#33a7ff"; ctx.lineWidth = 2; ctx.setLineDash([5, 3]);
    ctx.strokeRect(x, y, (box[2] - box[0]) * state.scale, (box[3] - box[1]) * state.scale);
    ctx.setLineDash([]);
    drawBadge(ctx, x, Math.max(4, y - 24), `${(box[2] - box[0]).toFixed(1)} × ${(box[3] - box[1]).toFixed(1)} px`);
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

  drawLoupe(ctx, w, h);
  $("zoomLabel").textContent = Math.round(state.scale * 100) + "%";
}

function buildOrientedFromDraft(draft, cursorPt) {
  const { p1, p2, angleDeg, w } = draft;
  const cx0 = (p1[0] + p2[0]) / 2;
  const cy0 = (p1[1] + p2[1]) / 2;
  const a = angleDeg * Math.PI / 180;
  const nx = -Math.sin(a), ny = Math.cos(a);
  const signedDist = (cursorPt[0] - cx0) * nx + (cursorPt[1] - cy0) * ny;
  const h = Math.max(4, Math.abs(signedDist) * 2);
  return { cx: cx0, cy: cy0, w: Math.max(4, w), h, angle_deg: angleDeg };
}

function refreshControls() {
  $("pageIndicator").textContent = (state.pageCount === 1 && state.doc?.page_index > 0)
    ? "Source p" + (state.doc.page_index + 1)
    : "Page " + (state.page + 1) + " / " + state.pageCount;
  $("prevPage").disabled = state.page === 0 || state.busy;
  $("nextPage").disabled = state.page >= state.pageCount - 1 || state.busy;
  for (const name of ["select", "draw", "oriented", "anchor", "snap", "pan"]) {
    const el = $(name + "Tool");
    if (el) el.setAttribute("aria-pressed", String(state.mode === name));
  }
  $("undo").disabled = state.undo.length === 0;
  $("redo").disabled = state.redo.length === 0;
  $("save").disabled = (!state.dirty && state.revision !== null) || state.busy;
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
    banner.textContent = state.orientedDraft
      ? "Oriented box (step 2/2): move perpendicular to set thickness, then click to finish (Esc to cancel)"
      : "Oriented box (step 1/2): drag along symbol axis (hold Shift for 15° snap · Esc to exit)";
  } else if (state.mode === "anchor") {
    banner.hidden = false;
    banner.textContent = `Anchor mode: click canvas to place "${state.activeAnchorName}" (Esc to exit)`;
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
  const item = chosen();
  $("emptySelection").hidden = Boolean(item);
  $("selectionDetails").hidden = !item;
  if (!item) return;
  $("selectedId").textContent = item.instance_id;
  $("instanceIdInput").value = item.instance_id;
  $("disciplineBadge").textContent = disciplineFor(item.symbol_family);
  $("family").value = item.symbol_family;
  for (const btn of document.querySelectorAll(".quick-family")) {
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
  state.selectedId = id;
  if (focus && chosen()) centerBox(chosen().bbox_xyxy);
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

function applyQuickFamily(family) {
  if (!family) return;
  state.lastFamily = family;
  if (!chosen()) {
    notify(`Next drawn box will default to ${family}.`);
    return;
  }
  modifySelection(x => {
    x.symbol_family = family;
    x.discipline = disciplineFor(family);
    x.device_type = family;
  });
  notify(`Set family to ${family}.`, "saved");
}

function newInstance(box, rotated = null) {
  const family = state.lastFamily;
  const item = {
    instance_id: "manual_p" + (state.page + 1) + "_" + crypto.randomUUID().slice(0, 8),
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
  state.doc.instances.push(item);
  select(item.instance_id);
  editState();
  notify(rotated ? "Oriented draft box added. Press 1–6 for family, A to verify." : "Draft box added. Press 1–6 for family, A to verify.");
}

function checkBox(box) {
  return box[2] - box[0] >= 2 && box[3] - box[1] >= 2 &&
    box[0] >= 0 && box[1] >= 0 && box[2] <= state.doc.image_width &&
    box[3] <= state.doc.image_height;
}

function modifySelection(callback) {
  if (state.busy) return;
  const item = chosen();
  if (!item) return;
  remember();
  try {
    const ok = callback(item);
    if (ok === false) { state.undo.pop(); return; }
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
  modifySelection(item => {
    item.review_status = "accepted";
    item.review_source = "human";
    item.verified_ground_truth = true;
    item.uncertain = false;
  });
  nextPending(false);
}

function unverifySelected() {
  flushPendingInputs();
  modifySelection(item => {
    item.review_status = "unreviewed";
    item.review_source = "manual_draft";
    item.verified_ground_truth = false;
    item.uncertain = true;
  });
}

function rejectSelected() {
  flushPendingInputs();
  if (state.busy) return;
  const item = chosen();
  if (!item) return;
  remember();
  state.doc.instances = state.doc.instances.filter(x => x.instance_id !== item.instance_id);
  const rejected = {
    ...item,
    review_status: "rejected",
    review_source: "human",
    verified_ground_truth: false,
    uncertain: true,
  };
  state.doc.rejected_proposals ||= [];
  state.doc.rejected_proposals.push(rejected);
  state.selectedId = null;
  editState();
  refresh();
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
  copy.instance_id = "manual_p" + (state.page + 1) + "_" + crypto.randomUUID().slice(0, 8);
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
  state.doc.instances.push(copy);
  select(copy.instance_id);
  editState();
  notify(`Stamped ${copy.symbol_family}/${copy.symbol_variant} at cursor. Press A to verify.`, "saved");
}

function deleteSelected() {
  if (state.busy || !chosen()) return;
  remember();
  state.doc.instances = state.doc.instances.filter(x => x.instance_id !== state.selectedId);
  state.selectedId = null;
  editState();
  refresh();
}

function nudgeSelected(dx, dy) {
  const item = chosen();
  if (!item || state.busy) return;
  const tx = clamp(dx, -item.bbox_xyxy[0], state.doc.image_width - item.bbox_xyxy[2]);
  const ty = clamp(dy, -item.bbox_xyxy[1], state.doc.image_height - item.bbox_xyxy[3]);
  if (Math.abs(tx) < 1e-6 && Math.abs(ty) < 1e-6) return;
  modifySelection(x => {
    x.bbox_xyxy = [
      Math.round((x.bbox_xyxy[0] + tx) * 1000) / 1000,
      Math.round((x.bbox_xyxy[1] + ty) * 1000) / 1000,
      Math.round((x.bbox_xyxy[2] + tx) * 1000) / 1000,
      Math.round((x.bbox_xyxy[3] + ty) * 1000) / 1000,
    ];
    if (x.bbox_rotated) {
      x.bbox_rotated.cx = Math.round((x.bbox_rotated.cx + tx) * 1000) / 1000;
      x.bbox_rotated.cy = Math.round((x.bbox_rotated.cy + ty) * 1000) / 1000;
    }
  });
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
  if (state.space || state.mode === "pan" || event.button === 1) {
    state.drag = { type: "pan", at: xy, x: state.x, y: state.y };
  } else if (state.mode === "draw") {
    state.drag = { type: "draw", start: pt, current: pt };
  } else if (state.mode === "oriented") {
    if (state.orientedDraft) {
      const rot = buildOrientedFromDraft(state.orientedDraft, pt);
      const aabb = rotatedAabb(rot.cx, rot.cy, rot.w, rot.h, rot.angle_deg);
      state.orientedDraft = null;
      if (checkBox(aabb)) {
        remember();
        newInstance(aabb, rot);
      } else {
        notify("Oriented box extends outside image bounds.", "error");
      }
      refreshControls();
      draw();
      return;
    }
    const clampedPt = [clamp(pt[0], 0, state.doc.image_width), clamp(pt[1], 0, state.doc.image_height)];
    state.drag = { type: "oriented-axis", start: clampedPt, current: clampedPt };
  } else if (state.mode === "anchor") {
    const item = chosen();
    if (!item) { setMode("select"); return; }
    const ax = clamp(pt[0], 0, state.doc.image_width);
    const ay = clamp(pt[1], 0, state.doc.image_height);
    const name = state.activeAnchorName || "circle_center";
    modifySelection(x => {
      x.anchors ||= {};
      x.anchors[name] = {
        xy: [Math.round(ax * 1000) / 1000, Math.round(ay * 1000) / 1000],
        visible: true,
        annotated: true,
      };
    });
    notify(`Placed anchor "${name}" at (${ax.toFixed(1)}, ${ay.toFixed(1)}).`, "saved");
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
        originalRotation: hit.item.bbox_rotated ? { ...hit.item.bbox_rotated } : null,
        rotBase,
        moved: false,
        selected: hit.item.instance_id,
      };
    }
  }
  if (state.drag) elements.canvas.setPointerCapture(event.pointerId);
  draw();
}

function pointerMove(event) {
  const xy = coordinate(event), pt = screenToImage(...xy, viewport());
  state.pointerScreen = xy;
  state.pointerImg = pt;
  if (state.doc) {
    $("cursorCoords").textContent = `x:${pt[0].toFixed(0)} y:${pt[1].toFixed(0)}`;
  }
  if (!state.drag) {
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
  } else if (d.type === "draw") {
    d.current = pt;
  } else if (d.type === "oriented-axis" || d.type === "snap") {
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
      const tx = clamp(dx, -box[0], state.doc.image_width - box[2]);
      const ty = clamp(dy, -box[1], state.doc.image_height - box[3]);
      box = [box[0] + tx, box[1] + ty, box[2] + tx, box[3] + ty];
      if (item.bbox_rotated) {
        item.bbox_rotated.cx = d.originalRotation.cx + tx;
        item.bbox_rotated.cy = d.originalRotation.cy + ty;
      }
    } else {
      if (d.type.includes("w")) box[0] = clamp(d.box[0] + dx, 0, box[2] - 2);
      if (d.type.includes("e")) box[2] = clamp(d.box[2] + dx, box[0] + 2, state.doc.image_width);
      if (d.type.includes("n")) box[1] = clamp(d.box[1] + dy, 0, box[3] - 2);
      if (d.type.includes("s")) box[3] = clamp(d.box[3] + dy, box[1] + 2, state.doc.image_height);
      delete item.bbox_rotated;
      item.rotation_degrees = 0;
      d.moved = true;
    }
    item.bbox_xyxy = box;
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
  if (d.type === "draw") {
    const box = normalizedBox(d.start, d.current, state.doc.image_width, state.doc.image_height);
    if (checkBox(box)) { remember(); newInstance(box); }
  } else if (d.type === "oriented-axis") {
    const len = Math.hypot(d.current[0] - d.start[0], d.current[1] - d.start[1]);
    if (len >= 4) {
      const ang = Math.round(Math.atan2(d.current[1] - d.start[1], d.current[0] - d.start[0]) * 180 / Math.PI * 10) / 10;
      state.orientedDraft = { p1: d.start, p2: d.current, angleDeg: ang, w: len };
      refreshControls();
    } else {
      notify("Drag along the symbol's main axis (at least 4 px).", "warning");
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
  if (drag.type === "pan") {
    state.x = drag.x; state.y = drag.y;
  } else if (drag.type !== "draw" && drag.type !== "snap" && drag.type !== "oriented-axis") {
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
  const family = $("family").value.trim(), variant = $("variant").value.trim();
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

function autoPlaceCenterAnchor() {
  const item = chosen();
  if (!item) return;
  const cx = item.bbox_rotated ? item.bbox_rotated.cx : (item.bbox_xyxy[0] + item.bbox_xyxy[2]) / 2;
  const cy = item.bbox_rotated ? item.bbox_rotated.cy : (item.bbox_xyxy[1] + item.bbox_xyxy[3]) / 2;
  const circular = ["receptacle", "luminaire", "junction_box", "fire_alarm_device", "sprinkler_head", "sensor"];
  const name = circular.includes(item.symbol_family) ? "circle_center" : "visual_center";
  modifySelection(x => {
    x.anchors ||= {};
    x.anchors[name] = {
      xy: [Math.round(cx * 1000) / 1000, Math.round(cy * 1000) / 1000],
      visible: true,
      annotated: true,
    };
  });
  notify(`Placed ${name} at box center (${cx.toFixed(1)}, ${cy.toFixed(1)}).`, "saved");
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
  const item = chosen();
  if (!item || state.busy) return;
  flushPendingInputs();
  try {
    const res = await api("/api/snap?page=" + state.page, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Labeler-Token": state.token },
      body: JSON.stringify({ instance: item }),
    });
    state.snapPreview = res.snap;
    modifySelection(x => { x.snap = res.snap; });
    notify(`Wall snap resolved: ${res.snap.kind}.`, "saved");
  } catch (error) {
    notify("Wall snap failed: " + (error.message || error), "error");
  }
}

async function importJsonFromFile(file) {
  if (!file || !state.doc) return;
  try {
    const text = await file.text();
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
    remember();
    state.doc.instances = imported.instances.map((raw, idx) => {
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
      };
    });
    state.doc.rejected_proposals = Array.isArray(imported.rejected_proposals)
      ? imported.rejected_proposals : [];
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
  if (state.dirty && !force &&
      !window.confirm("Discard unsaved annotations on this page? Click Cancel, then Save.")) return;
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
    state.revision = result.revision;
    state.doc = result.document;
    state.image = img;
    state.selectedId = null;
    state.snapPreview = null;
    state.orientedDraft = null;
    state.undo.length = 0;
    state.redo.length = 0;
    state.familyDisciplines = result.family_disciplines || {};
    state.variantsByFamily = result.variants_by_family || {};
    state.attributeSchema = result.attribute_schema || state.attributeSchema;
    state.modeKind = result.mode || "pdf";
    state.jsonFilename = result.json_filename || "";
    state.jsonPath = result.json_path || "";
    state.siblingFiles = result.sibling_files || [];
    state.walls = Array.isArray(result.walls) ? result.walls : [];
    if (Array.isArray(result.families)) populateFamilyDatalist(result.families);
    $("search").value = "";
    $("filter").value = "all";
    state.dirty = false;
    $("dirty").textContent = "Saved";
    $("dirty").className = "saved";
    $("save").disabled = state.revision !== null;
    $("conflictBanner").hidden = true;
    checkLocalDraft();
    fit();
    refresh();
    notify(`Loaded ${state.jsonFilename || ("page " + (page + 1))}. Draw boxes or review proposals.`);
  } catch (error) {
    notify(String(error.message || error), "error");
  } finally {
    state.busy = false;
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
  if (!state.dirty && state.revision !== null) return true;
  state.busy = true;
  refreshControls();
  try {
    const metadata = {
      source_id: state.doc.source_id || "",
      project_family_id: state.doc.project_family_id || "",
      document_id: state.doc.document_id || "",
    };
    if (state.doc.notes) metadata.notes = state.doc.notes;
    const result = await api("/api/save?page=" + state.page, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Labeler-Token": state.token },
      body: JSON.stringify({
        revision: state.revision,
        instances: state.doc.instances,
        rejected_proposals: state.doc.rejected_proposals || [],
        metadata,
      }),
    });
    state.revision = result.revision;
    if (result.document) {
      state.doc = result.document;
    }
    savedState();
    refresh();
    notify(`Saved ${state.jsonFilename || ("page " + (state.page + 1))}. No full-page completeness asserted.`, "saved");
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
  $("reloadFromDisk").addEventListener("click", () => loadPage(state.page, true));
  $("restoreDraftBtn").addEventListener("click", () => {
    try {
      const parsed = JSON.parse(window.localStorage?.getItem(draftStorageKey()) || "null");
      if (parsed?.instances) {
        remember();
        state.doc.instances = parsed.instances;
        state.doc.rejected_proposals = parsed.rejected_proposals || [];
        $("draftBanner").hidden = true;
        editState();
        refresh();
        notify("Restored unsaved local draft. Press Save when ready.", "saved");
      }
    } catch (_) { clearLocalDraft(); }
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

  for (const btn of document.querySelectorAll(".quick-family")) {
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
  $("autoCenterAnchor").addEventListener("click", autoPlaceCenterAnchor);
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
    else if (key === "k") setMode("anchor");
    else if (key === "g") setMode("snap");
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
    await loadPage(response.initial_page, true);
  } catch (error) {
    notify("Could not start editor: " + (error.message || error), "error");
  }
}

if (typeof document !== "undefined") init();
