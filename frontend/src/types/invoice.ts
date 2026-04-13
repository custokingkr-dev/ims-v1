export interface InvoiceItem {
  id?: number;
  description: string;
  quantity: number;
  unitPrice: number;
  taxRate: number;
  lineTotal?: number;
}

export interface Invoice {
  id: number;
  invoiceNo: string;
  customerId: number;
  customerName: string;
  branchId: number;
  branchName: string;
  invoiceDate: string;
  dueDate: string;
  subtotal: number;
  discountPercent: number;
  discountAmount: number;
  taxAmount: number;
  grandTotal: number;
  paidAmount: number;
  balanceAmount: number;
  status: string;
  paymentStatus: string;
  approvalStatus: string;
  notes?: string;
  items: InvoiceItem[];
}

export interface Customer {
  id: number;
  code: string;
  name: string;
  email?: string;
  phone?: string;
  gstin?: string;
  addressLine?: string;
  branchId: number;
  branchName: string;
  active: boolean;
}

export interface Payment {
  id: number;
  invoiceId: number;
  invoiceNo: string;
  branchId: number;
  branchName: string;
  paymentDate: string;
  amount: number;
  paymentMode: string;
  referenceNo?: string;
  notes?: string;
  receivedBy: string;
}
