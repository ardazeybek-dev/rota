# Contributing

Thanks for taking the time! Issues and pull requests are welcome.

## Setup

- JDK 17 or newer. Everything else (Gradle, Kotlin, Node for the JS tests) is downloaded by the wrapper.
- `./gradlew allTests :server:test :cli:test` must pass before a pull request.

## Ground rules

- **The solver must stay exact.** Any change to `core/.../solve` keeps `BruteForceTest` green on both
  platforms. If you add a new rule, add it to the random generator and to `bruteForce()` too.
- **New rules must be laminar** (nested in day ⊂ week ⊂ period) to be hard constraints. Anything else
  goes into the objective-neutral `Polish` pass and is reported, not promised.
- **Locale-safe strings.** Use Kotlin's `lowercase()` / `uppercase()` (locale-invariant), never the
  `java.lang.String` overloads that read the default locale.
- **Keep the web playground framework-free.** `web/` is plain HTML/CSS/JS that loads the Kotlin/JS
  build from `./lib`.

## Commit style

Short imperative subject (`Explain gaps caused by personal max`), body for the *why* when it isn't
obvious.
