package com.gimble.bus_stop.dto.responses;

import lombok.Builder;
import lombok.Data;


@Data
@Builder
public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private long expiresIn;         // access token TTL in seconds
    private UserProfileResponse user;

    public static String BEARER = "Bearer";
}
