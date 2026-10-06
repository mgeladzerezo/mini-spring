package io.minispring.core.event;

/** Publishes an event to every listener whose parameter type accepts it. Any object can be an event. */
public interface ApplicationEventPublisher {

    /**
     * Delivers the event synchronously, on the calling thread, in listener order. A listener
     * therefore runs inside the publisher's transaction, and an exception it throws propagates
     * to the publisher.
     */
    void publishEvent(Object event);
}
