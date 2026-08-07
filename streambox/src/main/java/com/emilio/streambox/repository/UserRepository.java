package com.emilio.streambox.repository;

import com.emilio.streambox.entity.User;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    // Se utiliza optional para manejar el caso en que no se encuentre un usuario con el email proporcionado 
    // y no devolver un null.
    
    Optional<User> findByEmail(String email);

}