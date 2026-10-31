package br.com.luma.user.web;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import br.com.luma.shared.dto.PageEnvelope;
import br.com.luma.shared.dto.PaginationRequest;
import br.com.luma.shared.dto.UserResponse;
import br.com.luma.shared.idempotency.IdempotentResponse;
import br.com.luma.user.dto.CreateUserRequest;
import br.com.luma.user.dto.ResetPasswordRequest;
import br.com.luma.user.dto.UpdateUserRequest;
import br.com.luma.user.service.UserApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Usuários", description = "Contas de owners e staff da loja.")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('OWNER')")
public class UserController {

    private final UserApplicationService userService;

    public UserController(UserApplicationService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "Lista os usuários")
    public PageEnvelope<UserResponse> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) String role, @RequestParam(required = false) Boolean active,
            @Valid PaginationRequest pagination) {
        return userService.list(q, role, active, pagination);
    }

    @PostMapping
    @Operation(summary = "Cria um usuário com senha temporária",
            parameters = @Parameter(ref = "#/components/parameters/IdempotencyKey"))
    public ResponseEntity<JsonNode> create(@Valid @RequestBody CreateUserRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        return toResponse(userService.create(UUID.fromString(jwt.getSubject()), idempotencyKey, request,
                httpRequest.getRemoteAddr()));
    }

    @PatchMapping("/{userId}")
    @Operation(summary = "Atualiza, desativa ou reativa um usuário",
            parameters = @Parameter(ref = "#/components/parameters/IdempotencyKey"))
    public ResponseEntity<JsonNode> update(@PathVariable UUID userId, @Valid @RequestBody UpdateUserRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        return toResponse(userService.update(UUID.fromString(jwt.getSubject()), userId, idempotencyKey, request,
                httpRequest.getRemoteAddr()));
    }

    @PostMapping("/{userId}/reset-password")
    @Operation(summary = "Define uma nova senha temporária e desloga o usuário",
            parameters = @Parameter(ref = "#/components/parameters/IdempotencyKey"))
    public ResponseEntity<JsonNode> resetPassword(@PathVariable UUID userId,
            @Valid @RequestBody ResetPasswordRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        return toResponse(userService.resetPassword(UUID.fromString(jwt.getSubject()), userId, idempotencyKey,
                request, httpRequest.getRemoteAddr()));
    }

    private static ResponseEntity<JsonNode> toResponse(IdempotentResponse result) {
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
}
