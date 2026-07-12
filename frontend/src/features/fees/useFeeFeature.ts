import { useRef, useState } from 'react';
import type { BandForm, FeeAssignForm, FeeFilters, FeeStructureData, PaymentForm, SelectionState } from './types';

const DEFAULT_PAYMENT_FORM: PaymentForm = { studentId: '', studentName: '', amount: '', paymentMode: 'UPI', notes: '' };
const DEFAULT_ASSIGN_FORM: FeeAssignForm = { studentId: '', bandId: '', paymentSchedule: '', bandDiscount: '0', manualDiscount: '0', surcharge: '2' };
const DEFAULT_BAND_FORM: BandForm = { name: '', classFrom: '1', classTo: '5', discount: '0', schedules: ['Annual'] };
const DEFAULT_FEE_STRUCTURE: FeeStructureData = {
  academicYear: 'Current academic year',
  academicYearId: '',
  bands: [],
};
const DEFAULT_SELECTION: SelectionState = { classId: '', sectionId: '', studentId: '' };

/** Encapsulates all fee-domain state for UnifiedWorkspacePage. */
export function useFeeFeature() {
  const [paymentForm, setPaymentForm] = useState<PaymentForm>(DEFAULT_PAYMENT_FORM);
  const [feeAssignForm, setFeeAssignForm] = useState<FeeAssignForm>(DEFAULT_ASSIGN_FORM);
  const [feeFilters, setFeeFilters] = useState<FeeFilters>({ className: '', sectionName: '' });
  const [feeClasses, setFeeClasses] = useState<unknown[]>([]);
  const [assignOptions, setAssignOptions] = useState<{ sections: unknown[]; students: unknown[] }>({ sections: [], students: [] });
  const [assignSelection, setAssignSelection] = useState<SelectionState>(DEFAULT_SELECTION);
  const [paymentOptions, setPaymentOptions] = useState<{ sections: unknown[]; students: unknown[] }>({ sections: [], students: [] });
  const [paymentSelection, setPaymentSelection] = useState<SelectionState>(DEFAULT_SELECTION);
  const [paymentDuePreview, setPaymentDuePreview] = useState<unknown | null>(null);
  const [paymentError, setPaymentError] = useState('');
  const [paymentSuccess, setPaymentSuccess] = useState('');
  const [reportOptions, setReportOptions] = useState<{ sections: unknown[] }>({ sections: [] });
  const [reportRows, setReportRows] = useState<unknown[]>([]);
  const [overdueRows, setOverdueRows] = useState<unknown[]>([]);
  const [reportLoading, setReportLoading] = useState(false);
  const [selectedReportStudentId, setSelectedReportStudentId] = useState<string | null>(null);
  const [feeLoadError, setFeeLoadError] = useState('');
  const [reminderSaving, setReminderSaving] = useState(false);
  const [reminderNotice, setReminderNotice] = useState('');

  // Fee structure
  const [feeItemForm, setFeeItemForm] = useState({ bandId: '', itemName: '', frequency: 'Annual', amount: '' });
  const [feeStructureData, setFeeStructureData] = useState<FeeStructureData>(DEFAULT_FEE_STRUCTURE);
  const [feeStructureLoading, setFeeStructureLoading] = useState(false);
  const [showFeeItemForm, setShowFeeItemForm] = useState(false);
  const [showBandForm, setShowBandForm] = useState(false);
  const [bandForm, setBandForm] = useState<BandForm>(DEFAULT_BAND_FORM);
  const [editingBandId, setEditingBandId] = useState('');
  const [confirmDeleteBandId, setConfirmDeleteBandId] = useState('');
  const [expandedBandIds, setExpandedBandIds] = useState<string[]>([]);
  const [feeAssignHint, setFeeAssignHint] = useState('');
  const [feeAssignError, setFeeAssignError] = useState('');
  const [feeStructureError, setFeeStructureError] = useState('');
  const [feeStructureToast, setFeeStructureToast] = useState('');
  const [editingFeeItem, setEditingFeeItem] = useState<unknown | null>(null);
  const [confirmRemoveFeeItemId, setConfirmRemoveFeeItemId] = useState('');
  const discountTimers = useRef<Record<string, number>>({});

  return {
    paymentForm, setPaymentForm,
    feeAssignForm, setFeeAssignForm,
    feeFilters, setFeeFilters,
    feeClasses, setFeeClasses,
    assignOptions, setAssignOptions,
    assignSelection, setAssignSelection,
    paymentOptions, setPaymentOptions,
    paymentSelection, setPaymentSelection,
    paymentDuePreview, setPaymentDuePreview,
    paymentError, setPaymentError,
    paymentSuccess, setPaymentSuccess,
    reportOptions, setReportOptions,
    reportRows, setReportRows,
    overdueRows, setOverdueRows,
    reportLoading, setReportLoading,
    selectedReportStudentId, setSelectedReportStudentId,
    feeLoadError, setFeeLoadError,
    reminderSaving, setReminderSaving,
    reminderNotice, setReminderNotice,
    feeItemForm, setFeeItemForm,
    feeStructureData, setFeeStructureData,
    feeStructureLoading, setFeeStructureLoading,
    showFeeItemForm, setShowFeeItemForm,
    showBandForm, setShowBandForm,
    bandForm, setBandForm,
    editingBandId, setEditingBandId,
    confirmDeleteBandId, setConfirmDeleteBandId,
    expandedBandIds, setExpandedBandIds,
    feeAssignHint, setFeeAssignHint,
    feeAssignError, setFeeAssignError,
    feeStructureError, setFeeStructureError,
    feeStructureToast, setFeeStructureToast,
    editingFeeItem, setEditingFeeItem,
    confirmRemoveFeeItemId, setConfirmRemoveFeeItemId,
    discountTimers,
  };
}
