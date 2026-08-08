---
version: alpha
name: "Linear Dark System"
description: "Linear's design system is a dark-first, high-density product UI built for engineering teams. The surface palette anchors on near-black (#08090a, #0f1011) with layered dark grays for sidebar, card, and panel differentiation. Typography is exclusively Inter Variable with precise negative letter-spacing at display sizes and Berkeley Mono for inline code. The radius language is deliberately small (2–6px dominant) with pill shapes reserved for badges and status chips. Elevation is expressed through subtle 1px inset borders and low-opacity drop shadows rather than dramatic layering. The primary CTA color is Linear's signature indigo (#5e6ad2), used on the Sign Up button and key links."
colors:
  brand-indigo: "#5e6ad2"
  background-elevated: "#23252a"
  background-primary: "#08090a"
  background-secondary: "#0f1011"
  white-surface: "#ffffff"
  text-primary: "#f7f8f8"
  text-quaternary: "#62666d"
  text-secondary: "#d0d6e0"
  text-tertiary: "#8a8f98"
  border-subtle: "#e2e4e7"
  white: "#ffffff"
  border-default: "#e2e4e7"
typography:
  display-hero:
    fontFamily: "Inter Variable"
    fontSize: "64px"
    fontWeight: "510"
    lineHeight: "1.1"
    letterSpacing: "-0.88px"
  title-1:
    fontFamily: "Inter Variable"
    fontSize: "40px"
    fontWeight: "510"
    lineHeight: "44px"
    letterSpacing: "-0.88px"
  title-2:
    fontFamily: "Inter Variable"
    fontSize: "18px"
    fontWeight: "400"
    lineHeight: "28.8px"
    letterSpacing: "-0.165px"
  body-regular:
    fontFamily: "Inter Variable"
    fontSize: "15px"
    fontWeight: "400"
    lineHeight: "24px"
    letterSpacing: "-0.165px"
  body-base:
    fontFamily: "Inter Variable"
    fontSize: "16px"
    fontWeight: "400"
    lineHeight: "24px"
  label-medium:
    fontFamily: "Inter Variable"
    fontSize: "13px"
    fontWeight: "510"
    lineHeight: "19.5px"
    letterSpacing: "-0.13px"
  label-small:
    fontFamily: "Inter Variable"
    fontSize: "12px"
    fontWeight: "510"
    lineHeight: "16.8px"
  caption:
    fontFamily: "Inter Variable"
    fontSize: "10px"
    fontWeight: "510"
    lineHeight: "15px"
  code-inline:
    fontFamily: "Berkeley Mono"
    fontSize: "14px"
    fontWeight: "400"
    lineHeight: "24px"
  nav-item:
    fontFamily: "Inter Variable"
    fontSize: "13px"
    fontWeight: "400"
    lineHeight: "19.5px"
    letterSpacing: "-0.13px"
rounded:
  radius-xs: "2px"
  radius-sm: "4px"
  radius-md: "6px"
  radius-lg: "8px"
  radius-xl: "12px"
  radius-2xl: "16px"
  radius-pill: "9999px"
spacing:
  spacing-1: "2px"
  spacing-2: "4px"
  spacing-3: "6px"
  spacing-4: "8px"
  spacing-5: "12px"
  spacing-6: "16px"
  spacing-7: "20px"
  spacing-8: "24px"
  spacing-9: "32px"
  spacing-10: "48px"
  spacing-11: "96px"
---

## Overview

