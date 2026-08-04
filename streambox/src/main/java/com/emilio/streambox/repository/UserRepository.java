package com.emilio.streambox.repository;

import com.emilio.streambox.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

}