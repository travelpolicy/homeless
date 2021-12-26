import com.pi4j.Pi4J
import com.pi4j.context.Context
import com.pi4j.io.i2c.I2C
import com.pi4j.io.i2c.I2CProvider
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServerOptions
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.*
import kotlin.reflect.KType
import kotlin.reflect.full.createType
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure

enum class SSD1306Command(val code: Int) {
    SSD1306_CTRL_BYTE_CMD_SINGLE       (0x80),
    SSD1306_CTRL_BYTE_CMD_STREAM       (0x00),
    SSD1306_CTRL_BYTE_DATA_STREAM      (0x40),

    // Fundamental commands (page 28)
    SSD1306_CMD_SET_CONTRAST           (0x81),
    SSD1306_CMD_DISPLAY_RAM            (0xA4),
    SSD1306_CMD_DISPLAY_ALLON          (0xA5),
    SSD1306_CMD_DISPLAY_NORMAL         (0xA6),
    SSD1306_CMD_DISPLAY_INVERTED       (0xA7),
    SSD1306_CMD_DISPLAY_OFF            (0xAE),
    SSD1306_CMD_DISPLAY_ON             (0xAF),

    // Addressing Command Table (page 30)
    SSD1306_CMD_SET_MEMORY_ADDR_MODE   (0x20),
    SSD1306_CMD_SET_COLUMN_RANGE       (0x21),
    SSD1306_CMD_SET_PAGE_RANGE         (0x22),

    // Hardware Config (page 31)
    SSD1306_CMD_SET_DISPLAY_START_LINE (0x40),
    SSD1306_CMD_SET_SEGMENT_REMAP      (0xA1),
    SSD1306_CMD_SET_MUX_RATIO          (0xA8),
    SSD1306_CMD_SET_COM_SCAN_MODE      (0xC8),
    SSD1306_CMD_SET_DISPLAY_OFFSET     (0xD3),
    SSD1306_CMD_SET_COM_PIN_MAP        (0xDA),
    SSD1306_CMD_NOP                    (0xE3),

    // Timing and Driving Scheme (page 32)
    SSD1306_CMD_SET_DISPLAY_CLK_DIV    (0xD5),
    SSD1306_CMD_SET_PRECHARGE          (0xD9),
    SSD1306_CMD_SET_VCOMH_DESELCT      (0xDB),

    // Charge Pump (page 62)
    SSD1306_CMD_SET_CHARGE_PUMP        (0x8D),
}

fun main(args: Array<String>) {
    val vertx = Vertx.vertx()
    val port = 8087

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

        final override suspend fun start() {
            val router = Router.router(vertx)

            router.get("/").coroutineHandler {
                htmlAsFlow(flow {
                    emit("<html>")
                    emit("<head><title>Test</title><head>")
                    emit("<body>")
                    emit("Hello")
                    emit("</body>")
                    emit("</html>")
                })
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

    val devices = args.map { ds ->
        val splits = ds.split(":")
        val d = I2CConnection::class.sealedSubclasses.firstOrNull {
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

        d.write(listOf(
            SSD1306Command.SSD1306_CTRL_BYTE_CMD_STREAM.code,
            SSD1306Command.SSD1306_CMD_DISPLAY_OFF.code,
            SSD1306Command.SSD1306_CMD_SET_MUX_RATIO.code,
            0x3F,
            // Set the display offset to 0
            SSD1306Command.SSD1306_CMD_SET_DISPLAY_OFFSET.code,
            0x00,
            // Display start line to 0
            SSD1306Command.SSD1306_CMD_SET_DISPLAY_START_LINE.code,
            // Mirror the x-axis. In case you set it up such that the pins are north.
            // 0xA0 - in case pins are south - default
            SSD1306Command.SSD1306_CMD_SET_SEGMENT_REMAP.code,
            // Mirror the y-axis. In case you set it up such that the pins are north.
            // 0xC0 - in case pins are south - default
            SSD1306Command.SSD1306_CMD_SET_COM_SCAN_MODE.code,
            // Default - alternate COM pin map
            SSD1306Command.SSD1306_CMD_SET_COM_PIN_MAP.code,
            0x12,
            // set contrast
            SSD1306Command.SSD1306_CMD_SET_CONTRAST.code,
            0x7F,
            // Set display to enable rendering from GDDRAM
            // (Graphic Display Data RAM)
            SSD1306Command.SSD1306_CMD_DISPLAY_RAM.code,
            // Normal mode!
            SSD1306Command.SSD1306_CMD_DISPLAY_NORMAL.code,
            // Default oscillator clock
            SSD1306Command.SSD1306_CMD_SET_DISPLAY_CLK_DIV.code,
            0x80,
            // Enable the charge pump
            SSD1306Command.SSD1306_CMD_SET_CHARGE_PUMP.code,
            0x14,
            // Set precharge cycles to high cap type
            SSD1306Command.SSD1306_CMD_SET_PRECHARGE.code,
            0x22,
            // Set the V_COMH deselect volatage to max
            SSD1306Command.SSD1306_CMD_SET_VCOMH_DESELCT.code,
            0x30,
            // Horizonatal addressing mode - same as the KS108 GLCD
            SSD1306Command.SSD1306_CMD_SET_MEMORY_ADDR_MODE.code,
            0x00,
            // Turn the Display ON
            SSD1306Command.SSD1306_CMD_DISPLAY_ON.code
        ).map {
            // print(it.toString(16).padStart(2, '0') + " ")
            it.toByte()
        }.toByteArray())
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