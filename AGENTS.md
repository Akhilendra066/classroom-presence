# Project rules
- Preserve the working firmware unless a hardware change is explicitly requested.
- presence-core is pure Kotlin: no Android/Firebase/UI imports.
- Match shared/beacon-contract.json; parse manufacturer payload, not BLE addresses.
- Never let missing or stale observations count as inside or outside.
- Backend owns final attendance and trusted roles. Demo is local and visibly labelled.
- Run core tests, backend tests, Android assemble and lint for changes to those layers.
- Real-device accuracy claims require a recorded field evaluation.
