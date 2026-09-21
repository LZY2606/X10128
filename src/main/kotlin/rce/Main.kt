package rce

import java.nio.file.Path

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5215
    var dataDir = "data"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> host = args[++i]
            "--port" -> port = args[++i].toInt()
            "--data" -> dataDir = args[++i]
            else -> System.err.println("未知参数：${args[i]}")
        }
        i++
    }
    val store = Store(Path.of(dataDir))
    val server = Server(store, host, port)
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    server.start()
    Thread.currentThread().join()
}
