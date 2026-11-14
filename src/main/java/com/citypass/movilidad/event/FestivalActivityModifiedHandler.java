package com.citypass.movilidad.event;

import com.citypass.movilidad.eventbus.consume.EventHandler;
import com.citypass.movilidad.eventbus.consume.IncomingEvent;
import com.citypass.movilidad.service.FestivalRestockService;
import org.springframework.stereotype.Component;

@Component
public class FestivalActivityModifiedHandler implements EventHandler {
    private final FestivalRestockService restock;

    public FestivalActivityModifiedHandler(FestivalRestockService restock) {
        this.restock = restock;
    }

    @Override
    public String topic() {
        return "com.citypass.espacios.ActividadModificada";
    }

    @Override
    public void handle(IncomingEvent event) {
        restock.applyActivityChange(event.data());
    }
}
