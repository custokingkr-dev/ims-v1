package com.custoking.ims.repo;
import com.custoking.ims.entity.FeeItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
public interface FeeItemRepository extends JpaRepository<FeeItemEntity, String> {
    List<FeeItemEntity> findByBand_IdOrderByCreatedAtAsc(String bandId);
    void deleteByBand_Id(String bandId);
    @Query("SELECT f.band.id, SUM(f.amount) FROM FeeItemEntity f WHERE f.band.id IN :bandIds GROUP BY f.band.id")
    List<Object[]> sumAmountByBandIds(@Param("bandIds") Collection<String> bandIds);
}
