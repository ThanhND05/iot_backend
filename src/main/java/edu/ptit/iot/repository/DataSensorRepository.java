package edu.ptit.iot.repository;

import edu.ptit.iot.entity.DataSensor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DataSensorRepository extends JpaRepository<DataSensor, Integer> {
    
    @Query("SELECT d FROM DataSensor d WHERE (:sensorType = 'All' OR d.sensor.name = :sensorType) " +
           "AND (cast(:startTime as timestamp) IS NULL OR d.createdAt >= :startTime) " +
           "AND (cast(:endTime as timestamp) IS NULL OR d.createdAt <= :endTime)")
    Page<DataSensor> searchDataSensors(
            @Param("sensorType") String sensorType,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            Pageable pageable);

    @Query("SELECT d FROM DataSensor d WHERE d.sensor.name = :sensorName ORDER BY d.createdAt DESC LIMIT :limit")
    List<DataSensor> findTopNBySensorNameOrderByCreatedAtDesc(@Param("sensorName") String sensorName, @Param("limit") int limit);
}
