import api from './api';
import { createCatalogClient } from '../generated/catalogClient';

export const catalogClient = createCatalogClient(api);
