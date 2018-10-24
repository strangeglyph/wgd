
import biweekly.ICalendar
import me.ivmg.telegram.Bot
import me.ivmg.telegram.bot
import me.ivmg.telegram.dispatch
import me.ivmg.telegram.dispatcher.command
import org.joda.time.DateTime

/**
 * @author µ
 */
class Service(val config: ServiceConf) {

    companion object {
        const val ALERT_TIME = 8 * 60 * 60 * 1000
    }

    private val bot: Bot
    private var running = false

    private val taskChecker = scheduleTaskThread()

    private val reminderChecker = scheduleReminderThread()

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
        Thread(Runnable { bot.startPolling() }, "telegram").start()
        taskChecker.start()
        reminderChecker.start()
        running = true
    }

    fun importReminders(ical: ICalendar) {
        for (event in ical.events) {
            if (DateTime(event.dateStart.value).isBeforeNow) continue
            Database.createReminder(event.summary.value, DateTime(event.dateStart.value))
        }
    }

    fun createTask(desc: String, dueDate: DateTime, interval: Int, participatingUsers: List<String>) {
        Database.createTask(desc, dueDate, interval, participatingUsers)
    }

    private fun scheduleReminderThread(): Thread {
        val reminderChecker = Thread {
            while (true) {
                Thread.sleep(5 * 60 * 1000)
                val reminders = Database.remindersWithin(ALERT_TIME)
                for (reminder in reminders) {
                    println("Reminder due: ${reminder.desc}")
                    notifyChat("Reminder: ${reminder.desc}")
                    Database.removeReminder(reminder)
                }
            }
        }
        reminderChecker.name = "reminder"
        reminderChecker.isDaemon = true

        return reminderChecker
    }

    private fun scheduleTaskThread(): Thread {
        val taskChecker = Thread {
            while (true) {
                Thread.sleep(5 * 60 * 1000)
                val tasks = Database.tasksWithin(ALERT_TIME)
                for (task in tasks) {
                    val respUser = Database.nextUserResponsibleFor(task)
                    println("Task due: ${respUser} for ${task.desc}")
                    notifyChat("Task due: ${respUser.name} for ${task.desc}")
                    Database.updateTask(task, respUser)
                }
            }
        }
        taskChecker.name = "task"
        taskChecker.isDaemon = true

        return taskChecker
    }
}

