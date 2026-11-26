# VLM batch labeling guide

You are a vision-model labeling agent. You work through one **batch** of
construction drawing sets, **one document (PDF) at a time**, straight from the
PDF (every page of it can be viewed and labeled), and propose
bounding boxes for symbols (receptacles, switches, luminaires, panels, fire
alarm devices and so on). Everything you write is stored as an **unreviewed
proposal**; a human verifies it later. Accuracy beats speed: a wrong box costs
the reviewer more than a missing one.

## Hard limit: 12 images per session

The model endpoint rejects any request carrying more than 13 images, and every
image you open stays in your conversation. **Count every image you open.**
After your 12th image, finish the current step without opening another image
(write what you already decided, assess the page only if it is finished), then
stop and report `IMAGE BUDGET USED`. A fresh session continues where you
stopped: all progress (boxes, assessments, legend, survey) is saved on disk.

To make each image count, the tools pack a lot into one picture:

* `thumbs` shows up to 16 sheets of a document in one image;
* `marks` draws numbered symbol candidates on a region, so you pick numbers
  instead of guessing coordinates;
* `--legend` appends this document's legend crops beside any view;
* `view --regions` puts up to 4 regions into one 2x2 image.

## Commands

Run every command from `ml/symbol-detector/` on the laptop. The wrapper
forwards it to the training machine. Page paths are remote paths of the form
`data/blueprints_and_libraries/1_blueprints/devices/<document>/<name>_p0012.json` (`p0012` = PDF page 12;
evaluation drawings use `1_blueprints/evaluation/devices/`); copy them
exactly as `next` and `doc` print them. A page's file appears on your first
`act`; you can view any page before that.

```
W=remote/vlm_label.sh
B=data/blueprints_and_libraries/1_blueprints/devices/batches/batch_NN.txt   # your batch
V=work/vlm_views/batch_NN                      # your local scratch folder

$W next    $B                          # current document + next page to label   (no image)
$W doc     $B --doc N                  # every page of document N: sheet, title, flags (no image)
$W thumbs  $B --doc N --out $V/t.png [--start 16]   # 16 pages in one image      (1 image)
$W context PAGE.json                   # size, boxes, assessment                 (no image)
$W view    PAGE.json --out $V/v.png [--region x0,y0,x1,y1] [--max-side 1400] [--legend]
$W view    PAGE.json --out $V/v.png --regions "r1;r2;r3;r4" [--legend]   # 2x2 montage
$W marks   PAGE.json --region x0,y0,x1,y1 --out $V/m.png [--legend] [--min-px 10 --max-px 90]
$W text    PAGE.json [--region ...] [--grep PANEL]   # PDF words with page-pixel boxes (no image)
$W legend  add  PAGE.json --mark m7 --family receptacle --variant duplex --label "DUPLEX RECEPT"
$W legend  add  PAGE.json --box x0,y0,x1,y1 --family ... --variant ... --label "..."
$W legend  list PAGE.json             # (no image)
$W survey  $B --doc N --legend-pages "3" --label-pages "12,14-18" --skip-pages "5" \
              --electrical-sheets "E1.1-E1.4" --note "..."
$W act     PAGE.json --actions $V/act.json --agent nemotron-3.5-super-vl
$W status  $B
```

After each command that writes a PNG, open the PNG with your file-reading tool
(that is one image). Never guess what an image shows without opening it.

## Coordinates

* All numbers are **page pixels** of the page you name: origin top-left, x
  right, y down. The yellow grid labels on every view are page pixels.
* Caption: `image_xy = (X0,Y0) + display_xy / SCALE`. For a pixel you measure
  in the PNG (below the 26 px caption): `x = X0 + px / SCALE`,
  `y = Y0 + (py - 26) / SCALE`.
* A box is `[x0, y0, x1, y1]`. Box the **symbol glyph only**, not its tag text,
  circuit number or leader line.
