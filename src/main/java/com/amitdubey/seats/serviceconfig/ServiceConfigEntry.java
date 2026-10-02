package com.amitdubey.seats.serviceconfig;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One runtime-tunable policy setting.
 *
 * <p>These rows are read into an in-memory snapshot on a schedule and <em>never</em>
 * queried from a request, which would add a round trip to the hottest path in the
 * service. Changing one is an update plus a reload, not a redeploy.
 *
 * <p>Infrastructure settings (pool size, timeouts, port, credentials) stay in
 * environment variables, because changing a pool size rebuilds the pool — a restart in
 * all but name.
 */
@Entity
@Table(name = "service_config")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ServiceConfigEntry {

    @Setter(AccessLevel.NONE)
    @Id
    @Column(name = "key")
    private String key;

    /** Use {@link #setValue(String)}, not the Lombok setter: it also bumps {@code updatedAt}. */
    @Setter(AccessLevel.NONE)
    @Column(name = "value", nullable = false)
    private String value;

    @Column(name = "description")
    private String description;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ServiceConfigEntry(String key, String value, String description) {
        this.key = key;
        this.value = value;
        this.description = description;
        this.updatedAt = Instant.now();
    }

    public void setValue(String value) {
        this.value = value;
        this.updatedAt = Instant.now();
    }
}
