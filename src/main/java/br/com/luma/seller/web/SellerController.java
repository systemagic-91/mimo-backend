package br.com.luma.seller.web;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
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

import br.com.luma.seller.dto.CreateSellerRequest;
import br.com.luma.seller.dto.SellerCommissionResponse;
import br.com.luma.seller.dto.SellerListItemResponse;
import br.com.luma.seller.dto.SellerStatsResponse;
import br.com.luma.seller.dto.UpdateSellerRequest;
import br.com.luma.seller.service.SellerApplicationService;
import br.com.luma.shared.dto.ConsignmentResponse;
import br.com.luma.shared.dto.PageEnvelope;
import br.com.luma.shared.dto.PaginationRequest;
import br.com.luma.shared.dto.SellerResponse;
import br.com.luma.shared.idempotency.IdempotentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/v1/sellers")
@Tag(name = "Vendedoras", description = "Cadastro, operação e métricas de vendedoras.")
@SecurityRequirement(name = "bearerAuth")
public class SellerController {

    private final SellerApplicationService sellerService;

    public SellerController(SellerApplicationService sellerService) {
        this.sellerService = sellerService;
    }

    @GetMapping
    @Operation(summary = "Lista vendedoras com métricas de consignação")
    @PreAuthorize("hasAnyRole('OWNER', 'STAFF')")
    public PageEnvelope<SellerListItemResponse> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String q, @Valid PaginationRequest pagination) {
        return sellerService.list(status, q, pagination);
    }

    @PostMapping
    @Operation(summary = "Cria uma vendedora", parameters = @Parameter(ref = "#/components/parameters/IdempotencyKey"))
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<JsonNode> create(@Valid @RequestBody CreateSellerRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        IdempotentResponse result = sellerService.create(UUID.fromString(jwt.getSubject()), idempotencyKey,
                request, httpRequest.getRemoteAddr());
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }

    @GetMapping("/{sellerId}")
    @Operation(summary = "Consulta uma vendedora")
    @PreAuthorize("hasAnyRole('OWNER', 'STAFF')")
    public SellerResponse get(@PathVariable UUID sellerId) {
        return sellerService.get(sellerId);
    }

    @PatchMapping("/{sellerId}")
    @Operation(summary = "Atualiza ou inativa uma vendedora", parameters = @Parameter(ref = "#/components/parameters/IdempotencyKey"))
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<JsonNode> update(@PathVariable UUID sellerId,
            @Valid @RequestBody UpdateSellerRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        IdempotentResponse result = sellerService.update(UUID.fromString(jwt.getSubject()), sellerId,
                idempotencyKey, request, httpRequest.getRemoteAddr());
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }

    @GetMapping("/{sellerId}/stats")
    @Operation(summary = "Retorna posse, vendas e comissão total", security = @SecurityRequirement(name = "bearerAuth"))
    @PreAuthorize("hasRole('OWNER')")
    public SellerStatsResponse stats(@PathVariable UUID sellerId) {
        return sellerService.stats(sellerId);
    }

    @GetMapping("/{sellerId}/commission")
    @Operation(summary = "Retorna comissão histórica no período", security = @SecurityRequirement(name = "bearerAuth"))
    @PreAuthorize("hasRole('OWNER')")
    public SellerCommissionResponse commission(@PathVariable UUID sellerId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return sellerService.commission(sellerId, from, to);
    }

    @GetMapping("/{sellerId}/consignments")
    @Operation(summary = "Lista o histórico de consignações da vendedora")
    @PreAuthorize("hasAnyRole('OWNER', 'STAFF')")
    public PageEnvelope<ConsignmentResponse> consignments(@PathVariable UUID sellerId,
            @RequestParam(required = false) String status, @Valid PaginationRequest pagination) {
        return sellerService.consignments(sellerId, status, pagination);
    }
}