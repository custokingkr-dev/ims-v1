package com.custoking.ims.repo;
import com.custoking.ims.entity.AppUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface AppUserRepository extends JpaRepository<AppUserEntity, Long> {
    Optional<AppUserEntity> findByEmailIgnoreCase(String email);
    List<AppUserEntity> findAllByRoleIgnoreCase(String role);
    Optional<AppUserEntity> findFirstByRoleIgnoreCaseAndBranchId(String role, Long branchId);
    void deleteByRoleIgnoreCaseAndBranchId(String role, Long branchId);
    List<AppUserEntity> findAllByOrderByFullNameAsc();
}
