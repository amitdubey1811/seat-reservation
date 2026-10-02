package com.amitdubey.seats.serviceconfig;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceConfigRepository extends JpaRepository<ServiceConfigEntry, String> {
}
