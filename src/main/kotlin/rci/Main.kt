package rci

import rci.store.AppService
import rci.store.FileStore
import rci.web.WebServer
import java.nio.file.Paths

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5215
    var dataDir = "data"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[++i] }
            "--port" -> { port = args[++i].toInt() }
            "--data" -> { dataDir = args[++i] }
            else -> System.err.println("未知参数：${args[i]}")
        }
        i++
    }
    val store = FileStore(Paths.get(dataDir, "rci.json"))
    val app = AppService(store)
    val server = WebServer(app, host, port)
    server.start()
    println("规则冲突解释器已启动：http://$host:$port （数据目录 $dataDir）")
}
