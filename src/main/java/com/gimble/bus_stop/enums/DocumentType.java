package com.gimble.bus_stop.enums;

import java.util.EnumSet;
import java.util.Set;

public enum DocumentType {
    // Driver documents
    OMANG_FRONT,
    OMANG_BACK,
    DRIVERS_LICENCE,
    POLICE_CLEARANCE,   // Botswana Police Service clearance certificate
    SELFIE,             // matched by the verifier against the Omang photo
    PSV_PERMIT,         // public service vehicle permit, if the driver has one
    COMPANY_REGISTRATION, // CIPA certificate — owners of a transport company
    // Vehicle documents
    VEHICLE_REGISTRATION,
    ROADWORTHY_CERTIFICATE,
    INSURANCE,          // optional — not required in Botswana
    VEHICLE_PHOTO;

    public static final Set<DocumentType> REQUIRED_FOR_DRIVER =
            EnumSet.of(OMANG_FRONT, OMANG_BACK, DRIVERS_LICENCE, POLICE_CLEARANCE, SELFIE);

    // Insurance isn't required in Botswana, so it's not asked for (older uploads are still kept)
    public static final Set<DocumentType> REQUIRED_FOR_VEHICLE =
            EnumSet.of(VEHICLE_REGISTRATION, ROADWORTHY_CERTIFICATE, VEHICLE_PHOTO);

    private static final Set<DocumentType> VEHICLE_DOCUMENTS =
            EnumSet.of(VEHICLE_REGISTRATION, ROADWORTHY_CERTIFICATE, INSURANCE, VEHICLE_PHOTO);

    public boolean isVehicleDocument() {
        return VEHICLE_DOCUMENTS.contains(this);
    }
}
