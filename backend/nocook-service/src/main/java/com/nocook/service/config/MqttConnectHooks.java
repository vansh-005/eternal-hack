package com.nocook.service.config;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Callbacks run on every (re)connect of the MQTT client; clean-start sessions lose subscriptions, so users re-subscribe. */
public class MqttConnectHooks {

    private static final Logger log = LoggerFactory.getLogger(MqttConnectHooks.class);

    private final List<Runnable> hooks = new CopyOnWriteArrayList<>();

    public void register(Runnable hook) {
        hooks.add(hook);
    }

    public void fire() {
        for (Runnable hook : hooks) {
            try {
                hook.run();
            } catch (RuntimeException ex) {
                log.warn("mqtt connect hook failed reason={}", ex.toString());
            }
        }
    }
}
