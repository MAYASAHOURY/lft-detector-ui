# Demo Examples — COVID-19 LFT Detector

Representative images covering all four outcome categories.  
Each image was processed through the full two-stage pipeline (YOLOv8-OBB → crop → YOLOv8-cls + classical line counter).

---

## Positive (C + T lines detected)

| Image | Expected | App Result | Confidence | Notes |
|-------|----------|------------|------------|-------|
| positive_01.jpg | Positive | **Positive** | 100% | Bold C + T bands; classic unambiguous positive |
| positive_02.jpg | Positive | **Positive** | 100% | Clear double-line, slightly faint T line still detected |
| positive_03.jpg | Positive | **Positive** | 100% | High-contrast positive, both lines clearly separated |
| positive_04.jpg | Positive | **Positive** | 100% | Slight shadow in result window; T line still resolved |
| positive_05.jpg | Positive | **Positive** | 100% | Multiple cassettes in frame; each detection independent |

---

## Negative (C line only — no T line)

| Image | Expected | App Result | Confidence | Notes |
|-------|----------|------------|------------|-------|
| negative_01.jpg | Negative | **Negative** | 100% | Single control line (C) only; clean result window |
| negative_02.jpg | Negative | **Negative** | 100% | Consistent C-line position; result window clear of T signal |
| negative_03.jpg | Negative | **Negative** | 100% | Faint smudge in T zone; correctly classified as no test line |
| negative_04.jpg | Negative | **Negative** | 100% | Bright control line; no T-line artefact |
| negative_05.jpg | Negative | **Negative** | 100% | Good cassette angle; single band confirmed |

---

## Invalid (control line absent — result unreadable)

| Image | Expected | App Result | Confidence | Notes |
|-------|----------|------------|------------|-------|
| invalid_01.png | Invalid | **Invalid** | 100% | No control line present; T-zone also blank |
| invalid_02.png | Invalid | **Invalid** | 100% | Real-world screenshot; control window completely empty |
| invalid_03.png | Invalid | **Invalid** | 100% | Insufficient sample volume; no C line formed |
| invalid_04.png | Invalid | **Invalid** | 100% | Reagent degradation; result window shows no bands |
| invalid_05.png | Invalid | **Invalid** | 100% | Expired test strip; no control line visible |

---

## Not Detected (YOLO found no cassette)

| Image | Expected | App Result | Confidence | Notes |
|-------|----------|------------|------------|-------|
| not_detected_01.png | — | **No cassette detected** | N/A | Browser error icon (1019×556 transparent PNG, 19 non-transparent pixels); no real cassette |
| not_detected_02.png | — | **No cassette detected** | N/A | Browser error icon (42 non-transparent pixels); failed web download |
| not_detected_03.png | — | **No cassette detected** | N/A | Same format — transparent canvas with red 1px error indicator |
| not_detected_04.png | — | **No cassette detected** | N/A | Same format — saved from broken image link |
| not_detected_05.png | — | **No cassette detected** | N/A | Same format — dataset corruption; not a real LFT photo |

> **Why "not detected" matters:** The system correctly refuses to emit a result when no cassette is found, rather than guessing. These five files represent corrupted/missing image downloads in the original dataset — YOLO's correct response is zero detections.

---

## Pipeline summary

```
Input image
  └─▶ Stage 1: YOLOv8-OBB (best.pt)
        └─▶ Bounding box per cassette (rotated)
              └─▶ Stage 2: Crop → YOLOv8-cls + Classical line counter
                    └─▶ Fusion decision → Positive / Negative / Invalid
```

**Medical decision rules enforced:**
- **Positive** = C line + T line both visible
- **Negative** = C line visible, no T line
- **Invalid** = C line absent (control failed), regardless of T
