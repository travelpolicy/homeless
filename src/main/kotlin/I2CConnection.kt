import com.google.common.hash.Hashing
import com.pi4j.Pi4J
import com.pi4j.context.Context
import com.pi4j.io.i2c.I2C
import com.pi4j.io.i2c.I2CProvider
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.*

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
    ;

    companion object {
        val writePrefix = listOf(
            SSD1306Command.SSD1306_CTRL_BYTE_CMD_STREAM.code,
            // column 0 to 127
            SSD1306Command.SSD1306_CMD_SET_COLUMN_RANGE.code,
            0x00,
            0x7F,
            // page 0 to 7
            SSD1306Command.SSD1306_CMD_SET_PAGE_RANGE.code,
            0x00,
            0x07
        ).map {
            it.toByte()
        }.toByteArray()

        val initDisplay = listOf(
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
        }.toByteArray()
    }
}

sealed class I2CConnection {
    abstract val name: String

    val lastFrames = MutableSharedFlow<Long>(100, 100, BufferOverflow.DROP_OLDEST)
    val lastWrites = MutableSharedFlow<ByteArray>(100, 100, BufferOverflow.DROP_OLDEST)

    @Suppress("UnstableApiUsage")
    val id: String by lazy {
        Hashing.crc32().hashString(name, Charsets.UTF_8).toString()
    }

    fun write(bytes: ByteArray) {
        lastWrites.tryEmit(bytes)
        writeImpl(bytes, 0, bytes.size)
    }

    protected abstract fun writeImpl(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size)

    class Linux(val bus: Int, val device: Int) : I2CConnection() {
        override val name = "${javaClass.simpleName.lowercase(Locale.ENGLISH)}:bus_${bus}:dev_${device.toString(16)}"

        val pi4j: Context = Pi4J.newAutoContext()
        val i2cProvider: I2CProvider = pi4j.provider("linuxfs-i2c")
        val i2cConfig = I2C.newConfigBuilder(pi4j)
            .id("ssd1306")
            .bus(bus)
            .device(device)
            .build()
        val displayDevice = i2cProvider.create(i2cConfig)

        override fun writeImpl(bytes: ByteArray, offset: Int, length: Int) {
            displayDevice.write(bytes, offset, length)
        }
    }

    class Trace : I2CConnection() {
        override val name = "${javaClass.simpleName.lowercase(Locale.ENGLISH)}"

        override fun writeImpl(bytes: ByteArray, offset: Int, length: Int) {
            /*
            println(bytes
                .asList()
                .subList(offset, offset + length)
                .joinToString(" ") {
                    it.toInt().and(0xff).toString(16).padStart(2, '0')
                })
             */
        }
    }
}