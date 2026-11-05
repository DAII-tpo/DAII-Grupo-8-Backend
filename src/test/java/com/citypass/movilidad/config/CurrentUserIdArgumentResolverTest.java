package com.citypass.movilidad.config;

import com.citypass.movilidad.service.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Resolución del parámetro anotado con {@link CurrentUserId}. */
class CurrentUserIdArgumentResolverTest {

    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final CurrentUserIdArgumentResolver resolver =
            new CurrentUserIdArgumentResolver(providerOf(currentUserService));

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CurrentUserService> providerOf(CurrentUserService service) {
        ObjectProvider<CurrentUserService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(service);
        return provider;
    }

    /** Métodos de prueba de los que se leen los parámetros por reflexión. */
    @SuppressWarnings("unused")
    static class Endpoints {
        void anotado(@CurrentUserId Long userId) {
        }

        void anotadoPeroNoEsLong(@CurrentUserId String userId) {
        }

        void sinAnotar(Long userId) {
        }
    }

    private static MethodParameter parametroDe(String metodo, Class<?> tipo) {
        try {
            Method method = Endpoints.class.getDeclaredMethod(metodo, tipo);
            return new MethodParameter(method, 0);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void soportaUnLongAnotado() {
        assertThat(resolver.supportsParameter(parametroDe("anotado", Long.class))).isTrue();
    }

    @Test
    void noSoportaUnParametroSinLaAnotacion() {
        assertThat(resolver.supportsParameter(parametroDe("sinAnotar", Long.class))).isFalse();
    }

    @Test
    void noSoportaUnTipoQueNoEsLong() {
        assertThat(resolver.supportsParameter(parametroDe("anotadoPeroNoEsLong", String.class)))
                .isFalse();
    }

    @Test
    void devuelveElIdDeLaPersonaAutenticada() {
        when(currentUserService.currentUserId()).thenReturn(42L);
        assertThat(resolver.resolveArgument(parametroDe("anotado", Long.class), null, null, null))
                .isEqualTo(42L);
    }

    @Test
    void laConfiguracionWebRegistraElResolver() {
        List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();
        new WebMvcAuthConfig(providerOf(currentUserService)).addArgumentResolvers(resolvers);
        assertThat(resolvers).singleElement().isInstanceOf(CurrentUserIdArgumentResolver.class);
    }
}
