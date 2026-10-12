# Mate USB remote checks — 12 October 2026

Scope: directly connected Mate 10 Pro, Android 10, Universal debug edition. No TV hub or physical
remote was attached. No streaming playback was used. Reader device checks remain those documented
on 11 October; this session does not claim a new Meebook hardware pass.

## Change

RemoteInputPolicy defers focus inspection until a text-sensitive shortcut needs it. Unmapped keys,
navigation/escape actions, screen off, and already latched repeats/releases do not need that query.
Previously the service queried another app's root/focus before most of these actions, despite the
existing keyboard policy allowing them regardless of focus. Media and macro shortcuts still check
editing state. D-pad execution may still inspect the tree to move focus; this is not a zero-tree claim.

No gesture retry, macro loop, permissions or capture behavior was added. Saved identifiers and the
screen on/off label remain compatible. scripts/verify.ps1 automates the three-edition local gates
without publication or deployment.

## Verification

- All three editions: assemble, Android lint and unit tests pass. 21 tests per edition, 63 total.
- Mutation: invert the editing guard; both shortcut protection tests fail. Restore source and rerun
  the complete acceptance script: success. Logs are in workspace work/mate-usb-2026-10-12.
- Read-only focused review found no required correctness, security or architecture changes.
- On the original and updated installed builds, record Home + drawer tap + Settings tap. Each
  recording contained three steps. Playback reached Settings once and the Stop action overlay ended.
  The updated build stayed on Settings after completion. No infinite loop reproduced.
- Temporary QA recordings were deleted, preserving the user's saved recordings.
- Original build screen-off probe reached Android's Asleep state in 0.625 seconds including USB
  command/query overhead. The display was still transitioning at that first query; this is not
  a measured physical display-off latency or remote wake guarantee.
- Updated build: Android reported Asleep at 1.141 seconds and display OFF at 1.469 seconds,
  including USB input/query overhead. Woke through ADB and returned to Home. This is one on-screen
  button test, not physical remote latency, HDMI blanking or a measured improvement.

## Performance evidence and limits

One short gfxinfo sample per debug build, recording the macro replay flow: original 26 frames,
7 janky (26.92%), median 11 ms; updated 24 frames, 8 janky (33.33%), median 14 ms. The small,
non-identical samples include screen transitions and automation. They do not establish a speed
improvement or regression and do not exercise physical remote keys. Raw reports remain local in
work/mate-usb-2026-10-12/gfxinfo.txt and post-update-gfxinfo.txt. A focused Perfetto trace is the next
step for attributing screen-transition stalls; these frame counts do not identify a root cause.

## Still needs connected hardware

Physical Back/Power delivery, remote wake behavior, Huawei external-display terms dialog recording,
HDMI blanking and the user's reported remote delay remain unverified. USB virtual keys cannot prove
these work. Android-reserved keys cannot be made interceptable by this focus-query change.

## Macro form follow-up

Recording-name and step-wait forms now keep invalid input open with an inline correction message.
Wait values outside 0–10000 milliseconds are rejected rather than silently clamped; cancelling returns
to the step list. The recording form reacquires the accessibility service on each attempt and at delayed
start, so opening Accessibility and reconnecting support does not leave a captured, stale service.

All-edition build, lint and 63 existing tests passed again (macro-forms-final-build.log). Focused review
identified the stale-service risk and confirmed its correction. On the updated Universal installed on
the Mate: empty name retained the form; correcting it created a two-step QA recording; an out-of-range
wait (25010001) displayed an error without dismissing; correcting it to zero saved a visible 0 ms step;
that value persisted later, and Cancel returned to the unchanged step list. These are UI checks, not
a claim that hardware recording or service reconnection was exercised end-to-end.
