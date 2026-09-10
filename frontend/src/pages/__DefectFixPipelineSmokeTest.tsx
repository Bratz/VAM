// This file is a smoke-test fixture used to validate the defect-fix pipeline.
// It intentionally exercises a lint rule (@typescript-eslint/no-unused-vars)
// so that automated fixes can be verified end-to-end.

function smokeTestFunction(): number {
  const _unusedVariable = 42;
  return 1;
}

export default smokeTestFunction;
