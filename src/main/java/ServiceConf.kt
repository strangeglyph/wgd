
import java.io.File
import java.util.*
import kotlin.system.exitProcess

/**
 * @author µ
 */
class ServiceConf {

    val listenPort: Int
    val baseDir: String
    val telegramToken: String
    val telegramChat: Long

    constructor(path: String) {
        val source = File(path)
        if (!source.exists()) {
            println("Failed to load config")
            exitProcess(1)
        }

        val prop = Properties()
        prop.load(source.inputStream())

        listenPort = prop.getProperty("listen_port", "16787").toInt()
        baseDir = prop.getProperty("base_dir", "${System.getProperty("java.io.tmpdir")}/wgd")
        telegramToken = checkNotNull(prop.getProperty("telegram_token"))
        telegramChat = checkNotNull(prop.getProperty("telegram_chat_id")).toLong()
    }
}