package edu.ptit.iot.repository;

import edu.ptit.iot.entity.Action;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ActionRepository
        extends JpaRepository<Action, Integer>,
        JpaSpecificationExecutor<Action> {

    Optional<Action> findFirstByDeviceIdOrderByCreatedAtDesc(Integer deviceId);
}
