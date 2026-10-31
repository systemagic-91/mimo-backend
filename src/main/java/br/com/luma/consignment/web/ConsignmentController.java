package br.com.luma.consignment.web;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import br.com.luma.consignment.dto.ConsignmentDetailsResponse;
import br.com.luma.consignment.dto.ConsignmentListItemResponse;
import br.com.luma.consignment.dto.CreateConsignmentRequest;
import br.com.luma.consignment.dto.MarkItemReturnedRequest;
import br.com.luma.consignment.dto.MarkItemSoldRequest;
import br.com.luma.consignment.service.ConsignmentApplicationService;
import br.com.luma.shared.dto.PageEnvelope;
import br.com.luma.shared.dto.PaginationRequest;
import br.com.luma.shared.idempotency.IdempotentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/v1/consignments")
@PreAuthorize("hasAnyRole('OWNER', 'STAFF')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Consignações", description = "Lotes entregues às vendedoras, vendas e devoluções.")
public class ConsignmentController {

    private final ConsignmentApplicationService consignmentService;

    public ConsignmentController(ConsignmentApplicationService consignmentService) {
        this.consignmentService = consignmentService;
    }

    @GetMapping
    @Operation(summary = "Lista lotes com itens em posse e status visual (em_dia, atrasada, quitada)")
    public PageEnvelope<ConsignmentListItemResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID sellerId,
            @RequestParam(required = false) Boolean overdue,
            @Valid @ModelAttribute PaginationRequest pagination) {
        return consignmentService.list(status, sellerId, overdue, pagination);
    }

    @GetMapping("/{consignmentId}")
    @Operation(summary = "Retorna o lote com a vendedora e todos os itens")
    public ConsignmentDetailsResponse get(@PathVariable UUID consignmentId) {
        return consignmentService.get(consignmentId);
    }

    @PostMapping
    @Operation(summary = "Cria um lote e entrega as peças à vendedora")
    public ResponseEntity<JsonNode> create(@Valid @RequestBody CreateConsignmentRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
        return response(consignmentService.create(UUID.fromString(jwt.getSubject()), idempotencyKey, request,
                httpRequest.getRemoteAddr()));
    }

    @PostMapping("/{consignmentId}/items/{pieceId}/sold")
    @Operation(summary = "Marca uma peça do lote como vendida")
    public ResponseEntity<JsonNode> sold(@PathVariable UUID consignmentId, @PathVariable UUID pieceId,
            @Valid @RequestBody(required = false) MarkItemSoldRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
        return response(consignmentService.markSold(UUID.fromString(jwt.getSubject()), idempotencyKey,
                consignmentId, pieceId, request, httpRequest.getRemoteAddr()));
    }

    @PostMapping("/{consignmentId}/items/{pieceId}/returned")
    @Operation(summary = "Marca uma peça do lote como devolvida e a devolve ao estoque")
    public ResponseEntity<JsonNode> returned(@PathVariable UUID consignmentId, @PathVariable UUID pieceId,
            @Valid @RequestBody(required = false) MarkItemReturnedRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
        return response(consignmentService.markReturned(UUID.fromString(jwt.getSubject()), idempotencyKey,
                consignmentId, pieceId, request, httpRequest.getRemoteAddr()));
    }

    private static ResponseEntity<JsonNode> response(IdempotentResponse response) {
        return ResponseEntity.status(response.status()).body(response.body());
    }
}
