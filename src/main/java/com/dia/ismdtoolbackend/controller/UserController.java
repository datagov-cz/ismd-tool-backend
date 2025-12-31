package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.UserInfoDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for user-related endpoints.
 * Provides authenticated user information for frontend.
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    /**
     * Returns current authenticated user information.
     * Extracts sanitized user data from validated JWT token.
     * Does NOT expose sensitive JWT claims or encrypted data.
     *
     * @param securityUser Current authenticated user from JWT
     * @return UserInfoDto containing userId, displayName, roles, and isAdmin flag
     */
    @GetMapping("/me")
    public ApiResponseDto<UserInfoDto> getCurrentUser(@AuthenticationPrincipal SecurityUser securityUser) {
        log.debug("Fetching current user info for userId: {}", securityUser.getUserId());

        UserInfoDto userInfo = new UserInfoDto(
                securityUser.getUserId(),
                securityUser.getDisplayName(),
                securityUser.getRoles(),
                securityUser.isAdmin()
        );

        log.debug("Returning user info: userId={}, roles={}, isAdmin={}",
                userInfo.getUserId(), userInfo.getRoles(), userInfo.isAdmin());

        return ApiResponseDto.success(userInfo, "User info retrieved successfully");
    }
}