package com.custoking.ims.schoolcoreservice.application;

import com.custoking.ims.schoolcoreservice.erasure.StudentErasureJournal;
import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Transaction owner for student commands. All SQL, enrollments, review invalidation
 * and outbox writes join this transaction. External erasure intents still precede
 * mutation and survive rollback; after-commit import callbacks retain that boundary.
 * Offline recovery uses its own bounded TransactionTemplate instead.
 */
@Service
public class StudentCommands {
    private final StudentReadRepository students;

    public StudentCommands(StudentReadRepository students) {
        this.students = students;
    }

    @Transactional
    public Map<String, Object> createStudent(Map<String, Object> request) {
        return students.createStudent(request);
    }

    @Transactional
    public Map<String, Object> updateStudent(Long id, Map<String, Object> request) {
        return students.updateStudent(id, request);
    }

    @Transactional
    public Map<String, Object> attachPhoto(Long id, byte[] data, String contentType) {
        return students.attachPhoto(id, data, contentType);
    }

    @Transactional
    public Map<String, Object> previewImport(Map<String, Object> request) {
        return students.previewImport(request);
    }

    @Transactional
    public Map<String, Object> confirmImport(Map<String, Object> request) {
        return students.confirmImport(request);
    }

    @Transactional(timeout = 45)
    public Map<String, Object> deleteStudent(Long id, String confirmationAdmissionNumber) {
        return students.deleteStudent(id, confirmationAdmissionNumber);
    }

    @Transactional(timeout = 45)
    public Map<String, Object> reconcileErasure(StudentErasureJournal.IntentReference reference) {
        return students.reconcileErasure(reference);
    }

    @Transactional
    public Map<String, Object> createPromotionBatch(Map<String, Object> request) {
        return students.createPromotionBatch(request);
    }

    @Transactional
    public Map<String, Object> updatePromotionBatchItem(String batchId, String itemId, Map<String, Object> request) {
        return students.updatePromotionBatchItem(batchId, itemId, request);
    }

    @Transactional
    public Map<String, Object> applyPromotionBatch(String batchId) {
        return students.applyPromotionBatch(batchId);
    }

    @Transactional
    public Map<String, Object> initiateIdCardReview(Map<String, Object> request) {
        return students.initiateIdCardReview(request);
    }

    @Transactional
    public Map<String, Object> initiateFullNameVerification(Map<String, Object> request) {
        return students.initiateFullNameVerification(request);
    }

    @Transactional
    public Map<String, Object> initiateProfileVerification(Map<String, Object> request) {
        return students.initiateProfileVerification(request);
    }

    @Transactional
    public Map<String, Object> initiatePhotoVerification(Map<String, Object> request) {
        return students.initiatePhotoVerification(request);
    }

    @Transactional
    public Map<String, Object> verifyStudentProfile(Long studentId, Long schoolId, Long actorId) {
        return students.verifyStudentProfile(studentId, schoolId, actorId);
    }

    @Transactional
    public Map<String, Object> verifyStudentPhoto(Long studentId, Long schoolId, Long actorId) {
        return students.verifyStudentPhoto(studentId, schoolId, actorId);
    }

    @Transactional
    public Map<String, Object> updateReviewItem(String itemId, Map<String, Object> request) {
        return students.updateReviewItem(itemId, request);
    }

    @Transactional
    public Map<String, Object> verifyFullName(String itemId, Map<String, Object> request) {
        return students.verifyFullName(itemId, request);
    }

    @Transactional
    public Map<String, Object> completeCampaign(String campaignId, Long actorId) {
        return students.completeCampaign(campaignId, actorId);
    }

}
