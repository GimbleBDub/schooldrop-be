package com.gimble.bus_stop.services;

import com.gimble.bus_stop.dto.responses.UserProfileResponse;
import com.gimble.bus_stop.entities.HouseholdMember;
import com.gimble.bus_stop.entities.SchoolStaff;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.Role;
import com.gimble.bus_stop.repositories.HouseholdMemberRepository;
import com.gimble.bus_stop.repositories.SchoolStaffRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The signed-in user's profile, including their role in their family or school (the app adapts to it). */
@Component
@RequiredArgsConstructor
public class ProfileAssembler {

    private final HouseholdMemberRepository householdMemberRepository;
    private final SchoolStaffRepository schoolStaffRepository;
    private final com.gimble.bus_stop.repositories.DriverProfileRepository driverProfileRepository;

    @Transactional(readOnly = true)
    public UserProfileResponse of(User user) {
        UserProfileResponse profile = UserProfileResponse.from(user);
        if (user.getRole() == Role.PARENT) {
            householdMemberRepository.findByUserId(user.getId())
                    .map(HouseholdMember::getRole).ifPresent(profile::setHouseholdRole);
        }
        if (user.getRole() == Role.DRIVER) {
            driverProfileRepository.findByUserId(user.getId()).filter(d -> d.getOperator() != null).ifPresent(d -> {
                profile.setTeamRole(d.getOperatorRole());
                profile.setTeamName(d.getOperator().getName());
            });
        }
        if (user.getRole() == Role.SCHOOL) {
            schoolStaffRepository.findByUserId(user.getId())
                    .map(SchoolStaff::getRole).ifPresent(profile::setSchoolRole);
        }
        return profile;
    }
}
