package br.com.luma.piece.web;

import java.util.List;
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

import br.com.luma.piece.dto.LabelPieceRequest;
import br.com.luma.piece.dto.NextPieceCodeResponse;
import br.com.luma.piece.dto.ReceivePieceRequest;
import br.com.luma.piece.service.PieceApplicationService;
import br.com.luma.shared.PieceStage;
import br.com.luma.shared.dto.PageEnvelope;
import br.com.luma.shared.dto.PaginationRequest;
import br.com.luma.shared.dto.PieceEventResponse;
import br.com.luma.shared.dto.PieceResponse;
import br.com.luma.shared.idempotency.IdempotentResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Validated
@RestController
@RequestMapping("/api/v1/pieces")
@PreAuthorize("isAuthenticated()")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Peças", description = "Estoque, recebimento, etiquetagem e histórico.")
public class PieceController {

    private final PieceApplicationService pieceService;

    public PieceController(PieceApplicationService pieceService) {
        this.pieceService = pieceService;
    }

    @GetMapping
    @Operation(summary = "Pesquisa e pagina peças do estoque")
    public PageEnvelope<PieceResponse> list(
            @RequestParam(required = false) @Size(max = 120) String q,
            @RequestParam(required = false) PieceStage status,
            @Valid @ModelAttribute PaginationRequest pagination) {
        return pieceService.list(q, status, pagination);
    }

    @GetMapping("/next-code")
    @Operation(summary = "Pré-visualiza o próximo código de peça")
    public NextPieceCodeResponse nextCode() {
        return new NextPieceCodeResponse(pieceService.previewNextCode());
    }

    @GetMapping("/{pieceId}")
    @Operation(summary = "Retorna os detalhes de uma peça")
    public PieceResponse get(@PathVariable UUID pieceId) {
        return pieceService.get(pieceId);
    }

    @GetMapping("/{pieceId}/events")
    @Operation(summary = "Retorna a timeline imutável de uma peça")
    public List<PieceEventResponse> events(@PathVariable UUID pieceId) {
        return pieceService.events(pieceId);
    }

    @PostMapping("/receive")
    @Operation(summary = "Recebe uma peça da fábrica ou a encaminha ao banho")
    public ResponseEntity<JsonNode> receive(@Valid @RequestBody ReceivePieceRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        return response(pieceService.receive(UUID.fromString(jwt.getSubject()), idempotencyKey, request));
    }

    @PostMapping("/label")
    @Operation(summary = "Etiqueta uma peça existente que retornou do banho")
    public ResponseEntity<JsonNode> label(@Valid @RequestBody LabelPieceRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        return response(pieceService.label(UUID.fromString(jwt.getSubject()), idempotencyKey, request));
    }

    private static ResponseEntity<JsonNode> response(IdempotentResponse response) {
        return ResponseEntity.status(response.status()).body(response.body());
    }
}
