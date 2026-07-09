export const STUDENT_PHOTO_MAX_BYTES = 5 * 1024 * 1024;
export const STUDENT_PHOTO_MAX_LABEL = '5 MB';
export const STUDENT_PHOTO_ACCEPTED_TYPES = ['image/jpeg', 'image/png', 'image/webp'];

export function validateStudentPhotoFile(file: File): string | null {
  if (!STUDENT_PHOTO_ACCEPTED_TYPES.includes(file.type)) {
    return 'Only JPG, PNG, or WEBP files are allowed.';
  }
  if (file.size > STUDENT_PHOTO_MAX_BYTES) {
    return `Photo must be ${STUDENT_PHOTO_MAX_LABEL} or smaller.`;
  }
  return null;
}
