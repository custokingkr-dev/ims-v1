import { useState } from 'react';

export interface QuotationForm {
  vendorName: string;
  amount: string;
  deliveryTimeline: string;
  notes: string;
  documentUrl: string;
}

export interface FirefightingForm {
  title: string;
  category: string;
  estimatedBudget: string;
  urgency: string;
  requiredByDate: string;
  summary: string;
  quotations: QuotationForm[];
}

const emptyQuote = (): QuotationForm => ({ vendorName: '', amount: '', deliveryTimeline: '', notes: '', documentUrl: '' });

const initForm = (): FirefightingForm => ({
  title: '', category: 'Furniture & fixtures', estimatedBudget: '', urgency: 'MEDIUM',
  requiredByDate: '', summary: '', quotations: [emptyQuote(), emptyQuote(), emptyQuote()],
});

/** Encapsulates all firefighting-domain state for UnifiedWorkspacePage. */
export function useFirefightingFeature() {
  const [ffForm, setFfForm] = useState<FirefightingForm>(initForm());
  const [ffStep, setFfStep] = useState<1 | 2 | 3>(1);
  const [ffSaving, setFfSaving] = useState(false);
  const [ffError, setFfError] = useState('');
  const [ffApprovalNotes, setFfApprovalNotes] = useState<Record<string, string>>({});
  const [ffApprovalDetails, setFfApprovalDetails] = useState<unknown[]>([]);
  const [ffApprovalLoading, setFfApprovalLoading] = useState(false);
  const [ffDraftSaving, setFfDraftSaving] = useState(false);
  const [ffEditingCode, setFfEditingCode] = useState<string | null>(null);
  const [ffExistingQuotes, setFfExistingQuotes] = useState<unknown[]>([]);
  const [saFfRequests, setSaFfRequests] = useState<unknown[]>([]);
  const [saFfLoading, setSaFfLoading] = useState(false);
  const [ffTrackOpen, setFfTrackOpen] = useState(false);
  const [ffTrackRequest, setFfTrackRequest] = useState<unknown>(null);
  const [ffTimeline, setFfTimeline] = useState<unknown[]>([]);
  const [ffTimelineLoading, setFfTimelineLoading] = useState(false);

  const setFfQuote = (idx: number, field: string, value: string) =>
    setFfForm(f => {
      const quotations = f.quotations.map((q, i) => i === idx ? { ...q, [field]: value } : q);
      return { ...f, quotations };
    });

  const resetFfForm = () => { setFfForm(initForm()); setFfStep(1); setFfError(''); };

  return {
    ffForm, setFfForm, resetFfForm,
    ffStep, setFfStep,
    ffSaving, setFfSaving,
    ffError, setFfError,
    ffApprovalNotes, setFfApprovalNotes,
    ffApprovalDetails, setFfApprovalDetails,
    ffApprovalLoading, setFfApprovalLoading,
    ffDraftSaving, setFfDraftSaving,
    ffEditingCode, setFfEditingCode,
    ffExistingQuotes, setFfExistingQuotes,
    saFfRequests, setSaFfRequests,
    saFfLoading, setSaFfLoading,
    ffTrackOpen, setFfTrackOpen,
    ffTrackRequest, setFfTrackRequest,
    ffTimeline, setFfTimeline,
    ffTimelineLoading, setFfTimelineLoading,
    setFfQuote,
    emptyQuote,
  };
}
