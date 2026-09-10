// Retrigger #6 — path-normalization + worktree-cleanup fixes, see e0303aa / b51b066.
// Throwaway file for testing the automated defect-fix pipeline
// (see tasks/defect-autofix-pipeline-design.md). Safe to delete once the
// pipeline has picked this up and filed a Jira ticket for it.
import React from 'react';

export const DefectFixPipelineSmokeTest: React.FC = () => {
  const unusedVariable = 'this triggers no-unused-vars';
  return <div>smoke test</div>;
};
