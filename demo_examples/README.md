# Demo Examples — COVID-19 LFT Detector

Representative images covering the three valid outcome categories, plus a set of corrupted/unusable inputs that the system correctly rejects.  
Each valid image was processed through the full two-stage pipeline (YOLOv8-OBB → crop → YOLOv8-cls + classical line counter).

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

## Corrupted / Unusable Input

These are **not real LFT photographs**. They are failed web downloads that ended up in the original dataset — each file is a 1019×556 RGBA PNG where the entire canvas is fully transparent except for 18–42 solid-red pixels (a browser broken-image icon).

> **Note on YOLO accuracy:** In the full labeled dataset, YOLO correctly detected every real cassette. There are no genuine LFT photos where YOLO failed to localise a cassette. The system correctly returns "no cassette detected" for these files because there is genuinely nothing to detect.

| Image | App Result | Notes |
|-------|------------|-------|
| corrupted_01.png | **No cassette detected** | Transparent PNG; ~42 non-transparent pixels (browser broken-image icon) |
| corrupted_02.png | **No cassette detected** | Transparent PNG; ~21 non-transparent pixels |
| corrupted_03.png | **No cassette detected** | Transparent PNG; ~19 non-transparent pixels |
| corrupted_04.png | **No cassette detected** | Transparent PNG; ~18 non-transparent pixels |
| corrupted_05.png | **No cassette detected** | Transparent PNG; ~21 non-transparent pixels |

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
