// This file exists solely to exercise the defect-fix pipeline's
// TypeScript type-checking step. It intentionally contains no
// unused variables.
import React from 'react';

const usedVariable = 'smoke-test';

const DefectFixPipelineSmokeTest: React.FC = () => {
  return <div>{usedVariable}</div>;
};

export default DefectFixPipelineSmokeTest;
