# UnitMind Evaluation Dataset

This directory contains the frozen v1 input corpus for the paid UnitMind generation evaluation.
Every case is a self-contained Java 17 source fixture with a strict `case.json` manifest. Generated
tests and reports are written only below `build/reports/unitmind-eval`.

## Profiles

- `smoke`: 12 representative targets, one attempt each.
- `regular`: all 60 targets, one attempt each.
- `release`: all 60 targets, three independent attempts each.

```bash
UNITMIND_API_KEY=... \
UNITMIND_API_URL=... \
UNITMIND_MODEL=... \
./gradlew unitMindEval -PunitmindEvalProfile=regular
```

Optional properties:

- `-PunitmindEvalCase=mock-007` selects one case after the complete corpus preflight.
- `-PunitmindEvalResume=<run-id>` continues an existing checkpointed run.
- `-PunitmindEvalPromoteBaseline=true` explicitly promotes a compatible summary to `baselines/`.

The ordinary `test` task excludes the real-API runner. Dataset or environment failures fail the
evaluation task; generated-code quality is reported as a trend and does not fail the task.

## Dataset contract

The loader enforces 50 method targets and 10 class targets, exactly 12 smoke cases, difficulty
distribution 18/30/12, branch bands 10/25/15, and 12 open-source-derived method fixtures.
Open-source fixtures retain upstream provenance in their manifest and are isolated into evaluation
packages so all cases can coexist in one IntelliJ test module.

Every manifest records the immutable case ID, `METHOD`/`CLASS` scope, source kind, difficulty,
branch band, target source and fully-qualified class, the complete parameter-type signature for
method targets, source release, `jdkVersion` (`17`), license, existing-test flag, smoke membership,
and capability tags. Open-source cases additionally record the repository URL and original release
path; evaluation never downloads a branch at runtime.

Each completed attempt is appended to `attempts.jsonl` before the next case begins. Reports include
the end-to-end and conditional funnels, method and class tracks, difficulty/tag breakdowns, failure
reasons, phased model telemetry, and release stability. Promotion is explicit and is refused when
the run contains an infrastructure error.

Class targets run a dedicated final JUnit loop after aggregate compilation. The report preserves
both `JUnit@1` and `JUnit@Budget`, class runtime/compile repair rounds, fixture-repair escalation,
regression rejections and stop reasons. This loop executes the complete generated class after every
accepted candidate, protects previously passing tests, and intentionally does not calculate class
coverage.
