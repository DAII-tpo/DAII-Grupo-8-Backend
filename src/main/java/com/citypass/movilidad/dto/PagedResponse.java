package com.citypass.movilidad.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Página de resultados de una consulta de lectura.
 *
 * Existe para no serializar directamente el {@code Page} de Spring Data: su JSON incluye la
 * estructura interna de {@code Pageable}, no es un contrato estable entre versiones y Spring Boot
 * avisa de eso desde la 3.3. Este record es el contrato que ve el frontend.
 */
@Schema(description = "Página de resultados")
public record PagedResponse<T>(

        @Schema(description = "Elementos de la página; puede venir vacía")
        List<T> content,

        @Schema(description = "Número de página, empezando en 0", example = "0")
        int page,

        @Schema(description = "Cantidad de elementos por página", example = "10")
        int size,

        @Schema(description = "Total de elementos que cumplen la consulta", example = "25")
        long totalElements,

        @Schema(description = "Cantidad total de páginas", example = "3")
        int totalPages,

        @Schema(description = "Indica si es la última página", example = "false")
        boolean last
) {

    public static <T> PagedResponse<T> of(Page<T> page) {
        return new PagedResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.isLast());
    }
}
