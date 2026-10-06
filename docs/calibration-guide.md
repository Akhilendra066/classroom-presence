# Calibration and accuracy evaluation

## Saved calibration

Calibrate each room once and publish the reviewed configuration. The backend stores
it against that room and reuses it automatically for future classes, independently
of class duration and attendance minimum. The teacher setup screen shows the saved
version. Recalibrate only after beacon placement/environment changes or when field
evaluation indicates the saved thresholds need adjustment. Existing sessions keep
their original calibration snapshot.


The default -72/-75 dBm inside thresholds and -82/-85 dBm outside thresholds are experimental values. No measured accuracy is implied. The pure classifier first checks scan success, freshness and minimum registered-beacon counts. It then evaluates independent strong/weak rules. Weak agreement yields outside; contradictory or boundary evidence yields uncertain; missing measurements yield insufficient data.

## Collect recordings

Use teacher → class → Room calibration. Keep the four ESPs in their final positions and verify their identities. Label center, each corner, edges, doorway, hallway and adjacent-room positions. At each position collect multiple 25-second Wi-Fi/BLE recordings. Include hand, pocket and bag placement, phone orientation, sitting/standing, normal occupants and doors open/closed. Recordings include device/OS, labelled location, timestamps and raw samples stored locally.

At least three inside and three outside recordings are required by the app/server before publication. This is a collection prerequisite, not an accuracy guarantee. The app shows count, median, min/max and standard deviation; exports include per-beacon raw samples. Threshold publication is a deliberate teacher action. New configurations are versioned and do not rewrite existing session evidence.

## Evaluate

Separate collection runs used for tuning from held-out runs used for evaluation. Do not split near-identical measurements from one burst between tuning and evaluation. Replay exported observation windows through the pure core with the candidate configuration, or collect fresh diagnostic scans at independent positions.

Report inside/outside confusion matrix, false-inside and false-outside rates, uncertainty rate, insufficient-data rate, valid-slot coverage, transition delay and battery usage. Report metrics per phone/model and aggregate them only with stated sample counts. Include doorway and adjacent-room cases. Establish acceptable targets with your supervisor before acceptance.

If inside/outside distributions overlap, preserve uncertain results and teacher review. Reconsider placement and power consistency instead of forcing a confident classification. Calibration is deterministic threshold selection, not ML training or exact coordinate estimation.

## Attendance policy

New class sessions choose checkpoints automatically: `max(5, ceil(class minutes / 3))`. A 15-minute class has five checkpoints; a 60-minute class has 20. Checkpoints are spread evenly over the selected duration. The required inside count is the teacher's minimum presence converted proportionally to whole checkpoint windows and rounded up. Uncertain, missing and insufficient data never contribute to that inside minimum. Older sessions retain their original attendance policy and checkpoint schedule.

Display hysteresis never supplies attendance evidence. Every checkpoint has independent preliminary status; absent/stale scans do not reuse an earlier inside status. Raw BLE samples are filtered within the burst, not smoothed across twenty minutes of checkpoint history.

## Raw versus uploaded data

Calibration raw samples remain in Room and can be exported by the teacher. Regular checkpoints and cloud calibration publication send fresh per-beacon medians, reducing cloud storage. All required reason codes, configuration/algorithm versions and preliminary results are recorded. The Supabase backend receives no biometric data and uses neither a BLE address nor a BLE device name as identity.
