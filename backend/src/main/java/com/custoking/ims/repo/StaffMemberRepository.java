package com.custoking.ims.repo;
import com.custoking.ims.entity.StaffMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface StaffMemberRepository extends JpaRepository<StaffMemberEntity, Long> {
    List<StaffMemberEntity> findBySchool_IdOrderByNameAsc(Long schoolId);
}
