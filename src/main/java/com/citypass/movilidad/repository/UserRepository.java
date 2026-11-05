package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    /** Busca por el `sub` del Login Federado, que es la clave estable de la persona. */
    Optional<User> findByExternalUserId(String externalUserId);

    /** Usuario con lock pesimista, para evitar dos viajes activos simultáneos. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
