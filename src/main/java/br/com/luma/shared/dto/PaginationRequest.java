package br.com.luma.shared.dto;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import br.com.luma.shared.exception.ApiException;

public class PaginationRequest {

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;

    private int page = DEFAULT_PAGE;
    private int pageSize = DEFAULT_PAGE_SIZE;
    private List<String> sort = new ArrayList<>();

    public Pageable toPageable(Set<String> allowedSortFields) {
        if (page < 1) {
            throw invalid("page", "page deve ser maior ou igual a 1.");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw invalid("pageSize", "pageSize deve estar entre 1 e 200.");
        }

        List<Sort.Order> orders = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : sort) {
            String[] parts = value.split(",", -1);
            String property = parts[0].trim();
            if (property.isEmpty() || !allowedSortFields.contains(property) || !seen.add(property)) {
                throw invalid("sort", "Ordenação inválida para este recurso.");
            }
            if (parts.length > 2) {
                throw invalid("sort", "Use sort=campo,asc ou sort=campo,desc.");
            }
            Sort.Direction direction = Sort.Direction.ASC;
            if (parts.length == 2) {
                try {
                    direction = Sort.Direction.fromString(parts[1].trim());
                } catch (IllegalArgumentException exception) {
                    throw invalid("sort", "A direção de ordenação deve ser asc ou desc.");
                }
            }
            orders.add(new Sort.Order(direction, property));
        }

        Sort sortOrder = orders.isEmpty() ? Sort.unsorted() : Sort.by(orders);
        return PageRequest.of(page - 1, pageSize, sortOrder);
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public List<String> getSort() {
        return sort;
    }

    public void setSort(List<String> sort) {
        this.sort = sort == null ? new ArrayList<>() : new ArrayList<>(sort);
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", message,
                java.util.Map.of(field, List.of(message)));
    }
}