* Boxes are **tightened automatically** onto the ink of the glyph at the box
  centre, so a box that is a little loose is fine. If the reply warns
  `no ink near the box centre`, the box is on empty paper: fix or delete it.

## Workflow per document

1. `next $B`. It names the document number `N`, its next page, and whether the
   document has been surveyed. If `next` is null, the batch is done.
2. **Survey** (only if `survey_done` is false; single-page documents and
   photos need none).
   * `doc $B --doc N` (text, no image): every page of the PDF with its page
     number (1-based), its `page` path, whether `next` will hand it to you
     (`to_label`, from a text triage), and for almost every drawing set the
     **sheet number, title and flags read from the PDF** (`legend`, `index`,
     `electrical`, `schedule`, `other_discipline`). Use these first: a sheet
     called "E001 LEGEND NOTES" with the `legend` flag is your legend; sheets
     with `other_discipline` titles (plumbing, structural...) need no image.
   * `thumbs $B --doc N` (1 image, more with `--start 16`): only if `doc`
     did not already tell you which sheets are the legend and the electrical
     plans.
   * `text PAGE --grep LEGEND` or `--grep RECEPT` (no image) finds where words
     sit on a sheet, in page pixels: use it to aim `marks` at the legend
     table instead of viewing the whole sheet.
   * Open the legend sheet (its `page` path from `doc`). Use `marks` on the
     legend's symbol column, then add each electrical symbol to the document
     legend with `legend add --mark ...` and a `--label` copied from the
     legend text. Aim for the 5-15 symbols that matter (receptacles, switches,
     lighting controls, luminaires and emergency lights, exit signs, panels,
     fire alarm, data). This is the most valuable step: every later view can
     show these crops with `--legend`.
   * `survey $B --doc N` with `--legend-pages`, the electrical sheet ids and
     a one-line note. **Fix the page list here**: `--label-pages` adds
     electrical plan pages the triage missed, `--skip-pages` removes queued
     pages that are not electrical (all as PDF page numbers, never sheet ids).
     If the set has no legend, say so in the note.
3. **Label each page** of the document, in order:
   * `view PAGE --legend --max-side 1400` (1 image). Decide the page kind.
   * Not an electrical plan (architectural, plumbing, structural, civil, HVAC,
     schedules, details, title sheet): one `assess_page` action, next page.
     You may decide this from the thumbs and `doc` titles without opening the
     page again.
   * Electrical plan: work region by region (about 1000-1600 page pixels
     square). `marks PAGE --region ... --legend` (1 image), compare each numbered
     candidate with the legend crops, and add the matches:
     `{"kind": "add_box", "mark": "m12", "family": "...", "variant": "..."}`.
     Symbols the marks missed (often those drawn on a wall line) can be added
     with an explicit `box`; tightening snaps it.
   * Check your boxes once with `view --regions` (up to 4 regions, 1 image).
   * Finish with `assess_page` (always, even with zero boxes).
4. **Keep going.** Finishing a document is not a reason to stop: run `next`
   again and continue with the next document. Stop only when you have opened
   12 images, `next` returns null, or a command keeps failing.

## Common mistakes (seen in earlier runs)

* **Solid black squares/rectangles on walls are structural columns**, not
  panelboards. A panelboard is a long thin rectangle against a wall, usually
  half-filled or hatched, with a panel name tag ("LP-1", "PP2A") next to it.
* **Do not put a box on empty floor or on furniture.** Before every `act`,
  check each box in the region view: the glyph must sit inside the box with a
  small margin. If you cannot point at the glyph, do not add the box.
* **Small dots or circles on walls joined by curved (often red) lines are
  devices on a circuit** — usually receptacles, switches or junction boxes.
  The curved lines are circuit homeruns, not symbols. Box the device at the
  end of the line, not the line.
* Table/chair outlines, door swings, dimension strings, grid bubbles and the
  hexagon/circle keynote tags with a number inside are **not** devices.
