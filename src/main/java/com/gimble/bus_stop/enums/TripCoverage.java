package com.gimble.bus_stop.enums;

public enum TripCoverage {
    MORNING,   // home -> school
    AFTERNOON, // school -> home
    BOTH;

    public int legsPerDay() {
        return this == BOTH ? 2 : 1;
    }

    public boolean includes(TripLeg leg) {
        return this == BOTH || (this == MORNING) == (leg == TripLeg.TO_SCHOOL);
    }
}
