# Screen Sketch S Pen v1.3

Galaxy Tab / S Pen screen-overlay drawing app.

## v1.3 critical fixes
- Toolbar and drawing input now share one window in DRAW mode, so the drawing layer cannot steal toolbar touches.
- TOUCH mode removes the full-screen input window and keeps only the compact toolbar, allowing the app underneath to receive touch normally.
- Red EXIT button is always visible even when the toolbar is collapsed.
- Notification still provides an independent Exit action.
- Service is START_NOT_STICKY so it will not intentionally restart itself after being stopped.

## Export
- SAVE PNG: captures the current screen, composites annotations, and saves to Pictures/ScreenSketch.
- SAVE PDF: captures the current screen, composites annotations, and saves to Downloads/ScreenSketch.
- PRINT: captures the current screen and opens Android's system print dialog (which can also Save as PDF).

Android requires a MediaProjection permission confirmation when capturing the screen. Choose Entire screen for the expected full-screen result.

## Build
GitHub Actions builds `ScreenSketchSPen-v1.3-debug.apk` automatically on push to main.
