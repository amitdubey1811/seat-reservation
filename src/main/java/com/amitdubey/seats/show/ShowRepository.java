package com.amitdubey.seats.show;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShowRepository extends JpaRepository<Show, UUID> {
}
