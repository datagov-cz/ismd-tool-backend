package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.UserInfoDto;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    @Operation(
            summary = "Získání informací o aktuálním uživateli",
            description = "Vrací informace o aktuálně přihlášeném uživateli extrahované z JWT tokenu. Obsahuje ID uživatele, zobrazované jméno, role a příznak administrátora."
    )
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