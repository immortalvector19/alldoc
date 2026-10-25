# AllDoc Minimalist Design System

> **Design Principle**: *Less, but better.* (Dieter Rams / Apple Notes / Things 3 / Linear / early Google Keep).  
> Calm, quiet, fast, typographic. Every element must earn its place.

---

## 1. What Was Removed and Why

The previous iteration suffered from decorative excess common in mobile templates: gradients, multi-colored squircle badges, drop shadows, carousels, quick-action tiles, and colored borders. These were systematically eliminated:

| Removed Element | Why It Was Removed | Minimalist Replacement |
|---|---|---|
| **Gradients & Glows** | Created visual vibration and looked styled for the sake of styling. | Flat, monochrome surfaces (`#FFFFFF` / `#0B0B0C`). |
| **Squircle Logo Tile ("AD")** | Redundant branding taking up valuable vertical screen estate. | Plain typographic title `"AllDoc"` (28sp bold). |
| **Colorful Format Badges** (Red, Blue, Green, Orange, Purple) | Visual clutter that drew the eye away from file names and content. | Small neutral typographic labels (`PDF`, `DOCX`, `XLSX`) in secondary text (`#6B6B6B`). |
| **Quick Action Cards & Carousel** | Redundant duplication of documents already listed right below. | A single, clean, fast list of recent files. |
| **Raised Center Scan FAB & Drop Shadows** | Artificial 3D elevation that clashed with flat typographic hierarchy. | Flat, restrained "+" action button in accent color (`#5B5BF0`). |
| **Stacked Cards Inside Cards & Heavy Borders** | Heavy boxes made the UI feel heavy and boxy. | Flat rows separated by 1dp hairline dividers (`#ECECEC` / `#232326`). |

---

## 2. Color System

The palette is strictly near-monochrome with **one** functional accent color:

### Light Theme
- **Background**: `#FFFFFF` (pure) or `#FAFAFA` (subtle warm-neutral)
- **Primary Text**: `#111111` (high contrast, crisp)
- **Secondary / Meta Text**: `#6B6B6B` (calm, legible)
- **Hairlines & Dividers**: `#ECECEC` (1dp subtle separation)
- **Surface Variant / Input Fills**: `#F5F5F7` (soft neutral background for inputs)
- **Accent**: `#5B5BF0` (Indigo — used *only* for the primary action button, active state, and links)

### Dark Theme
- **Background**: `#0B0B0C` (soft true-dark, never harsh pure black)
- **Surface**: `#141416` (subtle contrast against background)
- **Primary Text**: `#F2F2F2` (clean, non-glaring)
- **Secondary / Meta Text**: `#8A8A8E` (subdued, readable)
- **Hairlines & Dividers**: `#232326` (1dp hairline)
- **Surface Variant / Input Fills**: `#1C1C1F`
- **Accent**: `#7E7EFC` (refined indigo with appropriate dark-mode contrast)

> **Dynamic Color (Material You)**: Remains **OFF** by default so this calm typographic identity is preserved. Users who prefer OS-matched wallpaper colors can explicitly opt-in via Settings.

---

## 3. Typography Scale

Hierarchy is achieved purely through font size, weight, and whitespace — **never through colored boxes or borders**.

| Style | Size | Weight | Line Height | Letter Spacing | Color & Usage |
|---|---|---|---|---|---|
| **Large Title** | 28sp | SemiBold | 34sp | -0.75sp | Primary text. Screen headers ("AllDoc", "Settings"). |
| **Title Medium** | 18sp | SemiBold | 24sp | -0.3sp | Primary text. Document names, sheet headers. |
| **Section Label** | 13sp | Medium | 18sp | +0.5sp | Secondary text, uppercase tracking. Group dividers ("RECENT", "FAVORITES"). |
| **Body** | 16sp | Normal | 24sp | +0.1sp | Primary text. Text editor content, reading mode paragraphs. |
| **Meta / Subtext** | 13sp | Normal | 18sp | 0sp | Secondary text. Date, file size, page numbers. |
| **Format Tag** | 11sp | Medium | 14sp | +0.5sp | Secondary text. Format indicators (`PDF`, `DOCX`, `XLSX`). |

---

## 4. Spacing, Layout & Shapes

- **8dp Grid**: Margins and padding strictly align to multiples of 8dp (8dp, 16dp, 24dp).
- **Page Margins**: 16dp to 24dp of breathing room.
- **Corner Radii**:
  - Inputs & Search: 12dp (or subtle pill for search)
  - Bottom Sheets: 16dp top radius
  - Dialogs: 12dp
  - Buttons & Chips: 8dp
- **Elevation**: 0dp. Flat surfaces separated by whitespace and 1dp hairline dividers.

---

## 5. Screen Architecture & Before/After Comparison

### Home Screen
- **Before**: Gradient headers, "AD" brand squircle, colorful quick actions row (Scan, Open, Tools, Vault), recent horizontal carousel, category chip row, multi-colored format badges on bordered cards, raised center circular FAB, and a multi-tab bottom bar.
- **After**:
  - Top: Title `"AllDoc"` in plain text (28sp), clean search icon, and overflow menu.
  - Search: Clean, flat, subtle search field with placeholder and clear button.
  - Section Header: `"RECENT DOCUMENTS"` in 13sp secondary uppercase text.
  - List: Flat row items separated by 1dp hairlines. Each row shows:
    - Small neutral 3-4 letter format indicator (`PDF`, `DOCX`, `XLSX`) in secondary color.
    - File title in 15sp semi-bold primary text.
    - Single line of meta (`Oct 25, 2026 · 1.2 MB`).
    - More options menu button (48dp touch target).
  - Primary Action: One flat "+" button in `#5B5BF0` (bottom right or bottom center) opening a clean bottom sheet with:
    - **Open File** (SAF document picker)
    - **Scan Document** (ML Kit document scanner / basic camera fallback)
    - **New Note** (Plain text / markdown note)
  - Empty State: A single sentence `"No documents yet."` and an `"Open File"` button.

### Document Viewer & Editor
- **Before**: Heavy top bar with multiple colorful badges, colored OLE2 warning banners, sticky edit bars, and always-visible tool buttons.
- **After**:
  - **Reading Mode by Default**: When opened, documents enter full-focus reading mode. The chrome is quiet and minimal.
  - **Top Bar**: Minimal back arrow, document title, and a simple overflow menu.
  - **Bottom Bar**: A single slim toolbar with single-color icons (Search, Annotate/Edit, Share, Protect) that auto-hides during scrolling or single-tap reading.
  - **Legacy OLE2 Banner**: Quiet 1dp outlined notification card with clean text: *"Legacy binary format. Extracted read-only text."* and an *"Open In..."* text button.

---

## 6. Motion & Accessibility

- **Subtle Motion**: Transitions use 150ms–200ms fades and subtle vertical slides (`tween(180, easing = FastOutSlowInEasing)`). No bounce or playful springs.
- **Respect System Settings**: If `AccessibilityManager.isTouchExplorationEnabled` or system animator duration is disabled, animations are skipped.
- **Touch Targets**: All interactive icons maintain at least 48x48dp touch bounds.
- **Contrast Ratios**:
  - Primary text (`#111111` on `#FFFFFF`): **18.5:1** (exceeds WCAG AAA 7:1)
  - Secondary text (`#6B6B6B` on `#FFFFFF`): **5.4:1** (exceeds WCAG AA 4.5:1)
  - Dark mode text (`#F2F2F2` on `#0B0B0C`): **16.2:1** (exceeds WCAG AAA 7:1)
