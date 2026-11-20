# Voltage Drop

Module: `feature/voltagedrop` (math in `domain/VoltageDrop.kt`). Tables: `core/electrical`
(`ConductorResistance.kt`, `ConductorImpedance.kt`).

## Formulas

```
single-phase:  VD = 2  · I · L · Z / 1000 / sets
three-phase:   VD = √3 · I · L · Z / 1000 / sets
% drop = 100 · VD / V        voltage at load = V − VD
max one-way length (ft) = target% / 100 · V · 1000 · sets / (k · I · Z)      k = 2 or √3
```

`I` amps, `L` one-way feet, `V` source voltage (line-to-line for three-phase), `Z` Ω per 1000 ft of
one conductor, `sets` identical parallel conductors per phase (current shared equally).

`Z` depends on the method:

- **DC (Table 8 / K-factor):** `Z = R`, NEC Chapter 9 Table 8, DC resistance at 75 °C, *uncoated* copper
  or aluminum.
- **AC (Table 9):** `Z = R·PF + X·sin(acos PF)` with Table 9's AC resistance for the conduit type and
  its reactance (PVC and aluminum conduit share one X, steel has its own). Default PF 0.85, which
  reproduces Table 9's "effective Z" columns.

Smallest size: the first size in the method's table (smallest first) whose drop is within the target
with every other input unchanged. Max length is rounded **down** to 1/64 in.

## Data sources

NEC (NFPA 70) Chapter 9, Table 8 (copper 18 AWG to 2000 kcmil, aluminum 12 AWG to 2000 kcmil) and
Table 9 (copper 14 AWG to 1000 kcmil, aluminum 12 AWG to 1000 kcmil), values as printed in the
2017 to 2023 editions. 12 AWG copper is 1.93 Ω/kft (editions before about 2008 printed 1.98).
Table 9 does not list 18/16 AWG or sizes above 1000 kcmil, so the AC method offers only listed sizes.

## Limits

- Conductors assumed at 75 °C; no temperature correction.
- Table 9 is for three single conductors in conduit; it is also used for single-phase. Multi-conductor
  cables, cable tray, direct burial and other spacings are not modelled.
- Balanced load, one voltage and current for the whole run. No branch tapering.
- Ampacity, derating, terminal temperature and overcurrent protection are not checked.
- 3 % / 5 % are guidance (NEC 210.19 / 215.2 informational notes), not requirements; the target is the user's.
