package com.gimble.bus_stop.enums;

/** A driver account's role in a team / transport company. */
public enum OperatorRole {
    OWNER,   // the business: payouts, listing fee, adverts, team, vehicles, routes
    MANAGER, // runs operations: team drivers, vehicles, routes, today's runs
    DRIVER;  // drives the routes they're assigned

    public boolean canManage() {
        return this != DRIVER;
    }
}
