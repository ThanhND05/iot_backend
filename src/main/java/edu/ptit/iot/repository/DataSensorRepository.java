package edu.ptit.iot.repository;

import edu.ptit.iot.entity.DataSensor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface DataSensorRepository
        extends JpaRepository<DataSensor, Integer>,
        JpaSpecificationExecutor<DataSensor> {

    Page<DataSensor> findBySensor_NameOrderByCreatedAtDesc(
            String sensorName,
            Pageable pageable);
}
