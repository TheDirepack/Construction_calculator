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
export function hitBox(box, x, y, scale, includeCorners) {
  const tolerance = 8 / scale;
  const [x0, y0, x1, y1] = box;
  if (includeCorners) {
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

const $ = (id) => document.getElementById(id);
const state = {
  token: null, page: 0, pageCount: 1, revision: null, doc: null,
  image: null, mode: "select", selectedId: null,
  scale: 1, x: 0, y: 0, space: false, drag: null, dirty: false,
  undo: [], redo: [], lastFamily: "receptacle", busy: false,
};
const elements = typeof document === "undefined" ? null : {
  canvas: $("canvas"), viewport: $("viewport"), items: $("items"),
};
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
}
function savedState() {
  state.dirty = false;
  $("dirty").textContent = "Saved";
  $("dirty").className = "saved";
  $("save").disabled = true;
}
function snapshot() {
  return JSON.stringify({
    instances: state.doc.instances,
    rejected_proposals: state.doc.rejected_proposals || [],
    selectedId: state.selectedId,
  });
}
function remember() {
  state.undo.push(snapshot());
  if (state.undo.length > 70) state.undo.shift();
  state.redo.length = 0;
}
function restore(snapshotText) {
  const data = JSON.parse(snapshotText);
  state.doc.instances = data.instances;
  state.doc.rejected_proposals = data.rejected_proposals;
  state.selectedId = data.selectedId;
  editState();
  refresh();
}
function undo() {
  if (!state.undo.length) return;
  state.redo.push(snapshot());
  restore(state.undo.pop());
}
function redo() {
  if (!state.redo.length) return;
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
function fit() {
  if (!state.image) return;
  const w = elements.canvas.clientWidth, h = elements.canvas.clientHeight;
  state.scale = Math.min((w - 40) / state.image.naturalWidth, (h - 40) / state.image.naturalHeight);
  state.scale = Math.max(state.scale, .015);
  state.x = (w - state.image.naturalWidth * state.scale) / 2;
  state.y = (h - state.image.naturalHeight * state.scale) / 2;
  draw();
}
function zoomAt(x, y, ratio) {
  if (!state.image) return;
  const next = clamp(state.scale * ratio, .02, 24);
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
  if (item.instance_id === state.selectedId) return "#2f9fef";
  if (item.uncertain || item.review_status === "unreviewed") return "#e0a44a";
  return "#1c9f72";
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
  ctx.fillStyle = "#121d25"; ctx.fillRect(0, 0, w, h);
  if (state.image) {
    ctx.imageSmoothingEnabled = state.scale < 1;
    ctx.drawImage(state.image, state.x, state.y,
                  state.image.naturalWidth * state.scale,
                  state.image.naturalHeight * state.scale);
  }
  for (const item of state.doc.instances) {
    const box = item.bbox_xyxy;
    const [sx0, sy0] = imageToScreen(box[0], box[1], viewport());
    const bw = (box[2] - box[0]) * state.scale;
    const bh = (box[3] - box[1]) * state.scale;
    const color = labelColor(item);
    ctx.lineWidth = item.instance_id === state.selectedId ? 2 : 1.5;
    ctx.strokeStyle = color;
    ctx.strokeRect(sx0, sy0, bw, bh);
    if (item.bbox_rotated) {
      const rot = item.bbox_rotated;
      ctx.save();
      ctx.translate(rot.cx * state.scale + state.x, rot.cy * state.scale + state.y);
      ctx.rotate(rot.angle_deg * Math.PI / 180);
      ctx.strokeRect(-rot.w * state.scale / 2, -rot.h * state.scale / 2,
                     rot.w * state.scale, rot.h * state.scale);
      ctx.restore();
    }
    if (bw > 28 && bh > 9) {
      const text = item.symbol_family + (item.symbol_variant !== "unspecified" ? "/" + item.symbol_variant : "");
      ctx.font = "12px system-ui, sans-serif";
      const tw = Math.min(260, ctx.measureText(text).width + 9);
      ctx.fillStyle = color;
      ctx.fillRect(sx0, Math.max(0, sy0 - 19), tw, 18);
      ctx.fillStyle = "#08131b";
      ctx.fillText(text.slice(0, 40), sx0 + 4, Math.max(12, sy0 - 5));
    }
    if (item.instance_id === state.selectedId) {
      ctx.fillStyle = "#fff"; ctx.strokeStyle = color; ctx.lineWidth = 2;
      for (const [cx, cy] of [[sx0, sy0], [sx0 + bw, sy0],
                              [sx0, sy0 + bh], [sx0 + bw, sy0 + bh]]) {
        ctx.fillRect(cx - 4, cy - 4, 8, 8);
        ctx.strokeRect(cx - 4, cy - 4, 8, 8);
      }
    }
  }
  if (state.drag?.type === "draw") {
    const box = normalizedBox(state.drag.start, state.drag.current,
                              state.doc.image_width, state.doc.image_height);
    const [x, y] = imageToScreen(box[0], box[1], viewport());
    ctx.strokeStyle = "#2f9fef"; ctx.lineWidth = 2; ctx.setLineDash([5, 3]);
    ctx.strokeRect(x, y, (box[2] - box[0]) * state.scale, (box[3] - box[1]) * state.scale);
    ctx.setLineDash([]);
  }
  $("zoomLabel").textContent = Math.round(state.scale * 100) + "%";
}
function refreshControls() {
  $("pageIndicator").textContent = "Page " + (state.page + 1) + " / " + state.pageCount;
  $("prevPage").disabled = state.page === 0 || state.busy;
  $("nextPage").disabled = state.page >= state.pageCount - 1 || state.busy;
  for (const name of ["select", "draw", "pan"]) {
    $(name + "Tool").setAttribute("aria-pressed", String(state.mode === name));
  }
  $("undo").disabled = state.undo.length === 0;
  $("redo").disabled = state.redo.length === 0;
  $("save").disabled = !state.dirty || state.busy;
}
function sortedItems() {
  return [...state.doc.instances].sort((a, b) =>
    a.bbox_xyxy[1] - b.bbox_xyxy[1] || a.bbox_xyxy[0] - b.bbox_xyxy[0]
  );
}
function refreshList() {
  const accepted = state.doc.instances.filter(x => x.review_status === "accepted").length;
  const unreviewed = state.doc.instances.filter(x => x.review_status !== "accepted").length;
  const rejected = (state.doc.rejected_proposals || []).length;
  $("count").textContent = state.doc.instances.length + " active · " +
    accepted + " verified · " + unreviewed + " pending · " + rejected + " rejected";
  const query = $("search").value.trim().toLowerCase();
  const filter = $("filter").value;
  const rows = sortedItems().filter(item => {
    if (filter === "accepted" && item.review_status !== "accepted") return false;
    if (filter === "unreviewed" && item.review_status === "accepted") return false;
    return !query || (item.symbol_family + " " + item.symbol_variant + " " +
      item.instance_id).toLowerCase().includes(query);
  });
  elements.items.replaceChildren();
  for (const item of rows.slice(0, 400)) {
    const btn = document.createElement("button");
    btn.className = item.instance_id === state.selectedId ? "chosen" : "";
    const txt = document.createElement("span");
    txt.textContent = item.symbol_family + " / " + item.symbol_variant;
    const tag = document.createElement("span");
    tag.className = "tag " + (item.review_status === "accepted" ? "accepted" : "");
    tag.textContent = item.review_status === "accepted" ? "Verified" : "Pending";
    btn.append(txt, tag);
    btn.addEventListener("click", () => select(item.instance_id, true));
    elements.items.append(btn);
  }
  if (rows.length > 400) {
    const note = document.createElement("p");
    note.className = "small"; note.textContent = "Showing first 400. Filter to narrow results.";
    elements.items.append(note);
  }
}
function fillSelection() {
  const item = chosen();
  $("emptySelection").hidden = Boolean(item);
  $("selectionDetails").hidden = !item;
  if (!item) return;
  $("selectedId").textContent = item.instance_id;
  $("family").value = item.symbol_family;
  $("variant").value = item.symbol_variant;
  ["x0", "y0", "x1", "y1"].forEach((id, i) => $(id).value = item.bbox_xyxy[i].toFixed(1));
  $("angle").value = item.bbox_rotated?.angle_deg ?? 0;
  $("occluded").checked = Boolean(item.is_occluded);
  $("truncated").checked = Boolean(item.is_truncated);
  const score = typeof item.proposal_score === "number" ? "score " + item.proposal_score.toFixed(3) : "";
  $("proposalHint").textContent = item.review_status === "accepted" ?
    "Human-verified positive. Page completeness remains unknown." :
    "Unverified " + (item.review_source || "candidate") + " proposal " + score + ". Inspect before verifying.";
}
function refresh() {
  if (!state.doc) return;
  if (state.selectedId && !chosen()) state.selectedId = null;
  refreshControls(); refreshList(); fillSelection(); draw();
}
function select(id, focus = false) {
  state.selectedId = id;
  if (focus && chosen()) centerBox(chosen().bbox_xyxy);
  refresh();
}
function setMode(mode) {
  state.mode = mode;
  refreshControls();
  elements.canvas.style.cursor = mode === "pan" ? "grab" : mode === "draw" ? "crosshair" : "default";
}
function newInstance(box) {
  const item = {
    instance_id: "manual_p" + (state.page + 1) + "_" + crypto.randomUUID().slice(0, 8),
    symbol_family: state.lastFamily,
    symbol_variant: "unspecified",
    bbox_xyxy: box.map(x => Math.round(x * 1000) / 1000),
    uncertain: false, review_status: "accepted",
    review_source: "human", verified_ground_truth: true,
    is_truncated: false, is_occluded: false,
  };
  state.doc.instances.push(item);
  select(item.instance_id);
  editState();
  notify("Added human-labeled box. Specify its exact class and save.");
}
function checkBox(box) {
  return box[2] - box[0] >= 2 && box[3] - box[1] >= 2 &&
    box[0] >= 0 && box[1] >= 0 && box[2] <= state.doc.image_width &&
    box[3] <= state.doc.image_height;
}
function modifySelection(callback) {
  const item = chosen();
  if (!item) return;
  remember();
  try {
    const ok = callback(item);
    if (ok === false) { state.undo.pop(); return; }
    editState(); refresh();
  } catch (error) {
    state.undo.pop();
    notify(String(error.message || error), "error");
  }
}
function verifySelected() {
  modifySelection(item => {
    item.review_status = "accepted";
    item.review_source = "human";
    item.verified_ground_truth = true;
    item.uncertain = false;
  });
  nextPending(false);
}
function rejectSelected() {
  const item = chosen();
  if (!item) return;
  remember();
  state.doc.instances = state.doc.instances.filter(x => x.instance_id !== item.instance_id);
  const rejected = { ...item, review_status: "rejected", review_source: "human",
                     verified_ground_truth: false, uncertain: true };
  state.doc.rejected_proposals ||= [];
  state.doc.rejected_proposals.push(rejected);
  state.selectedId = null;
  editState(); refresh();
  nextPending(false);
}
function deleteSelected() {
  if (!chosen()) return;
  remember();
  state.doc.instances = state.doc.instances.filter(x => x.instance_id !== state.selectedId);
  state.selectedId = null;
  editState(); refresh();
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
function currentPageMouseHit(point) {
  const selected = chosen();
  if (selected) {
    const hit = hitBox(selected.bbox_xyxy, point[0], point[1], state.scale, true);
    if (hit) return { item: selected, action: hit };
  }
  for (const item of [...state.doc.instances].reverse()) {
    const hit = hitBox(item.bbox_xyxy, point[0], point[1], state.scale, false);
    if (hit) return { item, action: hit };
  }
  return null;
}
function pointerDown(event) {
  if (!state.image || event.button > 1) return;
  event.preventDefault();
  const xy = coordinate(event), pt = screenToImage(...xy, viewport());
  if (state.space || state.mode === "pan" || event.button === 1) {
    state.drag = { type: "pan", at: xy, x: state.x, y: state.y };
  } else if (state.mode === "draw") {
    state.drag = { type: "draw", start: pt, current: pt };
  } else {
    const hit = currentPageMouseHit(pt);
    if (!hit) {
      select(null);
    } else {
      select(hit.item.instance_id);
      remember();
      state.drag = { type: hit.action, start: pt, box: [...hit.item.bbox_xyxy],
                     moved: false, selected: hit.item.instance_id };
    }
  }
  if (state.drag) elements.canvas.setPointerCapture(event.pointerId);
  draw();
}
function pointerMove(event) {
  if (!state.drag) return;
  const xy = coordinate(event), pt = screenToImage(...xy, viewport());
  const d = state.drag;
  if (d.type === "pan") {
    state.x = d.x + xy[0] - d.at[0];
    state.y = d.y + xy[1] - d.at[1];
  } else if (d.type === "draw") {
    d.current = pt;
  } else {
    const item = state.doc.instances.find(x => x.instance_id === d.selected);
    if (!item) return;
    const dx = pt[0] - d.start[0], dy = pt[1] - d.start[1];
    let box = [...d.box];
    if (d.type === "move") {
      dx && (d.moved = true); dy && (d.moved = true);
      const tx = clamp(dx, -box[0], state.doc.image_width - box[2]);
      const ty = clamp(dy, -box[1], state.doc.image_height - box[3]);
      box = [box[0] + tx, box[1] + ty, box[2] + tx, box[3] + ty];
      if (item.bbox_rotated) {
        item.bbox_rotated.cx += box[0] - item.bbox_xyxy[0];
        item.bbox_rotated.cy += box[1] - item.bbox_xyxy[1];
      }
    } else {
      if (d.type.includes("w")) box[0] = clamp(d.box[0] + dx, 0, box[2] - 2);
      if (d.type.includes("e")) box[2] = clamp(d.box[2] + dx, box[0] + 2, state.doc.image_width);
      if (d.type.includes("n")) box[1] = clamp(d.box[1] + dy, 0, box[3] - 2);
      if (d.type.includes("s")) box[3] = clamp(d.box[3] + dy, box[1] + 2, state.doc.image_height);
      delete item.bbox_rotated; item.rotation_degrees = 0;
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
    if (checkBox(box)) { remember(); newInstance(box); setMode("select"); }
  } else if (d.type !== "pan") {
    if (d.moved) {
      editState(); refresh();
    } else {
      // No-op click should not consume undo history.
      state.undo.pop();
    }
  }
  draw();
}
function updateSelectedFields() {
  const item = chosen();
  if (!item) return;
  const box = ["x0", "y0", "x1", "y1"].map(id => Number($(id).value));
  if (!checkBox(box) || box.some(x => !Number.isFinite(x))) {
    notify("Invalid box dimensions; coordinates must stay on the page.", "error");
    fillSelection(); return;
  }
  const family = $("family").value.trim(), variant = $("variant").value.trim();
  if (!family || family.length > 100 || !variant || variant.length > 160) {
    notify("Enter a valid family and variant.", "error");
    fillSelection(); return;
  }
  modifySelection(x => {
    const changedBox = box.some((v, i) => Math.abs(v - x.bbox_xyxy[i]) > .051);
    x.symbol_family = family; x.symbol_variant = variant;
    if (changedBox) {
      // A manually edited axis-aligned hull supersedes rotated geometry.
      x.bbox_xyxy = box;
      delete x.bbox_rotated; x.rotation_degrees = 0;
    }
    x.is_occluded = $("occluded").checked;
    x.is_truncated = $("truncated").checked;
    state.lastFamily = family;
  });
}
function updateAngle() {
  const item = chosen();
  if (!item) return;
  const angle = Number($("angle").value);
  if (!Number.isFinite(angle) || angle < -360 || angle > 360) {
    notify("Rotation must be between -360 and 360 degrees.", "error"); fillSelection(); return;
  }
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
    x.bbox_xyxy = box;
    x.rotation_degrees = angle;
    if (Math.abs(angle) < 1e-6) delete x.bbox_rotated;
    else x.bbox_rotated = { cx, cy, w, h, angle_deg: angle };
  });
}
async function api(path, options = {}) {
  const response = await fetch(path, { cache: "no-store", ...options });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || "Request failed: HTTP " + response.status);
  return data;
}
async function loadPage(page, force = false) {
  if (state.busy || page < 0 || page >= state.pageCount && state.doc) return;
  if (state.dirty && !force &&
      !window.confirm("Discard unsaved annotations on this page? Click Cancel, then Save.")) return;
  state.busy = true; refreshControls();
  try {
    const result = await api("/api/page?page=" + page);
    const img = new Image();
    await new Promise((resolve, reject) => {
      img.onload = resolve; img.onerror = () => reject(new Error("Could not render source drawing image"));
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
    state.undo.length = 0; state.redo.length = 0;
    $("search").value = "";
    $("filter").value = "all";
    savedState();
    fit(); refresh();
    notify("Loaded page " + (page + 1) + ". Draw boxes or review proposals.");
  } catch (error) {
    notify(String(error.message || error), "error");
  } finally {
    state.busy = false; refreshControls();
  }
}
async function savePage() {
  if (!state.doc || !state.dirty || state.busy) return true;
  state.busy = true; refreshControls();
  try {
    const result = await api("/api/save?page=" + state.page, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Labeler-Token": state.token },
      body: JSON.stringify({
        revision: state.revision,
        instances: state.doc.instances,
        rejected_proposals: state.doc.rejected_proposals || [],
      }),
    });
    state.revision = result.revision;
    savedState();
    notify("Saved page " + (state.page + 1) + ". No full-page completeness asserted.", "saved");
    return true;
  } catch (error) {
    notify("Save failed: " + (error.message || error), "error");
    return false;
  } finally {
    state.busy = false; refreshControls();
  }
}
function wireEvents() {
  $("prevPage").addEventListener("click", () => loadPage(state.page - 1));
  $("nextPage").addEventListener("click", () => loadPage(state.page + 1));
  $("selectTool").addEventListener("click", () => setMode("select"));
  $("drawTool").addEventListener("click", () => setMode("draw"));
  $("panTool").addEventListener("click", () => setMode("pan"));
  $("fit").addEventListener("click", fit);
  $("zoomIn").addEventListener("click", () => zoomAt(elements.canvas.clientWidth/2, elements.canvas.clientHeight/2, 1.3));
  $("zoomOut").addEventListener("click", () => zoomAt(elements.canvas.clientWidth/2, elements.canvas.clientHeight/2, 1/1.3));
  $("undo").addEventListener("click", undo);
  $("redo").addEventListener("click", redo);
  $("save").addEventListener("click", savePage);
  $("search").addEventListener("input", refreshList);
  $("filter").addEventListener("change", refreshList);
  $("nextPending").addEventListener("click", () => nextPending());
  $("accept").addEventListener("click", verifySelected);
  $("reject").addEventListener("click", rejectSelected);
  $("delete").addEventListener("click", deleteSelected);
  for (const id of ["family", "variant", "x0", "y0", "x1", "y1", "occluded", "truncated"]) {
    $(id).addEventListener("change", updateSelectedFields);
  }
  $("angle").addEventListener("change", updateAngle);
  elements.canvas.addEventListener("pointerdown", pointerDown);
  elements.canvas.addEventListener("pointermove", pointerMove);
  elements.canvas.addEventListener("pointerup", pointerUp);
  elements.canvas.addEventListener("pointercancel", pointerUp);
  elements.canvas.addEventListener("wheel", event => {
    event.preventDefault();
    const [x,y] = coordinate(event);
    zoomAt(x,y,Math.exp(-event.deltaY * .002));
  }, { passive:false });
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
      event.preventDefault(); savePage(); return;
    }
    if (form) return;
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "z") {
      event.preventDefault(); undo(); return;
    }
    if ((event.ctrlKey || event.metaKey) && (event.key.toLowerCase() === "y" ||
        (event.shiftKey && event.key.toLowerCase() === "z"))) {
      event.preventDefault(); redo(); return;
    }
    if (event.code === "Space") { event.preventDefault(); state.space = true; return; }
    if (event.repeat) return;
    const key = event.key.toLowerCase();
    if (key === "b") setMode("draw");
    else if (key === "v") setMode("select");
    else if (key === "a") verifySelected();
    else if (key === "x") rejectSelected();
    else if (key === "n") nextPending();
    else if (key === "delete" || key === "backspace") deleteSelected();
    else if (key === "[") loadPage(state.page - 1);
    else if (key === "]") loadPage(state.page + 1);
    else if (key === "0") fit();
    else if (key === "+" || key === "=") zoomAt(elements.canvas.clientWidth/2, elements.canvas.clientHeight/2, 1.3);
    else if (key === "-") zoomAt(elements.canvas.clientWidth/2, elements.canvas.clientHeight/2, 1/1.3);
  });
}
async function init() {
  wireEvents();
  try {
    const response = await api("/api/session");
    state.token = response.token;
    const first = await api("/api/page?page=" + response.initial_page);
    state.pageCount = first.page_count;
    for (const label of first.families) {
      const option = document.createElement("option");
      option.value = label;
      $("families").append(option);
    }
    await loadPage(response.initial_page, true);
  } catch (error) {
    notify("Could not start editor: " + (error.message || error), "error");
  }
}
if (typeof document !== "undefined") init();
