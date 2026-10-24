package com.citypass.movilidad.eventbus.consume;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;

/**
 * Contenedor de Kafka suscripto a los tópicos de los EventHandler registrados.
 * Se arma a mano en lugar de con @KafkaListener porque la lista de tópicos sale de los handlers y puede
 * estar vacía (entonces no se conecta al broker).
 */
public class EventBusListener implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(EventBusListener.class);

    private final ConcurrentKafkaListenerContainerFactory<Object, Object> containerFactory;
    private final EventDispatcher dispatcher;

    private ConcurrentMessageListenerContainer<Object, Object> container;
    private volatile boolean running;

    public EventBusListener(ConcurrentKafkaListenerContainerFactory<Object, Object> containerFactory,
                            EventDispatcher dispatcher) {
        this.containerFactory = containerFactory;
        this.dispatcher = dispatcher;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        String[] topics = dispatcher.topics().toArray(String[]::new);
        if (topics.length == 0) {
            LOG.info("Consumo del bus activado, pero no hay EventHandler registrados: no se suscribe a ningún tópico");
        } else {
            container = containerFactory.createContainer(topics);
            container.setBeanName("eventBusListener");
            // El commit del offset lo hace el contenedor después de que dispatch termina bien (ack-mode=record).
            container.setupMessageListener((MessageListener<Object, Object>) dispatcher::dispatch);
            container.start();
            LOG.info("Consumiendo del bus los tópicos {}", (Object) topics);
        }
        running = true;
    }

    @Override
    public synchronized void stop() {
        if (container != null) {
            container.stop();
            container = null;
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
