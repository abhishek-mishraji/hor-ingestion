package com.handsoffretail.verifone.stores;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: refresh master.stores from the VERIFONE rows of auth_db core.stores (VERIFONE_SERVICE_PLAN.md §6.2).
 * One transaction on verifone_db; auth_db is only read. Rules:
 * new id → insert; same id and code → update when something changed; same id, other code → conflict, untouched
 * (codes are baked into S3 keys and loaded data); code owned by another id → conflict, skipped;
 * no longer VERIFONE or gone → INACTIVE (never deleted).
 */
@Service
public class StoreSyncService {

    private static final Logger log = LoggerFactory.getLogger(StoreSyncService.class);

    private final AuthStores authStores;
    private final MasterStores masterStores;
    private final Clock clock;

    public StoreSyncService(AuthStores authStores, MasterStores masterStores, Clock clock) {
        this.authStores = authStores;
        this.masterStores = masterStores;
        this.clock = clock;
    }

    @Transactional
    public Result sync(long actorId) {
        Instant now = clock.instant();
        Map<Long, MasterStore> current = masterStores.all().stream()
                .collect(Collectors.toMap(MasterStore::storeId, Function.identity()));
        Map<String, Long> idByCode = current.values().stream()
                .collect(Collectors.toMap(MasterStore::storeCode, MasterStore::storeId));
        Set<Long> seen = new HashSet<>();
        List<Long> unchangedIds = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        int inserted = 0;
        int updated = 0;
        for (AuthStore source : authStores.verifoneStores()) {
            MasterStore existing = current.get(source.storeId());
            if (existing == null) {
                Long owner = idByCode.get(source.storeCode());
                if (owner != null) {
                    conflicts.add(new Conflict(source.storeId(), source.storeCode(),
                            "store code already used by store " + owner + " in verifone_db"));
                    continue;
                }
                masterStores.insert(source, now);
                idByCode.put(source.storeCode(), source.storeId());
                inserted++;
            } else if (!existing.storeCode().equals(source.storeCode())) {
                conflicts.add(new Conflict(source.storeId(), source.storeCode(), "STORE_CODE_MISMATCH: verifone_db has "
                        + existing.storeCode() + "; a store code cannot change once data may be loaded"));
            } else if (existing.sameAs(source)) {
                unchangedIds.add(source.storeId());
            } else {
                masterStores.update(source, now);
                updated++;
            }
            seen.add(source.storeId());
        }
        masterStores.touch(unchangedIds, now);
        int deactivated = 0;
        for (MasterStore store : current.values()) {
            if (!seen.contains(store.storeId()) && store.active()) {
                masterStores.deactivate(store.storeId(), now);
                deactivated++;
            }
        }
        Result result = new Result(inserted, updated, deactivated, unchangedIds.size(), conflicts);
        log.info("Store sync by admin {}: {} inserted, {} updated, {} deactivated, {} unchanged, {} conflict(s)",
                actorId, inserted, updated, deactivated, unchangedIds.size(), conflicts.size());
        return result;
    }

    /** What a sync changed. */
    public record Result(int inserted, int updated, int deactivated, int unchanged, List<Conflict> conflicts) {

        public Result {
            conflicts = List.copyOf(conflicts);
        }
    }

    /** A store the sync could not apply. */
    public record Conflict(long storeId, String storeCode, String reason) {
    }
}
