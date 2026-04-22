package com.privacyguard.app.data.repository

import com.privacyguard.app.domain.model.Notification
import com.privacyguard.app.domain.model.NotificationType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

class NotificationRepository private constructor() {

    companion object {
        @Volatile
        private var INSTANCE: NotificationRepository? = null

        fun getInstance(): NotificationRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NotificationRepository().also { INSTANCE = it }
            }
        }
    }

    private val _notifications = MutableStateFlow<List<Notification>>(emptyList())
    val notifications: StateFlow<List<Notification>> = _notifications.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val notificationQueue = ConcurrentLinkedQueue<Notification>()
    private val maxSize = 100

    suspend fun addNotification(notification: Notification) {
        val updated = buildList {
            add(notification)
            addAll(_notifications.value.take(maxSize - 1))
        }
        _notifications.value = updated
        if (!notification.isRead) {
            _unreadCount.value = _unreadCount.value + 1
        }
        notificationQueue.offer(notification)
    }

    suspend fun markAsRead(notificationId: String) {
        var unread = _unreadCount.value
        _notifications.value = _notifications.value.map { item ->
            if (item.id == notificationId && !item.isRead) {
                unread = (unread - 1).coerceAtLeast(0)
                item.copy(isRead = true)
            } else {
                item
            }
        }
        _unreadCount.value = unread
    }

    suspend fun markAllAsRead() {
        _notifications.value = _notifications.value.map { it.copy(isRead = true) }
        _unreadCount.value = 0
    }

    suspend fun clearAll() {
        _notifications.value = emptyList()
        _unreadCount.value = 0
        notificationQueue.clear()
    }

    fun pollNextNotification(): Notification? = notificationQueue.poll()

    fun getNotificationsByType(type: NotificationType): List<Notification> {
        return _notifications.value.filter { it.type == type }
    }

    fun getUnreadNotifications(): List<Notification> {
        return _notifications.value.filter { !it.isRead }
    }
}
