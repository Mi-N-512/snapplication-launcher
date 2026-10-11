# Reader code review — 11 October 2026

Baseline: `eaecf69`; reviewed `4980ce9` plus the subsequent format-dispatch fix. Separate read-only standards and spec reviews were used. Required device and build checks are recorded in reader-qa-2026-10-09.md.

## Standards

No hard violation of the documented standards found. Two judgment-call findings:

1. P2, repeated format classification had a real bug: generic-MIME images passed Library recognition but failed in ReaderPanel; MIME-only comics could also fail. **Fixed** by sharing ReadingFormats.inlineKind between routing and decoding, with a regression test for both cases.
2. P3, the Read and Watch capture code repeats part of the cache-writing sequence. Optional maintenance improvement; no failure demonstrated. Deferred to avoid changing the working Watch capture path during a Reader fix.

## Spec

No actionable findings in the final source review. The change follows the automatic still-preview, Reader-only, supported-book resume, file-format and portrait-sizing requirements. No scope creep identified.

Summary: Standards 0 hard violations, 1 corrected P2 and 1 optional P3; Spec 0 findings. No unresolved correctness finding from these reviews.
