package com.custoking.ims.identityservice.application;

import com.custoking.ims.identityservice.persistence.OperatorDirectoryRepository;
import com.custoking.ims.identityservice.persistence.OperatorDirectoryRepository.OperatorRow;
import com.custoking.ims.identityservice.persistence.OperatorDirectoryRepository.StaffName;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Answers "who should get this order" and "may this user be assigned to it" from the operator assignments, and gives
 * the notebook tracker its staff's names.
 */
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

    /** The most users one names request may ask about. */
    public static final int MAX_NAME_IDS = 200;

    /**
     * Full names of the staff among these users (active users holding an OPERATIONS or SUPERADMIN assignment); any other
     * id is left out rather than refused. Repeated, null and non-positive ids are ignored.
     */
    public List<StaffName> names(Collection<Long> userIds) {
        if (userIds == null) throw new IllegalArgumentException("userIds is required");
        if (userIds.size() > MAX_NAME_IDS) throw new IllegalArgumentException("At most " + MAX_NAME_IDS + " userIds per request");
        List<Long> ids = userIds.stream().filter(Objects::nonNull).filter(id -> id > 0).distinct().toList();
        return ids.isEmpty() ? List.of() : repository.staffNames(ids);
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
