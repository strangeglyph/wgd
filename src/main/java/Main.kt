
import biweekly.Biweekly
import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import org.joda.time.DateTime
import java.io.File

/**
 * @author µ
 */
fun main(argv: Array<String>) {
    val args = ArgParser(argv).parseInto(::Args)
    val config = ServiceConf(args.config)

    val service = Service(config)
    Database.init()

    for (path in args.imports) {
        println("Importing reminders from $path")
        for (ical in Biweekly.parse(File(path)).all()) {
            service.importReminders(ical)
        }
    }

    for (name in args.addUsers) {
        println("Adding user $name")
        Database.createUser(name)
    }

    for (name in args.removeUsers) {
        val user = Database.findUser(name)
        if (user != null) {
            println("Removing user ${user.name}")
            Database.removeUser(user)
        } else {
            println("No user named $name")
        }
    }

    task@if (args.addTask != null) {
        val desc = args.addTask!!
        if (args.taskDueDate == null) {
            println("Missing due date for task")
            return@task
        }

        val date = DateTime.parse(args.taskDueDate)
        val interval = args.taskInterval

        val users = if (args.taskUsers.isEmpty()) {
            Database.allUsernames()
        } else args.taskUsers

        service.createTask(desc, date, interval, users)
        println("Created task $desc")
    }

    if (args.start) {
        println("Starting service")
        service.execute()
    }

}

class Args(parser: ArgParser) {
    val config by parser
            .storing("-c", "--config", help="location of the config file", argName="FILE")
            .default("${System.getProperty("user.dir")}/config.properties")
    val imports by parser
            .adding("-i", "--imports", help="imports reminders from the specified calender", argName="FILE")
    val start by parser
            .flagging("-s", "--start", help="start the daemon")
    val addUsers by parser
            .adding("--adduser", help="add a user", argName="USERNAME")
    val removeUsers by parser
            .adding("--deluser", help="remove a user", argName="USERNAME")
    val addTask by parser
            .storing("--addtask", help="add a task", argName="TASKDESC")
            .default<String?>(null)
    val taskUsers by parser
            .adding("-p", "--taskuser", help="user participating in task", argName="USERNAME")
    val taskDueDate by parser
            .storing("--duedate", help="task next due date (YYYY-MM-DD)", argName="DATE")
            .default<String?>(null)
    val taskInterval by parser
            .storing("--interval", help="task interval in days", argName="INTERVAL") { toInt() }
            .default(7)
}