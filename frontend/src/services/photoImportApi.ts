import api from './api';
import { createPhotoImportClient } from '../generated/photoImportClient';

export const photoImportClient = createPhotoImportClient(api);
