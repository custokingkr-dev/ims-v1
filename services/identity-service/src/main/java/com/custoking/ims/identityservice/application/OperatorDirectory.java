package com.custoking.ims.identityservice.application;

import com.custoking.ims.identityservice.persistence.OperatorDirectoryRepository;
import com.custoking.ims.identityservice.persistence.OperatorDirectoryRepository.OperatorRow;
import org.springframework.stereotype.Service;

import java.util.List;

/** Answers "who should get this order" and "may this user be assigned to it" from the operator assignments. */
@Service
public class OperatorDirectory {
    private final OperatorDirectoryRepository repository;

    public OperatorDirectory(OperatorDirectoryRepository repository) {
        this.repository = repository;
    }

    /** The operator to auto-assign for a school and catalogue item, or why none was picked. */
    public OperatorSelection.Result resolve(long schoolId, String catalogCategory) {
        String category = required(schoolId, catalogCategory);
        return OperatorSelection.choose(repository.itemSpecific(schoolId, category), repository.schoolWide(schoolId));
    }

    public boolean eligible(long userId, long schoolId, String catalogCategory) {
        return userId > 0 && repository.eligible(userId, schoolId, required(schoolId, catalogCategory));
    }

    public List<OperatorRow> list(Long schoolId, String catalogCategory) {
        return repository.list(schoolId, CatalogScope.normalize(catalogCategory));
    }

    private static String required(long schoolId, String catalogCategory) {
        if (schoolId <= 0) throw new IllegalArgumentException("schoolId is required");
        String category = CatalogScope.normalize(catalogCategory);
        if (category == null) throw new IllegalArgumentException("catalogCategory is required");
        return category;
    }
}
