
import biweekly.ICalendar
import com.sun.akuma.Daemon
import me.ivmg.telegram.Bot
import me.ivmg.telegram.bot
import me.ivmg.telegram.dispatch
import me.ivmg.telegram.dispatcher.command
import org.apache.commons.lang3.SystemUtils
import org.joda.time.DateTime
import org.joda.time.DurationFieldType
import org.joda.time.Interval

/**
 * @author µ
 */
class Service(val config: ServiceConf) {

    companion object {
        const val ALERT_TIME = 8 * 60 * 60 * 1000
    }

    private val bot: Bot
    private var running = false

    private val taskChecker = scheduleTaskTask()

    private val reminderChecker = scheduleReminderTask()

    init {
        this.bot = bot {
            token = config.telegramToken
            dispatch {
                command("id") { _, update ->
                    notifyChat("Chat id: ${update.message?.chat?.id}")
                }
            }
        }
    }

    fun notifyChat(msg: String) {
        bot.sendMessage(config.telegramChat, text = msg)
    }

    fun execute() {
        if (SystemUtils.IS_OS_LINUX || SystemUtils.IS_OS_MAC || SystemUtils.IS_OS_SOLARIS) {
            daemonize()
        } else {
            println("Daemonization not supported on this operating system")
            println("Service will run in foreground")
        }

        Thread(Runnable { bot.startPolling() }, "telegram").start()
        taskChecker.start()
        reminderChecker.start()
        running = true
    }

    private fun daemonize() {
        val daemon = Daemon()
        if (daemon.isDaemonized) {
            init()
        } else {
            println("Forking to background...")
            daemon.daemonize()
        }
    }

    private fun init() {
        println("Forked to background")
    }

    fun importReminders(ical: ICalendar) {
        var shouldStartOneOffThread = false
        val timeToNextReminder = Database.timeToNextReminder()

        for (event in ical.events) {
            if (DateTime(event.dateStart.value).isBeforeNow) continue

            val timeToReminder = Interval(DateTime.now(), DateTime(event.dateStart.value)).toDurationMillis()
            if (timeToNextReminder != null && timeToReminder < timeToNextReminder) {
                shouldStartOneOffThread = true
            }
            Database.createReminder(event.summary.value, DateTime(event.dateStart.value))
        }

        if (running && shouldStartOneOffThread) {
            val t = Thread { oneTimeReminderCheck() }
            t.name = "reminder one-off"
            t.isDaemon = true
            t.start()
        }
    }

    fun createTask(desc: String, dueDate: DateTime, interval: Int, participatingUsers: List<String>) {
        var dueDate = dueDate
        while (dueDate.isBeforeNow) {
            dueDate = dueDate.withFieldAdded(DurationFieldType.days(), interval)
        }

        val timeToReminder = Interval(DateTime.now(), dueDate).toDurationMillis()
        val timeToNextTask = Database.timeToNextTask()

        val shouldStartOneOffThread = timeToNextTask != null && timeToReminder < timeToNextTask

        Database.createTask(desc, dueDate, interval, participatingUsers)

        if (running && shouldStartOneOffThread) {
            val t = Thread { oneTimeTaskCheck() }
            t.name = "task one-off"
            t.isDaemon = true
            t.start()
        }
    }

    private fun scheduleReminderTask(): Thread {
        val reminderChecker = Thread {
            while (true) {
                oneTimeReminderCheck()
            }
        }
        reminderChecker.name = "reminder"
        reminderChecker.isDaemon = true

        return reminderChecker
    }

    private fun scheduleTaskTask(): Thread {
        val taskChecker = Thread {
            while (true) {
                oneTimeTaskCheck()
            }
        }
        taskChecker.name = "task"
        taskChecker.isDaemon = true

        return taskChecker
    }

    private fun oneTimeReminderCheck() {
        val timeToNextReminder = Database.timeToNextReminder()
        if (timeToNextReminder == null) {
            Thread.sleep(60 * 1000)
        } else if (timeToNextReminder > ALERT_TIME) {
            Thread.sleep(timeToNextReminder - ALERT_TIME)
        }

        for (reminder in Database.remindersWithin(ALERT_TIME)) {
            notifyChat("Reminder: ${reminder.desc}")
            Database.removeReminder(reminder)
        }
    }

    private fun oneTimeTaskCheck() {
        val timeToNextTask = Database.timeToNextTask()
        if (timeToNextTask == null) {
            Thread.sleep(60 * 1000) // 1 min
            return
        } else if (timeToNextTask > ALERT_TIME) {
            Thread.sleep(timeToNextTask - ALERT_TIME)
        }

        for (task in Database.tasksWithin(ALERT_TIME)) {
            val respUser = Database.nextUserResponsibleFor(task)
            notifyChat("Task due: ${respUser.name} for ${task.desc}")
            Database.updateTask(task, respUser)
        }
    }
}

