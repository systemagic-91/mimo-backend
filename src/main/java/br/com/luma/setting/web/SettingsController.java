package br.com.luma.setting.web;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import br.com.luma.setting.dto.UpdateSettingsRequest;
import br.com.luma.setting.service.SettingsApplicationService;
import br.com.luma.shared.idempotency.IdempotentResponse;
import br.com.luma.shared.dto.SettingsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/v1/settings")
@Tag(name = "Configurações", description = "Parâmetros da loja e faixas de comissão.")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('OWNER')")
public class SettingsController {

    private final SettingsApplicationService settingsService;

    public SettingsController(SettingsApplicationService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    @Operation(summary = "Consulta as configurações da loja")
    public SettingsResponse get() {
        return settingsService.get();
    }

    @PatchMapping
    @Operation(summary = "Atualiza configurações da loja", parameters = @Parameter(ref = "#/components/parameters/IdempotencyKey"))
    public ResponseEntity<JsonNode> update(@Valid @RequestBody UpdateSettingsRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        IdempotentResponse result = settingsService.update(UUID.fromString(jwt.getSubject()), idempotencyKey,
                request, httpRequest.getRemoteAddr());
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
}