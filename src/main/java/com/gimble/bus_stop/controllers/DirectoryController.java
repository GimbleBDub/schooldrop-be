package com.gimble.bus_stop.controllers;

import com.gimble.bus_stop.dto.responses.PageResponse;
import com.gimble.bus_stop.dto.responses.SchoolResponse;
import com.gimble.bus_stop.dto.responses.SchoolTermResponse;
import com.gimble.bus_stop.services.AdminService;
import com.gimble.bus_stop.services.SchoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Reference data any signed-in user can read. */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class DirectoryController {

    private final SchoolService schoolService;
    private final AdminService adminService;
    private final com.gimble.bus_stop.services.VehicleColourService vehicleColourService;

    @GetMapping("/schools")
    public PageResponse<SchoolResponse> schools(@RequestParam(required = false) String query,
                                                @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return schoolService.listVerified(query, pageable);
    }

    /** The vehicle colour dropdown. */
    @GetMapping("/vehicle-colours")
    public List<String> vehicleColours() {
        return vehicleColourService.activeNames();
    }

    @GetMapping("/terms")
    public List<SchoolTermResponse> upcomingTerms() {
        return adminService.listTerms(true);
    }
}
