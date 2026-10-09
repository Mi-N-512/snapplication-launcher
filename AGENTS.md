# Project checks

Read [CONSTRAINTS.md](CONSTRAINTS.md) before changing code. Its build, Android lint, unit-test and physical Reader checks are the acceptance bar; a compiled APK alone is not readiness.

Keep the editions distinct: Reader exposes reading only, Universal retains Read/Watch, TV retains its television controls. Do not publish releases or make the private repository public. Preserve local Android Studio/JDK configuration and user edits when syncing Downloads.

Class headers use detailed `/* ... */` comments explaining responsibilities, related classes, UI and extension points. Single-line comments use `//`. The simple analogy in MainActivity's header is intentional; do not apply that style to every class.
