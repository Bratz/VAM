/**
 * Template Selector Modal Component
 * 
 * A modal dialog for selecting and previewing hierarchy templates
 * during program initialization.
 * 
 * Usage:
 *   import TemplateSelectorModal from './TemplateSelectorModal';
 *   
 *   <TemplateSelectorModal
 *     isOpen={showModal}
 *     program={selectedProgram}
 *     onClose={() => setShowModal(false)}
 *     onSelect={(templateId) => handleInitialize(templateId)}
 *     loading={initLoading}
 *   />
 */

import React, { useState, useEffect } from 'react';
import {
  Play,
  Info,
  FolderTree,
  ChevronRight,
} from 'lucide-react';
import { Modal } from '../components/ui/enhanced';
import { Button, Badge } from '../components/ui';
import { cn } from '../utils';
import {
  HIERARCHY_TEMPLATES,
  TemplateConfig,
  getRecommendedTemplate,
  getTemplatesForProgramType,
} from '../config/templateHierarchy';

// ============================================================================
// TYPES
// ============================================================================

interface Program {
  id: string;
  programCode: string;
  programName: string;
  programType: string;
  currencyCode: string;
  status: string;
}

interface TemplateSelectorModalProps {
  /** Whether the modal is open */
  isOpen: boolean;
  /** The program to initialize hierarchy for */
  program: Program | null;
  /** Called when modal is closed */
  onClose: () => void;
  /** Called when a template is selected and confirmed */
  onSelect: (templateId: string) => void;
  /** Whether initialization is in progress */
  loading?: boolean;
}

// ============================================================================
// COMPONENT
// ============================================================================

