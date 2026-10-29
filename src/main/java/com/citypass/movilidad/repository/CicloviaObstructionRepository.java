package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.CicloviaObstruction;
import com.citypass.movilidad.model.enums.ObstructionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CicloviaObstructionRepository extends JpaRepository<CicloviaObstruction, Long> {

    Optional<CicloviaObstruction> findByReclamoId(String reclamoId);

    List<CicloviaObstruction> findByStatus(ObstructionStatus status);
}
