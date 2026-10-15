package br.com.luma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

import br.com.luma.shared.dto.PageEnvelope;
import br.com.luma.shared.dto.PaginationRequest;
import br.com.luma.shared.exception.ApiException;

class PaginationContractTests {

    @Test
    void defaultsAreOneBasedAndUseContractPageSize() {
        PaginationRequest request = new PaginationRequest();

        var pageable = request.toPageable(Set.of("updatedAt", "code"));

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(50);
    }

    @Test
    void sortUsesOnlyWhitelistedPropertiesAndExplicitDirection() {
        PaginationRequest request = new PaginationRequest();
        request.setPage(3);
        request.setPageSize(200);
        request.setSort(List.of("updatedAt,desc", "code,asc"));

        var pageable = request.toPageable(Set.of("updatedAt", "code"));

        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(200);
        assertThat(pageable.getSort().getOrderFor("updatedAt").isDescending()).isTrue();
        assertThat(pageable.getSort().getOrderFor("code").isAscending()).isTrue();
    }

    @Test
    void invalidPageSizeAndSortReturnStandardBadRequest() {
        PaginationRequest invalidSize = new PaginationRequest();
        invalidSize.setPageSize(201);
        assertThatThrownBy(() -> invalidSize.toPageable(Set.of("code")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getCode()).isEqualTo("INVALID_PAGINATION");
                    assertThat(exception.getFields()).containsKey("pageSize");
                });

        PaginationRequest invalidSort = new PaginationRequest();
        invalidSort.setSort(List.of("code desc; drop table pieces"));
        assertThatThrownBy(() -> invalidSort.toPageable(Set.of("code")))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getFields()).containsKey("sort"));
    }

    @Test
    void responseWrapsItemsAndOneBasedPaginationMetadata() {
        var page = new PageImpl<>(List.of("item"), PageRequest.of(1, 25), 51);

        PageEnvelope<String> response = PageEnvelope.from(page);

        assertThat(response.items()).containsExactly("item");
        assertThat(response.pagination().page()).isEqualTo(2);
        assertThat(response.pagination().pageSize()).isEqualTo(25);
        assertThat(response.pagination().total()).isEqualTo(51);
        assertThat(response.pagination().totalPages()).isEqualTo(3);
    }
}