* Guessing `unspecified` for the variant is fine; guessing the family is not.
  If you cannot tell the family, leave the symbol out.
* **Emergency lights** (often half-shaded, or tagged "EM") use the `_emergency`
  variant of their fixture type (`troffer_2x4_emergency`, ...);
  `emergency_battery_light` is the twin-head battery unit. **Exit signs**: pick
  `exit_sign_single_face`, `exit_sign_double_face`, `exit_sign_single_arrow`,
  `exit_sign_double_arrow` when the legend says so, else `exit_sign`.
* **Lighting controls**: power packs (`control_relay` / `power_pack`,
  `power_pack_dimming`, usually a small box tagged "PP" near fixtures) are
  wired to low-voltage switches (`switch` / `low_voltage`,
  `low_voltage_dimmer`) or paired with wireless switches (`wireless`,
  `wireless_dimmer`, `wireless_multi_button`). Ceiling occupancy and vacancy sensors are `sensor`;
  wall-box occupancy switches are `switch` / `occupancy_sensor`.

* **Model proposals** (`review_source: "model"`, with a `model_confidence`)
  come from the fine-tuned detector. Treat them like any other proposal:
  check each against the legend at region zoom, keep correct ones (no action
  needed), fix the box or family with `update_box`, delete wrong ones with
  `delete_box`, and add what the model missed. Low-confidence boxes are the
  most likely to be wrong.
* **Extracted proposals** (`review_source: "devices_json"`) were pulled from
  the drawing set's text and geometry when the page was first opened; the
  browser annotator shows the same boxes. Their device is often only the
  family (variant `unspecified`) and some boxes sit on the tag text instead of
  the symbol: review them exactly like model proposals.
* Marks are candidates, not answers: text letters, room numbers, column
  bubbles and furniture can get a mark too. Only add a mark you can match to
  a legend symbol or a vocabulary symbol.

## Actions

`act.json` is a JSON list, applied in order, all-or-nothing:

```json
[
  {"kind": "add_box", "mark": "m12", "family": "receptacle", "variant": "duplex",
   "text_attributes": ["LR1B-15"]},
  {"kind": "add_box", "family": "switch", "variant": "three_way", "box": [990, 402, 1012, 430]},
  {"kind": "update_box", "id": "a0_3", "mark": "m20"},
  {"kind": "delete_box", "id": "a0_4"},
  {"kind": "assess_page", "page_kind": "electrical_plan", "symbols_present": "yes",
   "labeling_complete": true, "note": "E311 power and data, sector A"}
]
```

* `family` / `variant` must come from the vocabulary below; `unspecified`
  when you know the family but not the variant. A warning naming the
  vocabulary means you used an unknown name: fix it.
* `text_attributes`: short tag text printed next to the symbol (circuit
  "LR1B-15", fixture type "A2", "GFI", "WP", "EM"). Optional.
* `tighten: false` on an action keeps your box exactly as given.
* `assess_page` fields: `page_kind` (`electrical_plan`,
  `electrical_schedule_or_detail`, `other_discipline_plan`,
  `title_or_text_sheet`, `photo_unreadable`, `other`), `symbols_present`
  (`yes`, `no`, `unsure`), `labeling_complete` (true only if you covered every
  region), `note` (one line for the reviewer).
* You **cannot** mark a page as verified symbol-free; say
  `symbols_present: "no"` and a human decides.

An `"error"` reply means nothing was written: fix the JSON and retry.

## Rules

* Only write to pages of the documents in your batch. Any page of those
  documents may be labeled, but spend your images on the pages `next` gives
  you.
* Never edit JSON files directly; do not run `batches` or `sync.sh`.
* Reuse the same few PNG names in `$V`.
* If a command fails three times in a row, stop and report it.
* When you stop (budget used, batch done, or a blocking problem), run
  `status $B` and report: pages assessed, boxes added, legend entries added,
  and `IMAGE BUDGET USED` if that is why you stopped.

