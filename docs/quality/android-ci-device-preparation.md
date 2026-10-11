# Android CI device preparation

The Android quality device job builds the debug app and instrumentation APK before starting the emulator. The `performance` and `release` lanes also build the benchmark app, its instrumentation APK, and Macrobenchmark APK first. JVM tests that require fresh execution use the task-level `--rerun` option; compilation and packaging remain ordinary Gradle tasks so those prebuilt outputs can be reused after emulator startup. Connected-test XML freshness checks remain enabled.

If the preparation contract cannot reach its positive functional-empty oracle, it retains the last `window.xml`. The test-system classifier recognizes a Pixel Launcher ANR only when the hierarchy contains the Android-owned alert title plus both exact `Close app` and `Wait` actions. That case reports product `NOT_RUN` and test-system `FAILED`, with zero automatic retry and no dialog dismissal. Other system dialogs and product assertion failures remain unclassified by this signature.
