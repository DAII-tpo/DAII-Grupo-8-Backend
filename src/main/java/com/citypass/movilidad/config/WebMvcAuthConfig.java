package com.citypass.movilidad.config;

import com.citypass.movilidad.service.CurrentUserService;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Registra el resolver de {@link CurrentUserId}. */
@Configuration
public class WebMvcAuthConfig implements WebMvcConfigurer {

    static {
        // Sin esto springdoc documenta el parámetro resuelto como un query param fantasma.
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUserId.class);
    }

    /**
     * Se pide por {@code ObjectProvider} y no directo porque los slices {@code @WebMvcTest} solo
     * cargan la capa web: sin esto, esta configuración arrastraría a cualquier test de controller a
     * necesitar los {@code @Service}. El servicio se resuelve recién cuando un endpoint usa de
     * verdad {@link CurrentUserId}.
     */
    private final ObjectProvider<CurrentUserService> currentUserService;

    public WebMvcAuthConfig(ObjectProvider<CurrentUserService> currentUserService) {
        this.currentUserService = currentUserService;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserIdArgumentResolver(currentUserService));
    }
}