## Vocabulary

| family | discipline | variants |
|---|---|---|
| `air_terminal` | hvac | vav_box, cav_box, fan_powered, unspecified |
| `alarm_device` | fire_protection | horn_strobe, bell, waterflow, tamper, pull_station, unspecified |
| `audio_visual_device` | electrical | speaker_ceiling, intercom_weatherproof, intercom_substation, clock_outlet, speaker_wall, intercom_master_station, unspecified |
| `cctv_device` | electrical | dvr_nvr, camera_mini_dome, security_camera, camera_ptz, unspecified |
| `circuit_breaker` | electrical | single_pole, two_pole, three_pole, gfci, afci, unspecified |
| `conduit_fitting` | electrical | stub_up, stub_down, homerun, flexible_connection, unspecified |
| `control_relay` | electrical | lighting_relay, contactor, shunt_trip, time_clock, power_pack, power_pack_dimming, power_pack_auxiliary, room_controller, emergency_transfer_device, unspecified |
| `damper` | hvac | volume, motorized, backdraft, fire_smoke, unspecified |
| `data_outlet` | electrical | data_wall, wap, catv_outlet, data_floor, telephone_outlet, voice_data_combo, data_ceiling, fiber_optic_outlet, telephone_data_wall, telephone_wall, telephone_data_floor, fiber, coax, audio_visual, voice_data_two_gang, data_wall_two_gang, unspecified |
| `diffuser` | hvac | supply, return, exhaust, linear_slot, transfer, unspecified |
| `disconnect` | electrical | disconnect_switch, fused, non_fused, motor_rated, weatherproof, magnetic_motor_starter, vfd_asd, unspecified |
| `door` | architectural | single_swing, double_swing, sliding, revolving, overhead, unspecified |
| `drain` | plumbing | floor_drain, roof_drain, trench_drain, cleanout, unspecified |
| `electrical_meter` | electrical | kwh_meter, submeter, ct_cabinet, unspecified |
| `fan` | hvac | exhaust, supply, ceiling_fan, wall_fan, inline, unspecified |
| `fire_alarm_device` | electrical | smoke_detector, fire_alarm_control_panel, fire_alarm_strobe, annunciator_panel, duct_smoke_detector, manual_pull_station, heat_detector, tamper_switch, horn_and_strobe, smoke_detector_ceiling, waterflow_switch, speaker_and_strobe, horn_strobe_wall, strobe_wall, speaker_strobe_ceiling, strobe_ceiling, voice_evacuation_panel, horn_strobe_ceiling, speaker_strobe_wall, heat_detector_rate_of_rise, smoke_detector_sounder_base, strobe_unit, horn_unit, bell_and_strobe, beam_smoke_detector, carbon_monoxide_detector, door_holder, area_of_refuge_station, elevator_recall_detector, emergency_responder_radio, unspecified |
| `fire_damper` | hvac | fire, smoke, combination_fire_smoke, unspecified |
| `fire_protection_device` | fire_protection | fire_extinguisher, hose_cabinet, fdc, standpipe, unspecified |
| `generator` | electrical | standby, emergency, transfer_switch, unspecified |
| `grille` | hvac | return, exhaust, supply, transfer, louver, unspecified |
| `grounding_symbol` | electrical | ground_rod, ground_bus, bonding_jumper, unspecified |
| `junction_box` | electrical | junction_box, pull_box, wall, ceiling, floor, combination_floor_box, power_pole, telecom_pole, unspecified |
| `luminaire` | electrical | exit_sign, emergency_battery_light, recessed_downlight, exit_sign_wall, pole_floodlight, wall_mounted, exit_sign_ceiling, linear_strip, downlight, troffer_2x4, troffer_2x2, linear, strip_luminaire, wall_sconce, wallpack, pendant, track_light, high_bay, low_bay, spotlight, pole_luminaire, chandelier, surface_mounted, troffer_1x4, vanity, chandelier_emergency, downlight_emergency, high_bay_emergency, linear_emergency, linear_strip_emergency, low_bay_emergency, pendant_emergency, pole_floodlight_emergency, pole_luminaire_emergency, recessed_downlight_emergency, spotlight_emergency, strip_luminaire_emergency, surface_mounted_emergency, track_light_emergency, troffer_1x4_emergency, troffer_2x2_emergency, troffer_2x4_emergency, vanity_emergency, wall_mounted_emergency, wall_sconce_emergency, wallpack_emergency, emergency_remote_head, exit_sign_double_arrow, exit_sign_double_face, exit_sign_double_face_arrow, exit_sign_single_arrow, exit_sign_single_face, exit_emergency_combo, unspecified |
| `motor` | electrical | single_phase, three_phase, fractional_hp, motor_connection, unspecified |
| `motor_control` | electrical | vfd, motor_starter, magnetic_motor_starter, vfd_asd, unspecified |
| `panelboard` | electrical | panelboard, lighting_control_panel, automatic_transfer_switch, switchboard_switchgear, lighting_or_power_panel, distribution_panel, subpanel, switchboard, mcc, transfer_switch, unspecified |
| `plumbing_fixture` | plumbing | water_closet, lavatory, sink, urinal, shower, drinking_fountain, unspecified |
| `pump` | mechanical | centrifugal, circulator, sump, booster, condensate, unspecified |
| `receptacle` | electrical | duplex, quad, single, special_purpose, duplex_above_counter, duplex_gfci, floor_mounted, usb_charger_duplex, isolated_ground, weatherproof_gfci_duplex, ceiling_mounted, tamper_resistant, gfci, weatherproof, quad_gfci, afci, dedicated, duplex_receptacle_floor, duplex_receptacle_ceiling, duplex_receptacle_emergency, combination_floor_box, multioutlet_assembly, usb_receptacle, unspecified |
| `security_device` | electrical | card_reader, glass_break_detector, panic_duress_button, door_release_button, request_to_exit, gate_operator, gate_release_button, card_reader_long_range, door_contact_switch, electric_door_strike, security_keypad, card_reader_proximity, magnetic_door_lock, security_alarm_strobe, unspecified |
| `sensor` | electrical | thermostat, humidistat, occupancy_sensor, co2_sensor, daylight_photosensor, co_sensor, vacancy_sensor, occupancy_sensor_ceiling, differential_pressure_sensor, occupancy_sensor_wall, daylight_sensor, photocell, motion_detector, flow_switch, pressure_switch, occupancy_sensor_wall_corner, occupancy_sensor_dual_technology, occupancy_sensor_wireless_ceiling, occupancy_sensor_wireless_wall, unspecified |
| `sprinkler_head` | fire_protection | pendent, upright, sidewall, concealed, dry_pendent, unspecified |
| `switch` | electrical | single_pole, dimmer, three_way, four_way, time_switch, momentary, key_operated, emergency_power_off_pushbutton, three_way_dimmer, four_way_dimmer, double_pole, dimmer_switch, occupancy_sensor, vacancy_sensor, timer, low_voltage, pilot_light, weatherproof, motor_control, double_throw, occupancy_sensor_dimming, low_voltage_dimmer, wireless, wireless_dimmer, wireless_multi_button, unspecified |
| `telecom_device` | electrical | equipment_rack, equipment_cabinet, terminal_cabinet, intercom, speaker, camera, card_reader, nurse_call, unspecified |
| `thermostat` | hvac | wall_thermostat, programmable, humidistat, unspecified |
| `transformer` | electrical | transformer, dry_type, pad_mounted, step_down, step_up, unspecified |
| `valve` | mechanical | gate, ball, butterfly, check, balancing, control, prv, unspecified |
| `window` | architectural | fixed, casement, sliding, storefront, unspecified |
