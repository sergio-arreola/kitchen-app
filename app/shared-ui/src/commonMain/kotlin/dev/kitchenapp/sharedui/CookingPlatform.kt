package dev.kitchenapp.sharedui

/** Platform operations used by the cooking flow. Reminder delivery is best effort. */
interface CookingPlatform {
    fun bindLifecycle(onAway: () -> Unit, onReturn: () -> Unit)
    fun unbindLifecycle()
    fun bindReminderOpen(onOpen: (String?) -> Unit)
    fun unbindReminderOpen()
    fun bindStatus(onStatus: (String) -> Unit)
    fun unbindStatus()
    fun setCookingScreen(active: Boolean, focusAllowed: Boolean)
    fun checkInMinutes(sessionId: String): Int?
    fun setCheckInMinutes(sessionId: String, minutes: Int?, onResult: (Boolean) -> Unit)
    fun scheduleCheckIn(sessionId: String, generation: Long, minutes: Int)
    fun cancelCheckIn(sessionId: String)
    fun scheduleLeftover(eventId: String, minutes: Int, onResult: (Boolean) -> Unit)
    fun cancelLeftover(eventId: String)
}

object NoCookingPlatform : CookingPlatform {
    override fun bindLifecycle(onAway: () -> Unit, onReturn: () -> Unit) = Unit
    override fun unbindLifecycle() = Unit
    override fun bindReminderOpen(onOpen: (String?) -> Unit) = Unit
    override fun unbindReminderOpen() = Unit
    override fun bindStatus(onStatus: (String) -> Unit) = Unit
    override fun unbindStatus() = Unit
    override fun setCookingScreen(active: Boolean, focusAllowed: Boolean) = Unit
    override fun checkInMinutes(sessionId: String): Int? = null
    override fun setCheckInMinutes(sessionId: String, minutes: Int?, onResult: (Boolean) -> Unit) =
        onResult(minutes == null)
    override fun scheduleCheckIn(sessionId: String, generation: Long, minutes: Int) = Unit
    override fun cancelCheckIn(sessionId: String) = Unit
    override fun scheduleLeftover(eventId: String, minutes: Int, onResult: (Boolean) -> Unit) = onResult(false)
    override fun cancelLeftover(eventId: String) = Unit
}
