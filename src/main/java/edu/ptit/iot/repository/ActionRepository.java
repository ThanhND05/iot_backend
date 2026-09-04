package edu.ptit.iot.repository;

import edu.ptit.iot.entity.Action;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface ActionRepository extends JpaRepository<Action, Integer> {

    @Query("SELECT a FROM Action a WHERE (:deviceName IS NULL OR a.device.name = :deviceName) " +
           "AND (cast(:startTime as timestamp) IS NULL OR a.createdAt >= :startTime) " +
           "AND (cast(:endTime as timestamp) IS NULL OR a.createdAt <= :endTime)")
    Page<Action> searchActions(
            @Param("deviceName") String deviceName,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            Pageable pageable);
}
