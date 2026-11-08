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

    boolean existsByEmail(String email);

    /** Busca por el `sub` del Login Federado, que es la clave estable de la persona. */
    Optional<User> findByExternalUserId(String externalUserId);

    /** Usuario con lock pesimista, para evitar dos viajes activos simultáneos. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    /**
     * Fila del login con lock pesimista. Serializa los intentos concurrentes del mismo email, que
     * es lo que hace que dos fallos simultáneos cuenten dos y no uno: sin el lock, los dos leen el
     * mismo contador y el segundo pisa al primero.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.email = :email")
    Optional<User> findByEmailForUpdate(@Param("email") String email);
}
