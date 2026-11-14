package com.citypass.movilidad.event;

import com.citypass.movilidad.eventbus.consume.EventHandler;
import com.citypass.movilidad.eventbus.consume.IncomingEvent;
import com.citypass.movilidad.service.FestivalRestockService;
import org.springframework.stereotype.Component;

@Component
public class FestivalActivityCreatedHandler implements EventHandler {
    private final FestivalRestockService restock;

    public FestivalActivityCreatedHandler(FestivalRestockService restock) {
        this.restock = restock;
    }

    @Override
    public String topic() {
        return "com.citypass.espacios.ActividadCreada.v2";
    }

    @Override
    public void handle(IncomingEvent event) {
        restock.planFromActivity(event.data());
    }
}
