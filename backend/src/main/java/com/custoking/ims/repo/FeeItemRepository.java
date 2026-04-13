package com.custoking.ims.repo;
import com.custoking.ims.entity.FeeItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface FeeItemRepository extends JpaRepository<FeeItemEntity, String> { List<FeeItemEntity> findByBand_IdOrderByCreatedAtAsc(String bandId); void deleteByBand_Id(String bandId); }
