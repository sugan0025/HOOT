# Windows companion memory investigation — 2026-11-17

## Scope and evidence

Work is isolated in `codex/possibility`, based on v3.0.0. Installed HOOT PID 20872 remained
running from `%LOCALAPPDATA%\Programs\Hoot\Hoot.exe`; no installed files or settings were changed.
Its startup log identifies ANGLE EGL with WinUIComposition on Intel Iris Xe graphics.
Live working set varied around 482–523 MiB during this investigation; this metric is not
the same as private committed memory or Task Manager's private working set.

A small standalone Avalonia process rendered the real `BlobatarControl` in a transparent
104×104 window at a maximum 30 rendered frames per second. It did not initialize HOOT's
conversion, updater, focus, music, or application services. Each process exited itself.
The probe and captured output are retained locally in `.cache/memory-probe/` (Git ignored).

| Rendering mode | Working set at 5 seconds | Working set at 20 seconds | Private commit at 20 seconds |
| --- | ---: | ---: | ---: |
| Avalonia GPU default / WinUIComposition | 183 MiB | 233 MiB | 218 MiB |
| Same, 32 MiB Skia resource cache limit | 159 MiB | 226 MiB | 212 MiB |
| GPU / DirectComposition | 158 MiB | 208 MiB | 211 MiB |
| GPU / redirection surface | 149 MiB | 206 MiB | 196 MiB |
| Software, before lifecycle fix | 67 MiB | 60 MiB | 17 MiB |
| New application default, with lifecycle fix | 76 MiB | 69 MiB | 20 MiB |

A separate 60-second GPU run reached 321 MiB working set and 321 MiB private commit.
Forcing GC and waiting for finalizers at 30 seconds reduced the managed heap to below
1 MiB, but did not release the native allocations or stop their growth. Forced GC is
diagnostic only and is not part of the fix.

The fixed default finished a 60-second run at 76 MiB working set and 26 MiB private
commit, with a 1 MiB managed heap. The optional 3D renderer also ran successfully with
the new default: 74 MiB working set and 31 MiB private commit after 20 seconds. These are
standalone renderer measurements, not the memory use of the complete installed application.

These comparisons isolate a major memory cost to the GPU rendering path on this machine.
They do not identify the individual ANGLE/Skia/Intel allocation stacks, prove an unbounded
leak, or predict the full application's final memory footprint. GPU and process memory
counters must not be added together as independent allocations.

## Changes

- Windows auto/unset rendering now uses software rendering, avoiding the observed native
  GPU growth. `FILEE_RENDERING=gpu` explicitly restores Avalonia's acceleration defaults;
  existing `dcomp`, `redirection`, and `software` overrides remain supported.
- Blob animation loops observe ancestor visibility, window hiding/minimization, and the
  animation enable switch. They stop before advancing hidden physics and restart when
  visible. An epoch invalidates queued callbacks after stop/restart or detachment.
- No forced garbage collection, working-set trimming, temporary-file deletion, renderer
  geometry changes, or installed-app restart is used as a memory fix.

## Validation and limitations

Release build succeeded. Focused `WindowsRenderingTests` and `BlobatarTests`: 61 passed,
zero failed or skipped. This includes new hide/show, ancestor visibility, animation-toggle,
minimize/restore, and GPU-override coverage. Existing unrelated deprecation warnings remain.

The Windows default affects all HOOT windows, so release acceptance still needs visual and
performance checks of large PDF previews, main-window glass, radial menus, DPI scaling, and
the companion/music popups. The lightweight renderer measurements do not replace those
checks. The installed application has deliberately not been updated.
