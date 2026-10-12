package com.gimble.bus_stop.notifications;

import java.util.List;
import java.util.Map;

/** Delivers push notifications to devices. Swap in an FCM implementation for production. */
public interface PushSender {

    void send(List<String> deviceTokens, String title, String body, Map<String, String> data);
}
