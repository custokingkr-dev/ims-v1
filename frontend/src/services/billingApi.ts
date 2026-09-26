import api from './api';
import { createBillingClient } from '../generated/billingClient';

export const billingClient = createBillingClient(api);
