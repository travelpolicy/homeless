import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServerOptions
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import javax.imageio.ImageIO
import kotlin.experimental.or
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

enum class DeviceData {
    FRAMES {
        override fun dataFlow(d: I2CConnection): Flow<String> =
            d.lastFrames.map { "<div>$it</div>" }
    },
    WRITES {
        override fun dataFlow(d: I2CConnection): Flow<String> =
            d.lastWrites.map { "<div>${it.joinToString("&nbsp;") { byte ->
                byte.toInt()
                    .and(0xff)
                    .toString(16)
                    .uppercase(Locale.ENGLISH)
                    .padStart(2, '0')
            }}</div>" }
    },
    ;

    abstract fun dataFlow(d: I2CConnection): Flow<String>
}

@OptIn(ExperimentalTime::class)
fun main(args: Array<String>) = runBlocking {
    val vertx = Vertx.vertx()
    val port = 8087

    val devices = args.map { ds ->
        val splits = ds.split(":")
        I2CConnection::class.sealedSubclasses.firstOrNull {
            it.simpleName?.lowercase(Locale.ENGLISH) == splits[0]
        }?.primaryConstructor?.let { pc ->
            pc.call(
                *splits.drop(1).zip(pc.parameters).map { (o, p) ->
                    if (p.type.jvmErasure.isSubclassOf(String::class)) {
                        o
                    } else if (p.type.jvmErasure.isSubclassOf(Int::class)) {
                        o.toInt(16)
                    } else if (p.type.jvmErasure.isSubclassOf(Boolean::class)) {
                        o.toBoolean()
                    } else {
                        error("Unsupported type ${p.name}")
                    }
                }.also {
                    println(it.joinToString(" "))
                }.toTypedArray()
            )
        } ?: error("Can't create device ${ds}")
    }

    vertx.deployVerticle(object : CoroutineVerticle() {
        fun Route.coroutineHandler(fn: suspend CoroutineHandler.(c: RoutingContext) -> Unit) {
            handler { rc ->
                val hndlr = CoroutineHandler(rc)
                launch(Dispatchers.IO + hndlr.job) {
                    hndlr.fn(rc)
                }
            }
        }

        inner class CoroutineHandler(val rc: RoutingContext) {
            val job = Job()

            suspend fun htmlAsFlow(stringsFlow: Flow<String>,
                                   headers: List<Pair<String, String>> =
                                       listOf("Content-type" to "text/html;charset=utf-8")) {
                responseAsFlow(stringsFlow.map { b -> b.toByteArray(Charsets.UTF_8) }, headers)
            }

            fun httpCancel() {
                job.cancel()
            }

            suspend fun responseAsFlow(buffersFlow: Flow<ByteArray>, headers: List<Pair<String, String>>) {
                rc.response().setChunked(true)
                headers.forEach {
                    rc.response().headers().add(it.first, it.second)
                }

                rc.response().closeHandler {
                    if (rc.response().closed()) {
                        httpCancel()
                    }
                }
                buffersFlow.collect {
                    if (rc.response().closed()) {
                        httpCancel()
                        return@collect
                    }
                    rc.response().write(Buffer.buffer(it))
                }

                rc.response().end()
            }
        }

        suspend fun FlowCollector<String>.html(
            title: String,
            content: suspend FlowCollector<String>.() -> Unit) {

            emit("<html lang=\"en\">")
            emit("<head><title>$title</title></head>")
            emit("<body>")
            content()
            emit("</body>")
            emit("</html>")
        }

        override suspend fun start() {
            val router = Router.router(vertx)

            router.get("/").coroutineHandler {
                htmlAsFlow(flow {
                    html("Main") {
                        emit("Main page")
                    }
                })
            }

            router.get("/devices").coroutineHandler {
                htmlAsFlow(flow {
                    html("Devices") {
                        emit(devices.joinToString("<br/>") { dev ->
                            "${dev.name}: " + DeviceData.values().joinToString("&nbsp;") { dd ->
                                "<a href='./${
                                    dd.name.lowercase(Locale.ENGLISH)
                                }?device=${dev.id}'>${dd.name.lowercase(Locale.ENGLISH)}</a>"
                            } + "&nbsp;" + "<a href='./screen?device=${dev.id}'>screen</a>"
                        })
                    }
                })
            }

            router.get("/screen").coroutineHandler {
                val devId = rc.request().getParam("device")
                val dev = devId?.let { id ->
                    devices.firstOrNull {
                        it.id == id
                    }
                }

                htmlAsFlow(flow {
                    html("Screen") {
                        if (dev == null) {
                            emit("Device ${devId} not found")
                        } else {
                            emit("<pre id='screen'></pre>")
                            emitAll(dev.screenFlow.map { ba_ ->
                                val scr = Array<Array<Boolean>>(64) {
                                    Array<Boolean>(128) { false }
                                }
                                ba_.withIndex().forEach { (idx, b) ->
                                    val x = idx % 128
                                    val yy = idx / 128 * 8
                                    for (ii in 0..7) {
                                        scr[yy + ii][x] = (b.toInt().shr(ii)).and(1) == 1
                                    }
                                }
                                """
                                    <script>
                                        var f = document.getElementById("screen");
                                        f.innerHTML = "${
                                            scr.withIndex().joinToString("\\n") { (idx, chunk) ->
                                                    idx.toString().padStart(2, '0') + " " +  
                                                    chunk.joinToString("") { 
                                                        if (it) "X" else "."
                                                    }
                                                } 
                                        }";
                                    </script>
                                """.trimIndent()
                            })
                        }
                    }
                })
            }

            DeviceData.values().forEach { dd ->
                router.get("/${dd.name.lowercase(Locale.ENGLISH)}").coroutineHandler {
                    val devId = rc.request().getParam("device")
                    val dev = devId?.let { id ->
                        devices.firstOrNull {
                            it.id == id
                        }
                    }

                    htmlAsFlow(flow {
                        html("Frames") {
                            if (dev == null) {
                                emit("Device ${devId} not found")
                            } else {
                                emitAll(dd.dataFlow(dev))
                            }
                        }
                    })
                }
            }

            vertx.createHttpServer(
                HttpServerOptions()
                    .setMaxFormAttributeSize(10_000_000)
                    .setTcpKeepAlive(true) //
                    .setMaxInitialLineLength(2_000_000) // We can operate with quite large URLs, let's allow more than standard 4K
                    .setCompressionSupported(true)
            )
                .requestHandler(router)
                // .webSocketHandler(wsHandler)
                .listen(port)
                .await()
        }
    })

    println("Server is running on port $port")


    val width = 128
    val height = 64
    val bi = BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY)

    val ig2 = bi.createGraphics()
    ig2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    val font = Font("Arial", Font.BOLD, height)
    ig2.font = font
    val message = "Hell"

    val c = Color.WHITE
    val color = Color.BLACK
    ig2.paint = color
    ig2.fillRect(0, 0, width, height)

    try {
        val fontMetrics = ig2.fontMetrics
        val stringWidth = fontMetrics.stringWidth(message)
        val stringHeight = fontMetrics.ascent - fontMetrics.descent

        ig2.paint = c

        ig2.drawString(message,
            (width - stringWidth) / 2,
            height - (height - stringHeight) / 2 - 1)

        // ig2.drawOval(0, 0, width - 1, height - 1)
    } catch (e: Throwable) {
        // Can't draw strings
    }

    // ImageIO.write(bi, "PNG", Files.newOutputStream(Path.of("/tmp/out.png")))

    devices.forEach { d ->
        launch(newSingleThreadContext("Renderer_${d}")) {
            d.write(SSD1306Command.initDisplay)

            val stepp = 64

            val r = Random(System.currentTimeMillis())
            val smallBuf = ByteArray(1 + stepp)
            smallBuf[0] = SSD1306Command.SSD1306_CTRL_BYTE_DATA_STREAM.code.toByte()

            while (true) {
                measureTime {
                    d.write(SSD1306Command.writePrefix)

                    val buffer = ByteArray(1024) { 0 }
                    val currSec = (System.currentTimeMillis() / 1000)
                    // buffer.fill(0xaa.toByte())
                    if (currSec % 3 != 0L) {
                        val graphicsBuf = (bi.data.dataBuffer as DataBufferByte).data

                        graphicsBuf.withIndex().forEach { (idx, b) ->
                            for (ii in 0..7) {
                                val x = (idx * 8 + (8 - ii)) % 128
                                val y = idx / 16
                                //
                                if ((b.toInt().shr(ii)).and(1) == 1) {
                                    // println("$x, $y")
                                    val byteIdx = (y / 8) * 128 + x
                                    // println("$byteIdx")
                                    buffer[byteIdx] = buffer[byteIdx].or(1.shl(y % 8).toByte())
                                }
                            }
                        }
                    } else {
                        System.arraycopy(ByteArray(1024) { it.and(0xff).toByte() }, 0,
                            buffer, 0, buffer.size)
                    }

                    /*
                    if (currSec % 3 == 1L) {
                        r.nextBytes(buffer)
                    } else if (currSec % 3 == 2L) {
                        // buffer.fill(0xaa.toByte())
                    }
                     */

                    for (off in 0 until buffer.size step stepp) {
                        System.arraycopy(buffer, off, smallBuf, 1, stepp)
                        d.write(smallBuf)
                    }
                }.let {
                    d.lastFrames.tryEmit(it.inWholeMilliseconds)
                }

                delay(25)
                // println("------------------")
                // println("Screen has updated")
            }
        }
    }

    /*
    val pi4j: Context = Pi4J.newAutoContext()
    val i2cProvider: I2CProvider = pi4j.provider("linuxfs-i2c")
    val i2cConfig = I2C.newConfigBuilder(pi4j)
        .id("ssd1306")
        .bus(0)
        .device(0x3c)
        .build()
    val displayDevice = i2cProvider.create(i2cConfig)

    println(displayDevice.isOpen)

    // displayDevice.register()

    .let {
        displayDevice.write(it.toByteArray())
    }
    println()
    println(displayDevice.isOpen)

    // I2CConfig i2cConfig = I2C.newConfigBuilder(pi4j).id("TCA9534").bus(1).device(0x3f).build();
     */

}