const TemplateSelectorModal: React.FC<TemplateSelectorModalProps> = ({
  isOpen,
  program,
  onClose,
  onSelect,
  loading = false,
}) => {
  const [selectedTemplate, setSelectedTemplate] = useState<string | null>(null);
  const [previewTemplate, setPreviewTemplate] = useState<TemplateConfig | null>(null);

  // Auto-select recommended template when program changes
  useEffect(() => {
    if (program) {
      const recommended = getRecommendedTemplate(program.programType);
      if (recommended) {
        setSelectedTemplate(recommended.id);
        setPreviewTemplate(recommended);
      } else {
        // Fall back to first available template
        const templates = getTemplatesForProgramType(program.programType);
        if (templates.length > 0) {
          setSelectedTemplate(templates[0].id);
          setPreviewTemplate(templates[0]);
        }
      }
    }
  }, [program]);

  // Reset state when modal closes
  useEffect(() => {
    if (!isOpen) {
      setSelectedTemplate(null);
      setPreviewTemplate(null);
    }
  }, [isOpen]);

  if (!program) return null;

  // Categorize templates
  const relevantTemplates = HIERARCHY_TEMPLATES.filter((t) =>
    t.forProgramTypes.includes(program.programType)
  );
  const otherTemplates = HIERARCHY_TEMPLATES.filter(
    (t) => !t.forProgramTypes.includes(program.programType)
  );

  const handleTemplateClick = (template: TemplateConfig) => {
    setSelectedTemplate(template.id);
    setPreviewTemplate(template);
  };

  const handleConfirm = () => {
    if (selectedTemplate) {
      onSelect(selectedTemplate);
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      size="xl"
      title="Initialize Program Hierarchy"
    >
      <div className="flex gap-6 min-h-[500px] max-h-[70vh]">
        {/* ─────────────────────────────────────────────────────────────────── */}
        {/* LEFT PANEL: Template List */}
        {/* ─────────────────────────────────────────────────────────────────── */}
        <div className="w-1/2 overflow-y-auto pr-4 border-r border-neutral-200 dark:border-primary-800">
          <p className="text-sm text-neutral-600 mb-4 dark:text-neutral-300">
            Select a hierarchy template for{' '}
            <strong>{program.programName}</strong> ({program.programType})
          </p>

          {/* Recommended Templates */}
          {relevantTemplates.length > 0 && (
            <>
              <h3 className="text-xs font-semibold text-neutral-500 uppercase tracking-wider mb-2 dark:text-neutral-400">
                Recommended for {program.programType}
              </h3>
              <div className="space-y-2 mb-6">
                {relevantTemplates.map((template) => {
                  const Icon = template.icon;
                  const isSelected = selectedTemplate === template.id;

                  return (
                    <div
                      key={template.id}
                      onClick={() => handleTemplateClick(template)}
                      className={cn(
                        'p-4 border rounded-lg cursor-pointer transition-all',
                        isSelected
                          ? 'border-primary-500 bg-primary-50 ring-2 ring-primary-500 dark:bg-primary-800/40'
                          : 'border-neutral-200 hover:border-neutral-300 hover:bg-neutral-50 dark:border-primary-800'
                      )}
                    >
                      <div className="flex items-start gap-3">
                        <div
                          className={cn(
                            'w-10 h-10 rounded-lg flex items-center justify-center shrink-0',
                            template.bgColor
                          )}
                        >
                          <Icon className={cn('w-5 h-5', template.color)} />
                        </div>
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2 flex-wrap">
                            <span className="font-medium text-primary-900 dark:text-neutral-50">
                              {template.name}
                            </span>
                            {template.recommended && (
                              <Badge variant="success" size="sm">
                                Recommended
                              </Badge>
                            )}
                          </div>
                          <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">
                            {template.description}
                          </p>
                          <p className="text-xs text-neutral-400 mt-1">
                            {template.levels.length} hierarchy levels
                          </p>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            </>
          )}

          {/* Other Templates */}
          {otherTemplates.length > 0 && (
            <>
              <h3 className="text-xs font-semibold text-neutral-500 uppercase tracking-wider mb-2 dark:text-neutral-400">
                Other Templates
              </h3>
              <div className="space-y-2">
                {otherTemplates.map((template) => {
                  const Icon = template.icon;
                  const isSelected = selectedTemplate === template.id;

                  return (
                    <div
                      key={template.id}
                      onClick={() => handleTemplateClick(template)}
                      className={cn(
                        'p-3 border rounded-lg cursor-pointer transition-all',
                        isSelected
                          ? 'border-primary-500 bg-primary-50 ring-2 ring-primary-500 opacity-100 dark:bg-primary-800/40'
                          : 'border-neutral-200 hover:border-neutral-300 opacity-60 hover:opacity-100 dark:border-primary-800'
                      )}
                    >
                      <div className="flex items-center gap-3">
                        <div
                          className={cn(
                            'w-8 h-8 rounded-lg flex items-center justify-center shrink-0',
                            template.bgColor
                          )}
                        >
                          <Icon className={cn('w-4 h-4', template.color)} />
                        </div>
                        <div className="flex-1 min-w-0">
                          <span className="text-sm font-medium text-primary-900 dark:text-neutral-50">
                            {template.name}
                          </span>
                          <p className="text-xs text-neutral-500 dark:text-neutral-400">
                            {template.levels.length} levels •{' '}
                            {template.forProgramTypes.join(', ')}
                          </p>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            </>
          )}
        </div>

        {/* ─────────────────────────────────────────────────────────────────── */}
        {/* RIGHT PANEL: Template Preview */}
        {/* ─────────────────────────────────────────────────────────────────── */}
        <div className="w-1/2 overflow-y-auto">
          {previewTemplate ? (
            <div>
              {/* Template Header */}
              <div className="flex items-center gap-3 mb-4">
                <div
                  className={cn(
                    'w-12 h-12 rounded-xl flex items-center justify-center shrink-0',
                    previewTemplate.bgColor
                  )}
                >
                  <previewTemplate.icon
                    className={cn('w-6 h-6', previewTemplate.color)}
                  />
                </div>
                <div>
                  <h3 className="font-semibold text-primary-900 dark:text-neutral-50">
                    {previewTemplate.name}
                  </h3>
                  <p className="text-sm text-neutral-500 dark:text-neutral-400">
                    {previewTemplate.levels.length} hierarchy levels
                  </p>
                </div>
              </div>

              {/* Hierarchy Structure */}
              <div className="bg-neutral-50 rounded-lg p-4 mb-4 dark:bg-primary-950">
                <h4 className="text-sm font-medium text-primary-900 mb-3 dark:text-neutral-50">
                  Hierarchy Structure
                </h4>
                <div className="space-y-1">
                  {previewTemplate.levels.map((level, index) => (
                    <div key={level.levelNumber} className="flex items-start gap-3">
                      {/* Level Number Indicator */}
                      <div className="flex flex-col items-center">
                        <div
                          className={cn(
                            'w-8 h-8 rounded-full flex items-center justify-center text-sm font-medium shrink-0',
                            index === 0
                              ? 'bg-primary-600 text-white'
                              : index === previewTemplate.levels.length - 1
                              ? 'bg-success-600 text-white'
                              : 'bg-neutral-200 text-neutral-700 dark:text-neutral-200'
                          )}
                        >
                          {level.levelNumber}
                        </div>
                        {index < previewTemplate.levels.length - 1 && (
                          <div className="w-0.5 h-6 bg-neutral-300" />
                        )}
                      </div>

                      {/* Level Details */}
                      <div className="flex-1 pb-2 min-w-0">
                        <div className="flex items-center gap-2 flex-wrap">
                          <span className="font-medium text-primary-900 dark:text-neutral-50">
                            {level.levelName}
                          </span>
                          <Badge variant="default" size="sm">
                            {level.dimensionType}
                          </Badge>
                          {level.isRequired && (
                            <Badge variant="warning" size="sm">
                              Required
                            </Badge>
                          )}
                        </div>
                        {level.description && (
                          <p className="text-xs text-neutral-500 mt-0.5 dark:text-neutral-400">
                            {level.description}
                          </p>
                        )}
                        {level.allowedValues && level.allowedValues.length > 0 && (
                          <div className="flex flex-wrap gap-1 mt-1">
                            {level.allowedValues.slice(0, 4).map((v) => (
                              <span
                                key={v}
                                className="text-xs px-1.5 py-0.5 bg-neutral-200 rounded"
                              >
                                {v}
                              </span>
                            ))}
                            {level.allowedValues.length > 4 && (
                              <span className="text-xs text-neutral-500 dark:text-neutral-400">
                                +{level.allowedValues.length - 4} more
                              </span>
                            )}
                          </div>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              {/* What Will Be Created */}
              <div className="bg-info-50 border border-info-200 rounded-lg p-3 dark:bg-info-500/10 dark:border-info-500/30">
                <div className="flex items-start gap-2">
                  <Info className="w-4 h-4 text-info-600 mt-0.5 shrink-0 dark:text-info-300" />
                  <div className="text-sm text-info-800 dark:text-info-300">
                    <p className="font-medium">What will be created:</p>
                    <ul className="mt-1 space-y-1 text-info-700 dark:text-info-300">
                      <li>• Root hierarchy node for the program</li>
                      <li>
                        • <strong>Exception VA</strong> ({program.currencyCode}) -
                        catches unmatched transactions
                      </li>
                      <li>
                        • Level configuration with {previewTemplate.levels.length}{' '}
                        tiers
                      </li>
                    </ul>
                  </div>
                </div>
              </div>
            </div>
          ) : (
            /* Empty State */
            <div className="flex items-center justify-center h-full text-neutral-400">
              <div className="text-center">
                <FolderTree className="w-12 h-12 mx-auto mb-3" />
                <p>Select a template to preview</p>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* ─────────────────────────────────────────────────────────────────── */}
      {/* FOOTER */}
      {/* ─────────────────────────────────────────────────────────────────── */}
      <div className="flex justify-between items-center pt-4 border-t mt-4">
        <p className="text-xs text-neutral-500 dark:text-neutral-400">
          You can add Settlement VAs at any level after initialization
        </p>
        <div className="flex gap-2">
          <Button variant="ghost" onClick={onClose} disabled={loading}>
            Cancel
          </Button>
          <Button
            onClick={handleConfirm}
            disabled={!selectedTemplate || loading}
            loading={loading}
            leftIcon={<Play className="w-4 h-4" />}
          >
            Initialize Hierarchy
          </Button>
        </div>
      </div>
    </Modal>
  );
};

export default TemplateSelectorModal;