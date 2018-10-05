
import org.jetbrains.exposed.dao.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils.create
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.joda.time.DurationFieldType
import org.joda.time.Interval
import java.sql.Connection
import kotlin.system.exitProcess

/**
 * @author µ
 */
const val CURRENT_SCHEMA_VERSION = "1.0"

object Users : IntIdTable() {
    val name = text("name")
    val normalizedName = text("normalized_name")

    fun normalizeName(name: String): String = Regex("[^a-z]").replace(name.toLowerCase(), "")
}

object Tasks : IntIdTable() {
    val description = text("description")
    val nextDueDate = date("next_due_date")
    val interval = integer("interval")
}

object TaskParticipations : IntIdTable() {
    val user = reference("user", Users)
    val task = reference("task", Tasks)
    val lastParticipated = date("last_participated")
}

object Reminders : IntIdTable() {
    val description = text("description")
    val date = date("date")
}

object SchemaData : IdTable<String>() {
    override val id = text("key").entityId()
    val value = text("value")
}

class User(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<User>(Users)

    var name by Users.name
    var normalizedName by Users.normalizedName
}

class Task(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Task>(Tasks)

    var desc by Tasks.description
    var nextDueDate by Tasks.nextDueDate
    var interval by Tasks.interval
}

class TaskParticipationEntry(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TaskParticipationEntry>(TaskParticipations)

    var user by User referencedOn TaskParticipations.user
    var task by Task referencedOn TaskParticipations.task
    var lastParticipated by TaskParticipations.lastParticipated
}

class Reminder(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Reminder>(Reminders)

    var desc by Reminders.description
    var date by Reminders.date
}

class SchemaDatum(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, SchemaDatum>(SchemaData)

    var value by SchemaData.value
}

object Database {
    fun init() {
        print("Initializing DB at wgd.sqlite")
        Database.connect("jdbc:sqlite:wgd.sqlite", org.sqlite.JDBC::class.java.canonicalName)
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

        val dbSchemaVersion = transaction {
            create(Users, Tasks, TaskParticipations, Reminders, SchemaData)
            var schemaVersion = SchemaDatum.findById("schema_version")
            if (schemaVersion == null) {
                schemaVersion = SchemaDatum.new(id = "schema_version") {
                    value = CURRENT_SCHEMA_VERSION
                }
            }
            schemaVersion
        }

        println("DB schema version: ${dbSchemaVersion?.value}")
        println("Current schema version: $CURRENT_SCHEMA_VERSION")

        if (dbSchemaVersion?.value != CURRENT_SCHEMA_VERSION) {
            println("Schema upgrade not supported yet")
            exitProcess(1)
        }
    }

    fun createReminder(desc: String, date: DateTime) {
        transaction {
            Reminder.new {
                this.desc = desc
                this.date = date
            }
        }
    }

    fun createUser(name: String) {
        transaction {
            User.new {
                this.name = name
                this.normalizedName = Users.normalizeName(name)
            }
        }
    }

    fun createTask(desc: String, nextDueDate: DateTime, interval: Int, memberNames: List<String>) {
        transaction {
            val members = ArrayList<User>()
            for (name in memberNames) {
                val member = User.find { Users.normalizedName eq Users.normalizeName(name) }
                if (member.empty()) {
                    throw IllegalArgumentException("No user named $name")
                } else {
                    members.add(member.first())
                }
            }

            val task = Task.new {
                this.desc = desc
                this.nextDueDate = nextDueDate
                this.interval = interval
            }

            for (member in members) {
                TaskParticipationEntry.new {
                    this.user = member
                    this.task = task
                    this.lastParticipated = DateTime.now()
                }
            }
        }
    }

    fun updateTask(task: Task, user: User) {
        transaction {
            task.nextDueDate = task.nextDueDate.withFieldAdded(DurationFieldType.days(), task.interval)
            TaskParticipationEntry
                    .find { (TaskParticipations.task eq task.id) and (TaskParticipations.user eq user.id) }
                    .first().lastParticipated = DateTime.now()
        }
    }

    fun timeToNextTask(): Long? {
        return transaction {
            Tasks.selectAll()
                    .orderBy(Tasks.nextDueDate)
                    .map { it[Tasks.nextDueDate] }
                    .map {
                        if (it.isAfterNow)
                            Interval(DateTime.now(), it).toDurationMillis()
                        else
                            0
                    }
                    .firstOrNull()
        }
    }

    fun timeToNextReminder(): Long? {
        return transaction {
            Reminders.selectAll()
                    .orderBy(Reminders.date)
                    .map { it[Reminders.date] }
                    .map {
                        if (it.isAfterNow)
                            Interval(DateTime.now(), it).toDurationMillis()
                        else
                            0
                    }
                    .firstOrNull()
        }
    }

    fun remindersWithin(span: Int): Iterable<Reminder> {
        return transaction {
            Reminder.find { Reminders.date lessEq DateTime.now().withFieldAdded(DurationFieldType.millis(), span) }.toList()
        }
    }

    fun tasksWithin(span: Int): Iterable<Task> {
        return transaction {
            Task.find { Tasks.nextDueDate lessEq DateTime.now().withFieldAdded(DurationFieldType.millis(), span) }.toList()
        }
    }

    fun nextUserResponsibleFor(task: Task): User {
        return transaction {
            TaskParticipations
                    .select { TaskParticipations.task eq task.id }
                    .orderBy(TaskParticipations.lastParticipated)
                    .map { it[TaskParticipations.user] }
                    .map { User.findById(it) }
                    .map { checkNotNull(it) }
                    .first()
        }
    }

    fun removeUser(user: User) {
        transaction {
            TaskParticipationEntry
                    .find { TaskParticipations.user eq user.id }
                    .map { it.delete() }

            user.delete()
        }
    }

    fun removeTask(task: Task) {
        transaction {
            TaskParticipationEntry
                    .find { TaskParticipations.task eq task.id }
                    .map { it.delete() }

            task.delete()
        }
    }

    fun removeReminder(reminder: Reminder) {
        transaction { reminder.delete() }
    }

    fun findUser(name: String): User? {
        return transaction {
            User.find { Users.normalizedName eq Users.normalizeName(name) }.firstOrNull()
        }
    }

    fun allUsernames(): List<String> {
        return transaction {
            Users.selectAll()
                    .map { it[Users.name] }
                    .toList()
        }
    }
}