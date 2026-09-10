package com.citypass.movilidad.service;

import com.citypass.movilidad.model.enums.BikeStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class BikeStatusTransitionPolicy {

    private static final Map<BikeStatus, Set<BikeStatus>> ADMIN_TRANSITIONS = new EnumMap<>(BikeStatus.class);

    static {
        ADMIN_TRANSITIONS.put(BikeStatus.AVAILABLE,
                EnumSet.of(BikeStatus.MAINTENANCE, BikeStatus.OUT_OF_SERVICE, BikeStatus.STOLEN));
        ADMIN_TRANSITIONS.put(BikeStatus.MAINTENANCE,
                EnumSet.of(BikeStatus.AVAILABLE, BikeStatus.OUT_OF_SERVICE, BikeStatus.STOLEN));
        ADMIN_TRANSITIONS.put(BikeStatus.OUT_OF_SERVICE,
                EnumSet.of(BikeStatus.MAINTENANCE, BikeStatus.STOLEN));
        ADMIN_TRANSITIONS.put(BikeStatus.STOLEN,
                EnumSet.of(BikeStatus.MAINTENANCE, BikeStatus.OUT_OF_SERVICE));
        ADMIN_TRANSITIONS.put(BikeStatus.IN_USE, EnumSet.noneOf(BikeStatus.class));
    }

    private BikeStatusTransitionPolicy() {
    }

    public static boolean isAllowedForAdministration(BikeStatus from, BikeStatus to) {
        return from != null && to != null && ADMIN_TRANSITIONS.get(from).contains(to);
    }
}
