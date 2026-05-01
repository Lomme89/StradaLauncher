package com.carlauncher;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * NotificationListenerService vuoto — serve SOLO per ottenere
 * il permesso di accedere alle MediaSession tramite MediaSessionManager.
 *
 * L'utente deve abilitarlo manualmente in:
 * Impostazioni → App → Accesso speciale → Accesso alle notifiche → CarLauncher ✓
 *
 * Senza questo step, il polling dei media funziona ma non legge i metadati.
 */
public class MediaListenerService extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // Non gestiamo le notifiche — ci serve solo il permesso
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {}
}