Linear's design system is a dark-first, high-density product UI built for engineering teams. The surface palette anchors on near-black (#08090a, #0f1011) with layered dark grays for sidebar, card, and panel differentiation. Typography is exclusively Inter Variable with precise negative letter-spacing at display sizes and Berkeley Mono for inline code. The radius language is deliberately small (2–6px dominant) with pill shapes reserved for badges and status chips. Elevation is expressed through subtle 1px inset borders and low-opacity drop shadows rather than dramatic layering. The primary CTA color is Linear's signature indigo (#5e6ad2), used on the Sign Up button and key links.

**Signature traits:**
- Dual typeface system: Pairs Inter Variable and Berkeley Mono across the type hierarchy.
- Soft, rounded geometry: Generous corner rounding up to 9999px.
- Layered elevation: Depth comes from 5 validated shadow tokens.

## Colors

The palette uses 18 validated color tokens across 2 theme profiles. Semantic roles stay attached to observed usage so generation agents can choose accents without inventing new color meaning.

**Semantic naming:**
- **surface-background** maps to `background-primary`: Role "background" is grounded by usage context "Page-level background, deepest surface layer".
- **surface-text** maps to `text-primary`: Role "text" is grounded by usage context "Primary headings and body text on dark surfaces".
- **content-text** maps to `text-secondary`: Role "text" is grounded by usage context "Secondary labels, nav items, subheadings".
- **border-border** maps to `border-subtle`: Role "border" is grounded by usage context "Hairline dividers and subtle borders".

### Dark Theme

### Primary Brand
- **Brand Indigo** (#5e6ad2): Primary CTA button (Sign Up), key interactive links. Role: primary. {authored: rgb(94, 106, 210), space: rgb}

### Text Scale
- **Text Primary** (#f7f8f8): Primary headings and body text on dark surfaces. Role: text. {authored: rgb(247, 248, 248), space: rgb, alpha: 0.05}
- **Text Quaternary** (#62666d): Disabled states, faintest UI labels. Role: text. {authored: rgb(98, 102, 109), space: rgb}
- **Text Secondary** (#d0d6e0): Secondary labels, nav items, subheadings. Role: text. {authored: rgb(208, 214, 224), space: rgb}
- **Text Tertiary** (#8a8f98): Placeholder text, muted metadata, timestamps. Role: text. {authored: rgb(138, 143, 152), space: rgb}

### Interactive
- **Border Subtle** (#e2e4e7): Hairline dividers and subtle borders. Role: border. {authored: rgb(226, 228, 231), space: rgb}

### Surface & Shadows
- **Background Elevated** (#23252a): Elevated panels, sidebar, and modal surfaces. Role: background. {authored: rgb(35, 37, 42), space: rgb}
- **Background Primary** (#08090a): Page-level background, deepest surface layer. Role: background. {authored: rgb(8, 9, 10), space: rgb}
- **Background Secondary** (#0f1011): Secondary surface, panel and card fills. Role: background. {authored: rgb(15, 16, 17), space: rgb}
- **White Surface** (#ffffff): Toast/notification backgrounds, modal overlays. Role: background. {authored: rgb(255, 255, 255), space: rgb, alpha: 0.01}

### Light Theme

### Primary Brand
- **Brand Indigo** (#5e6ad2): Primary CTA and interactive accent. Role: primary. {authored: rgb(94, 106, 210), space: rgb}

### Text Scale
- **Text Primary** (#08090a): Primary text on light surfaces. Role: text. {authored: rgb(8, 9, 10), space: rgb}
- **Text Quaternary** (#62666d): Disabled and faintest labels. Role: text. {authored: rgb(98, 102, 109), space: rgb}
- **Text Secondary** (#d0d6e0): Secondary labels and nav items. Role: text. {authored: rgb(208, 214, 224), space: rgb}
- **Text Tertiary** (#8a8f98): Muted metadata and placeholder text. Role: text. {authored: rgb(138, 143, 152), space: rgb}

### Interactive
- **Border Default** (#e2e4e7): Component borders and dividers. Role: border. {authored: rgb(226, 228, 231), space: rgb}

### Surface & Shadows
- **Background Primary** (#f7f8f8): Page-level background in light contexts. Role: background. {authored: rgb(247, 248, 248), space: rgb, alpha: 0.05}
- **White** (#ffffff): Card and modal surfaces. Role: background. {authored: rgb(255, 255, 255), space: rgb, alpha: 0.01}

## Typography

Typography uses Inter Variable, Berkeley Mono across extracted hierarchy roles. Keep hierarchy mapped to these token rows before adding decorative type styles.

Mixes Inter Variable and Berkeley Mono for visual contrast. Weight range spans semi-bold, regular. Sizes range from 10px to 64px.

### Font Roles
- **Headline Font**: Inter Variable
- **Body Font**: Inter Variable

### Type Scale Evidence
| Role | Font | Size | Weight | Line Height | Letter Spacing | Stack / Features | Notes |
|------|------|------|--------|-------------|----------------|------------------|-------|
| Hero headline (h1), largest display text | Inter Variable | 64px | 510 | 1.1 | -0.88px | Inter Variable, SF Pro Display, -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Oxygen, Ubuntu, Cantarell, Open Sans, Helvetica Neue, sans-serif; features: "cv01", "ss03" | Extracted token |
| Section headings, major titles | Inter Variable | 40px | 510 | 44px | -0.88px | Inter Variable, SF Pro Display, -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Oxygen, Ubuntu, Cantarell, Open Sans, Helvetica Neue, sans-serif; features: "cv01", "ss03" | Extracted token |
| Sub-section headings | Inter Variable | 18px | 400 | 28.8px | -0.165px | Inter Variable, SF Pro Display, -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Oxygen, Ubuntu, Cantarell, Open Sans, Helvetica Neue, sans-serif; features: "cv01", "ss03" | Extracted token |
| Primary body copy, paragraph text | Inter Variable | 15px | 400 | 24px | -0.165px | Inter Variable, SF Pro Display, -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Oxygen, Ubuntu, Cantarell, Open Sans, Helvetica Neue, sans-serif; features: "cv01", "ss03" | Extracted token |
| Default UI text, nav items | Inter Variable | 16px | 400 | 24px | normal | Inter Variable, SF Pro Display, -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Oxygen, Ubuntu, Cantarell, Open Sans, Helvetica Neue, sans-serif; features: "cv01", "ss03" | Extracted token |
| Sidebar nav labels, button labels, tags | Inter Variable | 13px | 510 | 19.5px | -0.13px | Inter Variable, SF Pro Display, -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Oxygen, Ubuntu, Cantarell, Open Sans, Helvetica Neue, sans-serif; features: "cv01", "ss03" | Extracted token |
| Compact labels, badges, metadata chips | Inter Variable | 12px | 510 | 16.8px | normal | Inter Variable, SF Pro Display, -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Oxygen, Ubuntu, Cantarell, Open Sans, Helvetica Neue, sans-serif; features: "cv01", "ss03" | Extracted token |
| Micro labels, status indicators | Inter Variable | 10px | 510 | 15px | normal | Inter Variable, SF Pro Display, -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Oxygen, Ubuntu, Cantarell, Open Sans, Helvetica Neue, sans-serif; features: "cv01", "ss03" | Extracted token |
| Inline code snippets, variable names in issue descriptions | Berkeley Mono | 14px | 400 | 24px | normal | Berkeley Mono, ui-monospace, SF Mono, Menlo, monospace | Extracted token |
| Navigation item text | Inter Variable | 13px | 400 | 19.5px | -0.13px | Inter Variable, SF Pro Display, -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Oxygen, Ubuntu, Cantarell, Open Sans, Helvetica Neue, sans-serif; features: "cv01", "ss03" | Extracted token |

## Layout

Responsive system uses 2 breakpoint tier(s): mobile, desktop.

This system uses a 4px base grid with scale values 2, 4, 6, 8, 12, 16, 20, 24, 32, 48, 96.

### Responsive Strategy
- **mobile (<= 1280px)**: Constrain layout for small viewports and prioritize vertical stacking.
- **desktop (Unknown)**: Expand layout density and horizontal composition for wide viewports.

### Spacing System
| Token | Value | Px | Notes |
|------|-------|----|-------|
| spacing-1 | 2px | 2 | Extracted spacing token |
| spacing-2 | 4px | 4 | Extracted spacing token |
| spacing-3 | 6px | 6 | Extracted spacing token |
| spacing-4 | 8px | 8 | Extracted spacing token |
| spacing-5 | 12px | 12 | Extracted spacing token |
| spacing-6 | 16px | 16 | Extracted spacing token |
| spacing-7 | 20px | 20 | Extracted spacing token |
| spacing-8 | 24px | 24 | Extracted spacing token |
| spacing-9 | 32px | 32 | Extracted spacing token |
| spacing-10 | 48px | 48 | Extracted spacing token |
| spacing-11 | 96px | 96 | Extracted spacing token |

## Elevation & Depth

Keep depth flat unless validated shadow or interaction evidence appears in the extraction payload. Do not invent shadows beyond this evidence boundary.

### Shadow Evidence
| Shadow Token | Layers | Details |
|--------------|--------|---------|
| shadow-hairline | 1 | 0px 1.2px 0px 0px rgba(0, 0, 0, 0.03) |
| shadow-low | 1 | 0px 2px 4px 0px rgba(0, 0, 0, 0.4) |
| shadow-inset-overlay | 1 | inset 0px 0px 12px 0px rgba(0, 0, 0, 0.2) |
| shadow-border-inset | 1 | inset 0px 0px 0px 1px rgb(35, 37, 42) |
| shadow-border-outer | 1 | 0px 0px 0px 1px rgba(0, 0, 0, 0.2) |

### Interaction Signals
| Theme | Signal | Evidence |
|-------|--------|----------|
| Light | backdrop-filter | blur(4px) ; blur(20px) |
| Light | outline-color | rgba(0, 0, 0, 0) ; rgb(247, 248, 248) ; rgb(208, 214, 224) |
| Light | outline-width | 3px |
| Light | outline-offset | 0px |
| Light | transform | matrix(1, 0, 0, 1, 0, 0) ; matrix(0, 0, 0, 0, 0, 0) ; matrix(1, 0, 0, 1, -200, -200) |
| Dark | backdrop-filter | blur(4px) ; blur(20px) |
| Dark | outline-color | rgba(0, 0, 0, 0) ; rgb(247, 248, 248) ; rgb(208, 214, 224) |
| Dark | outline-width | 3px |
| Dark | outline-offset | 0px |
| Dark | transform | matrix(1, 0, 0, 1, 0, 0) ; matrix(0, 0, 0, 0, 0, 0) ; matrix(1, 0, 0, 1, -200, -200) |

## Shapes

Shape language maps directly to rounded tokens. Keep component corners consistent with the role mapping below before introducing bespoke geometry.

### Radius Roles
| Token | Value | Px | Role Mapping |
|------|-------|----|--------------|
| radius-xs | 2px | 2 | Hairline corner |
| radius-sm | 4px | 4 | Subtle corner |
| radius-md | 6px | 6 | Subtle corner |
| radius-lg | 8px | 8 | Control corner |
| radius-xl | 12px | 12 | Control corner |
| radius-2xl | 16px | 16 | Card corner |
| radius-pill | 9999px | 9999 | Large surface corner |

### Geometry Evidence
| Radius Token | Shape | Units |
|--------------|-------|-------|
| radius-xs | 2px | px |
| radius-sm | 4px | px |
| radius-md | 6px | px |
| radius-lg | 8px | px |
| radius-xl | 12px | px |
| radius-2xl | 16px | px |
| radius-pill | 9999px | px |

## Components

(none detected)

## Do's and Don'ts

Guardrails protect Dual typeface system, Soft, rounded geometry, Layered elevation without adding unsupported visual claims.

| Do | Don't |
|----|---------|
| Do maintain consistent spacing using the base grid | Don't make unsupported claims about absent visual features |
| Do maintain WCAG AA contrast ratios (4.5:1 for normal text) | Don't mix rounded and sharp corners in the same view |
| Do use the primary color only for the single most important action per screen |  |
| Do verify evidence before writing new design-system guidance |  |

## Responsive Evidence

### Breakpoints
| Name | Width | Key Changes |
|------|-------|-------------|
| Mobile | <= 600px | (max-width: 600px) |
| Mobile | <= 640px | (max-width: 640px) |
| Breakpoint 3 | <= 768px | (max-width: 768px) |
| Breakpoint 4 | <= 1024px | (max-width: 1024px) |
| Breakpoint 5 | <= 1280px | (max-width: 1280px) |
| Breakpoint 6 | Unknown | (hover: none) and (pointer: coarse) |

## Agent Prompt Guide

### Example Component Prompts
- Create button component using validated primary color role and spacing tokens.
- Create card component with mapped radius role and evidence-backed elevation.
- Create form input component using inferred typography hierarchy and border roles.

### Iteration Guide
1. Start with extracted palette and typography roles only.
2. Map spacing and radius directly from token tables before visual polish.
3. Apply component patterns one section at a time and compare against source intent.
4. Keep elevation claims tied to explicit evidence in output.
5. Iterate with smallest diffs and re-check section hierarchy after each change.
