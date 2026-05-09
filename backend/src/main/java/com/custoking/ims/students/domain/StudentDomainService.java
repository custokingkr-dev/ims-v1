package com.custoking.ims.students.domain;

import com.custoking.ims.common.domain.StudentStatus;
import com.custoking.ims.entity.StudentEntity;
import com.custoking.ims.repo.StudentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class StudentDomainService {

    private final StudentRepository studentRepository;

    public StudentDomainService(StudentRepository studentRepository) {
        this.studentRepository = studentRepository;
    }

    public boolean isAdmissionNumberUnique(String admissionNo) {
        return studentRepository.findByAdmissionNoIgnoreCase(admissionNo).isEmpty();
    }

    public boolean isAdmissionNumberUniqueExcludingStudent(String admissionNo, Long excludeStudentId) {
        Optional<StudentEntity> existing = studentRepository.findByAdmissionNoIgnoreCase(admissionNo);
        return existing.isEmpty() || existing.get().getId().equals(excludeStudentId);
    }

    public void validateStudentStatusTransition(StudentStatus currentStatus, StudentStatus newStatus) {
        switch (currentStatus) {
            case ACTIVE -> {
                if (newStatus != StudentStatus.INACTIVE && newStatus != StudentStatus.TRANSFERRED
                        && newStatus != StudentStatus.GRADUATED) {
                    throw new IllegalStateException("Cannot transition ACTIVE student to " + newStatus);
                }
            }
            case INACTIVE -> {
                if (newStatus != StudentStatus.ACTIVE) {
                    throw new IllegalStateException("INACTIVE student can only be reactivated");
                }
            }
            case TRANSFERRED, GRADUATED ->
                throw new IllegalStateException("Cannot change status from terminal state " + currentStatus);
        }
    }

    public List<StudentEntity> findStudentsBySchool(Long schoolId) {
        return studentRepository.findBySchool_IdOrderByFullNameAsc(schoolId);
    }

    public List<StudentEntity> findStudentsByClass(String classId) {
        return studentRepository.findBySchoolClass_IdOrderByFullNameAsc(classId);
    }

    public List<StudentEntity> findStudentsByClassAndSection(String classId, String sectionId) {
        return studentRepository.findBySchoolClass_IdAndSection_IdOrderByFullNameAsc(classId, sectionId);
    }

    public long countStudentsInSchool(Long schoolId) {
        return studentRepository.countBySchool_Id(schoolId);
    }
}
