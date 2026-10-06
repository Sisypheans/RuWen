# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
(`MAJOR.MINOR.PATCH`: incompatible change / feature added or changed / bug fix).

## [1.2.0] - 2026-10-06

### Added

- **Playback speed control** on the player screen (0.5× / 0.75× / 1.0× / 1.25× / 1.5×),
  replacing the shuffle button. The speed is a player-level setting: it is persisted
  immediately and re-applied whenever a queue is loaded, so it survives queue reloads.
- **Speed picker as a Material 3 bottom sheet**, with the active value marked by a check icon.
  The presets live in `PlaybackService.SPEED_PRESETS`, shared by the UI and the service.
- **Unified popup menu component** (`RuwenMenu`): icon on the trailing (right) side, one white
  surface for every menu in the app, and the popup right edge aligned with the overflow dots.

### Changed

- **Subtitles are never truncated.** Priority is "current line first":
  the previous / next context lines are dropped when the current line does not fit, and the
  current line steps down from 20sp to 13sp only if it still does not fit. Line counts are
  pre-measured with `StaticLayout`, so context lines never flash before disappearing.
- Playlist detail overflow menu (select / sort) is rendered by the shared menu component
  instead of the system overflow menu, so every menu in the app looks the same.
- Speed labels always keep at least one decimal place (`1.0×`, `0.5×`, `0.75×`, `1.25×`, `1.5×`).

### Removed

- Shuffle playback: button, service flag, broadcast extra, icon and all translations.

### Fixed

- Popup menu background no longer differs between screens: one popup menu style in the theme
  now covers both code-created menus and toolbar overflow menus.
- Menu popup width is measured from the label text; long labels are no longer squeezed to the
  112dp minimum width and cut off with an ellipsis.

[1.2.0]: https://github.com/Sisypheans/RuWen/releases/tag/v1.2.0
