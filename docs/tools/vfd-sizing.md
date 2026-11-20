# VFD Sizing

Module: `feature/vfdsizing` (math in `domain/VfdSizing.kt`). Tables: `core/electrical`
(`MotorFullLoadCurrent.kt`, `Ampacity.kt`, `StandardOcpd.kt`). Edition: **NEC (NFPA 70) 2023**.

The motor is always three-phase (the drive output). The supply into the drive is three-phase or single-phase.

## Rules

| Output | Rule |
|---|---|
| Table FLC | Table 430.250 (induction), by HP and voltage. 430.6(A)(1): conductors and protection use the table, not the nameplate. |
| Min. drive output current | Nameplate FLA if entered, else the table FLC. Compare with the drive's normal-duty (variable torque) or heavy-duty (constant torque) continuous rating. *Guidance.* |
| Single-phase input | Drive output rating ≈ 2× the motor current, or per the manufacturer's derate. *Rule of thumb, not code.* |
| Input conductors | 430.122(A): ≥ 125 % of the drive's rated input current. If not entered, 125 % of the table FLC is used (an approximation, flagged). |
| Output conductors | 430.122(B) / 430.22: ≥ 125 % of the table FLC. The "Suitable for Output Motor Conductor Protection" exception is noted, not applied. |
| Conductor size | Smallest size in Table 310.16 (copper or aluminum, 60 or 75 °C column) with ampacity ≥ required. If none, the fewest parallel sets (1/0 AWG or larger each, up to 6). |
| Short-circuit protection | Table 430.52(C)(1) maximums (250 % inverse-time breaker, 175 % time-delay fuse) rounded up to the next 240.6(A) rating (430.52(C)(1)(a)). The drive manufacturer's marked limits take precedence (430.130(A)(2)). |
| Disconnect | ≥ 115 % of the drive's input current (430.128). |
| Overload | Max 125 % of nameplate FLA (115 % for motors without SF ≥ 1.15 or ≤ 40 °C rise), 430.32(A)(1); the drive's electronic overload usually provides it (430.124(A)). |

## Data sources

Each table was checked against the printed NEC 2023 book:
Table 430.248 and 430.250 (pp. 70-363/364), Table 310.16 (p. 70-187), Table 240.6(A) (p. 70-124).
`MotorFullLoadCurrent` also holds Table 430.248 (single-phase motors) and the 115 / 2300 V columns; the
tool uses only Table 430.250 at 200-575 V (2300 V is Part XI and not covered).

## Limits

- Ambient temperature and more-than-three-conductor adjustment (310.15), voltage drop, and raceway fill are not applied.
- 110.14(C) termination limits are only applied through the 60 / 75 °C column the user picks.
- 240.4(D) small-conductor limits are not applied (240.4(G) exempts motor circuits).
- Instantaneous-trip breakers, non-time-delay fuses, 430.52(C)(1)(b) increases and synchronous/two-phase motors are not covered.
- Drive ratings (altitude, ambient and carrier-frequency derating, overload capacity) come from the manufacturer.
- The drive rating for kW entry converts at 0.7457 kW/HP to the next standard HP (2 % tolerance).
