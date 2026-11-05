package com.citypass.movilidad.eventbus.consume;

/**
 * Procesa los eventos de un tópico del bus. Para consumir un tópico nuevo alcanza con registrar un bean que
 * implemente esta interfaz: el listener se suscribe a los tópicos de todos los handlers.
 * <p>
 * handle corre dentro de la misma transacción que registra el evento como procesado, así que sus cambios en
 * la base y la marca de "ya procesado" se confirman juntos. Si lanza una excepción, se hace rollback y el
 * evento se reintenta; no atrapes errores para "seguir de largo", porque el offset avanzaría igual.
 */
public interface EventHandler {

    /** Tópico que consume: el fqn del event type (ej. com.citypass.emergencias.EmergenciaReportada). */
    String topic();

    void handle(IncomingEvent event);
}
